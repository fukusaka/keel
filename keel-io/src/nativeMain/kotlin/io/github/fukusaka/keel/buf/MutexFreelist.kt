@file:OptIn(UnsafeIoBufApi::class, ExperimentalForeignApi::class)

package io.github.fukusaka.keel.buf

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.alloc
import kotlinx.cinterop.ptr
import platform.posix.pthread_mutex_destroy
import platform.posix.pthread_mutex_init
import platform.posix.pthread_mutex_lock
import platform.posix.pthread_mutex_t
import platform.posix.pthread_mutex_unlock

/**
 * Native [Freelist] backed by an `ArrayDeque` guarded by a blocking
 * `pthread_mutex`.
 *
 * **When to choose this**: an *arbitrary-concurrency* allocator instance — one
 * that may receive concurrent pop/push from threads not pinned to a single
 * `EventLoop`. Compared to the default `SpinLockFreelist`:
 * - Uncontended cost is higher (about 2× the spin lock; `pthread_mutex_lock`
 *   takes its own CAS fast-path, plus parking infrastructure).
 * - Under genuine contention the mutex *parks* waiters instead of busy-waiting,
 *   so it scales flat and avoids the userspace-spinlock preemption pathology
 *   (a preempted lock holder no longer burns waiters' CPU).
 *
 * See `benchmark --bench=freelist-variants` / `--bench=freelist-contended`
 * for the numbers; the keel default (EL-pinned engines) keeps the spin lock
 * because uncontended is the dominant regime, but a public allocator that
 * promises arbitrary-thread safety should pick this implementation.
 *
 * **ABA safety**: structural — there is no CAS, so the classic Treiber-stack
 * ABA hazard does not apply.
 *
 * ## Lifecycle
 *
 * The `pthread_mutex_t` is allocated from `nativeHeap` at construction and
 * released on [close] (`pthread_mutex_destroy` + `nativeHeap.free`).
 * [PooledAllocator.close] invokes [close] after draining pooled buffers so
 * the destroy lands when the mutex is guaranteed quiescent. After [close]
 * the freelist must not be used.
 */
class MutexFreelist(private val maxSlots: Int) : Freelist {
    private val list = ArrayDeque<IoBuf>(maxSlots)

    @kotlin.concurrent.Volatile
    private var closed: Boolean = false

    private val mutex = nativeHeap.alloc<pthread_mutex_t>().also {
        // `pthread_mutex_init` returns 0 on success; non-zero is the errno code
        // (EAGAIN / ENOMEM / EBUSY / EINVAL / EPERM per POSIX). Fail fast on any
        // non-zero return — never silently fall through to a partially-initialised
        // mutex, which would later produce undefined behaviour on lock/unlock.
        // Reported as a numeric errno (not via `errnoMessage`) because keel-io is
        // a lower-level module than keel-native-posix and cannot depend on it
        // without a cycle.
        val rc = pthread_mutex_init(it.ptr, null)
        check(rc == 0) { "pthread_mutex_init() failed with errno=$rc" }
    }

    private inline fun <T> withMutex(block: () -> T): T {
        // `pthread_mutex_lock` / `_unlock` return 0 on success; non-zero is the
        // errno code. We check both ends per the "every syscall return value is
        // checked" rule: with the default mutex attribute these can only fail
        // when the mutex is misused (uninitialised / cross-thread unlock under
        // ERRORCHECK / recursive overflow), which would silently leave the
        // allocator in a corrupt state if we ignored the return value.
        //
        // unlock-in-finally tries to preserve any in-flight exception: if
        // `block()` threw, the unlock failure is attached via `addSuppressed`
        // so the original cause is not masked. If `block()` did not throw,
        // the unlock failure is propagated directly.
        val lockRc = pthread_mutex_lock(mutex.ptr)
        check(lockRc == 0) { "pthread_mutex_lock() failed with errno=$lockRc" }
        var primary: Throwable? = null
        try {
            return block()
        } catch (t: Throwable) {
            primary = t
            throw t
        } finally {
            val unlockRc = pthread_mutex_unlock(mutex.ptr)
            if (unlockRc != 0) {
                val unlockError = IllegalStateException(
                    "pthread_mutex_unlock() failed with errno=$unlockRc",
                )
                if (primary != null) {
                    primary.addSuppressed(unlockError)
                } else {
                    throw unlockError
                }
            }
        }
    }

    override fun push(buf: IoBuf): Boolean = withMutex {
        if (list.size < maxSlots) {
            list.addLast(buf)
            true
        } else {
            false
        }
    }

    override fun pop(): IoBuf? = withMutex {
        if (list.isEmpty()) null else list.removeLast()
    }

    override fun snapshotInto(out: MutableList<IoBuf>) {
        withMutex { out.addAll(list) }
    }

    /**
     * Destroys the underlying `pthread_mutex_t` and frees its native-heap
     * slot. Idempotent — a second call is a no-op.
     *
     * The caller (typically [PooledAllocator.close]) must ensure no other
     * thread is mid-lock when this runs. After [close] all freelist
     * methods are undefined; the allocator's closed-flag guard prevents
     * them from being reached on the documented teardown path.
     */
    override fun close() {
        if (closed) return
        closed = true
        // POSIX: destroying an initialised mutex with no waiters returns 0.
        // A non-zero return indicates programmer error (the mutex is still
        // locked or has waiters); surface it so a teardown-race regression
        // does not silently leak the mutex slot.
        val rc = pthread_mutex_destroy(mutex.ptr)
        check(rc == 0) { "pthread_mutex_destroy() failed with errno=$rc" }
        nativeHeap.free(mutex.rawPtr)
    }
}
