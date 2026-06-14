package io.github.fukusaka.keel.buf

/**
 * Platform-abstracted mutex used by [PooledAllocator] for thread-safety
 * across the chunk-arena / subpage layer.
 *
 * **Implementation**:
 * - **JVM**: `java.util.concurrent.locks.ReentrantLock` — GC-managed, no
 *   explicit destroy needed; [close] is a no-op.
 * - **Native**: `pthread_mutex_t` in `nativeHeap` — [close] runs
 *   `pthread_mutex_destroy` and frees the native slot.
 * - **JS**: no-op — JS engines run single-threaded (no shared-memory races
 *   without explicit worker bridges) so the lock is structurally
 *   unnecessary; the expect/actual contract is preserved by a stub that
 *   simply returns.
 *
 * The mutex is **non-reentrant by contract** for predictable performance and
 * to match the established intrusive-lock idiom of pool back-ends (Netty's
 * `PoolArena.lock` is also non-reentrant; reentrant acquire patterns are a
 * correctness smell on a layer that needs strict acquire ordering between
 * the arena lock and per-size-class subpage locks). The JVM actual uses
 * `ReentrantLock` only because that is what `java.util.concurrent` exposes
 * with the desired blocking semantics — callers must not rely on the
 * `tryLock` reentrancy property.
 *
 * **Thread safety**: every method is thread-safe; the whole point of the
 * type is to enable concurrent access through it.
 *
 * Mark `internal` because the lock is an implementation detail of
 * [PooledAllocator]; making it `public` would expose the choice of mutex
 * primitive to API consumers, which we explicitly want to keep mutable.
 */
internal expect class PlatformLock() {
    fun lock()
    fun unlock()
    fun close()
}

/**
 * Runs [block] under [this] lock, releasing it on both normal completion and
 * exceptional exit. Pattern mirrors `kotlin.concurrent.withLock` on the JVM
 * `Lock` interface, kept inline so the lambda is statically dispatched on the
 * hot path without an indirect call.
 */
internal inline fun <T> PlatformLock.withLock(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}
