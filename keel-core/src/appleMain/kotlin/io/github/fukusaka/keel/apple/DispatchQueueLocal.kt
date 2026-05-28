package io.github.fukusaka.keel.apple

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.staticCFunction
import platform.darwin.dispatch_get_specific
import platform.darwin.dispatch_queue_set_specific
import platform.darwin.dispatch_queue_t

/**
 * Per-`dispatch_queue_t` storage for a value of type [T]. The Apple-platform
 * analogue of Kotlin's `ThreadLocal`, with the same "name a slot, install on
 * a scope, read from inside that scope" shape — but the slot is bound to a
 * GCD dispatch queue rather than an OS thread.
 *
 * **Why a queue-local, not a thread-local.** GCD's serial-queue contract
 * guarantees execution order between blocks on one queue but does **not**
 * pin them to one OS thread. A single GCD worker pthread serves blocks
 * from many independent queues during its lifetime, and a single queue's
 * blocks migrate across the worker pool. A naive `@ThreadLocal` therefore
 * collocates state belonging to different queues on the same worker
 * pthread, breaking any "exactly one owner" invariant that downstream
 * consumers rely on — see the K56b investigation
 * (`HttpHeadersPool` cross-connection aliasing) for the canonical example
 * of how a `@ThreadLocal` design fails when handed GCD queues.
 *
 * **Mechanism.** Each [DispatchQueueLocal] instance owns a process-lifetime
 * key pointer (a [StableRef] to a unique placeholder object, never
 * disposed). [install] calls `dispatch_queue_set_specific(queue, key,
 * StableRef(value), destructor)` so GCD owns the value's [StableRef]
 * lifetime: it disposes the ref when [queue] is finally released. [current]
 * does the inverse `dispatch_get_specific(key)` lookup. From inside a
 * block running on a queue that had [install] called, [current] returns
 * that queue's value; from any other context (including blocks on an
 * untagged queue, or non-GCD pthreads such as a kqueue / epoll EventLoop)
 * [current] falls back to [fallback].
 *
 * **Layering.** Pure Apple-platform utility — uses only `platform.darwin`
 * `dispatch_*` symbols. Available in `appleMain`, the keel-platform-Apple
 * source set common to macOS / iOS / tvOS / watchOS targets. There is no
 * cross-platform projection because no other platform has the same problem
 * shape (JVM's `ThreadLocal` and K/N's `@ThreadLocal` both already preserve
 * per-pthread isolation, which is enough for keel's pthread-pinned engines:
 * kqueue / epoll / io_uring / nio / netty).
 *
 * **Type safety.** The `T` parameter is enforced at [install] / [current]
 * call sites by the Kotlin compiler, but a single instance must always be
 * given values of one type — the underlying `dispatch_queue_specific`
 * machinery loses static type information. Installing values of two
 * different concrete types into the same [DispatchQueueLocal] is a
 * programming error caught only by `ClassCastException` at [current].
 *
 * **Thread-safety.** [install] and [current] are safe to call from any
 * thread. GCD's documented `dispatch_queue_specific` semantics are
 * memory-coherent across writes/reads — installing on one queue is
 * visible to subsequent lookups via [dispatch_get_specific] regardless
 * of which pthread observes them.
 *
 * @param T the value type bound per queue. Must be a non-null reference
 *   type so it can be wrapped in a [StableRef].
 * @param fallback returns the value used by [current] when the caller is
 *   not currently executing on a queue that had [install] called for this
 *   [DispatchQueueLocal]. Typically a process-wide singleton, a
 *   `@ThreadLocal`-backed accessor, or — rarely — a thrown error if
 *   off-queue access is a contract violation.
 */
@OptIn(ExperimentalForeignApi::class)
class DispatchQueueLocal<T : Any>(
    private val fallback: () -> T,
) {

    /**
     * Per-instance unique key pointer used as `dispatch_queue_specific_key`.
     *
     * Identity-only — the [Any] target is a placeholder used purely so its
     * heap pointer becomes a distinct `void*` key. The [StableRef] is
     * intentionally never disposed; one [DispatchQueueLocal] instance
     * typically lives for the process lifetime, and the key is freed at
     * process exit alongside everything else.
     */
    private val keyRef: StableRef<Any> = StableRef.create(Any())
    private val key: COpaquePointer = keyRef.asCPointer()

    /**
     * Installs [value] as this [DispatchQueueLocal]'s scoped value for
     * [queue]. After this call, any [current] invocation made from a block
     * executing on [queue] returns [value]; the value remains until [queue]
     * is released by its last owner, at which point GCD invokes the
     * registered destructor to dispose the backing [StableRef] (so the
     * value becomes eligible for GC).
     *
     * **Idempotency.** Calling [install] twice on the same queue replaces
     * the previous value — GCD's `dispatch_queue_set_specific` runs the
     * old key's destructor before binding the new pointer.
     *
     * **Lifetime contract.** [value] should be a fresh per-queue instance
     * (e.g. a freshly-constructed pool stack). Sharing one [value]
     * across multiple queues defeats the per-queue isolation and is
     * equivalent to using the [fallback].
     */
    fun install(queue: dispatch_queue_t, value: T) {
        val ref = StableRef.create(value)
        dispatch_queue_set_specific(queue, key, ref.asCPointer(), DESTRUCTOR)
    }

    /**
     * Returns this [DispatchQueueLocal]'s value for the dispatch queue
     * currently executing this code, or — if not on a queue that had
     * [install] called — the result of [fallback].
     *
     * The lookup is a single `dispatch_get_specific(key)` call plus, on
     * a hit, an `asStableRef` dereference. On a miss it falls through to
     * [fallback]; performance therefore depends on the supplied lambda.
     */
    @Suppress("UNCHECKED_CAST")
    fun current(): T {
        val specific = dispatch_get_specific(key)
        if (specific != null) {
            return specific.asStableRef<Any>().get() as T
        }
        return fallback()
    }

    /**
     * Returns `true` iff the current execution context is a GCD block on
     * a queue that had [install] called for this [DispatchQueueLocal].
     * Useful for diagnostics, assertions, or hot-path fast-checks that
     * want to avoid the [fallback] cost entirely.
     */
    fun isScopedHere(): Boolean = dispatch_get_specific(key) != null

    private companion object {
        /**
         * Destructor invoked by GCD when a queue with a value bound to a
         * `DispatchQueueLocal` key is finally released. Disposes the
         * [StableRef] backing the value so the K/N GC can reclaim the
         * held object.
         *
         * Must be a [staticCFunction] (no captures) — GCD invokes this
         * from C with the key's destructor signature.
         */
        @OptIn(ExperimentalForeignApi::class)
        private val DESTRUCTOR = staticCFunction<COpaquePointer?, Unit> { ptr ->
            if (ptr != null) {
                ptr.asStableRef<Any>().dispose()
            }
        }
    }
}
