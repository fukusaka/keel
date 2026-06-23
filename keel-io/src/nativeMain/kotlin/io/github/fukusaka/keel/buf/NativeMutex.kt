@file:OptIn(ExperimentalForeignApi::class)

package io.github.fukusaka.keel.buf

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import platform.posix.pthread_mutex_destroy
import platform.posix.pthread_mutex_init
import platform.posix.pthread_mutex_lock
import platform.posix.pthread_mutex_t
import platform.posix.pthread_mutex_unlock

/**
 * A blocking `pthread_mutex` with a checked lifecycle, shared by every keel-io
 * Native component that needs one ([ArenaLock]'s Native actual, [MutexFreelist]).
 *
 * The `pthread_mutex_t` is allocated from `nativeHeap` at construction and
 * released on [close] (`pthread_mutex_destroy` + `nativeHeap.free`).
 *
 * **Thread safety**: [lock] / [unlock] / [withLock] are the standard
 * mutual-exclusion contract and are safe to call concurrently. [close] is **not**
 * synchronised against them — the owner must ensure no other thread is mid-lock
 * when [close] runs (destroying a locked mutex is undefined / leaks the slot).
 * keel's allocators satisfy this by draining and closing on the single-threaded
 * teardown path. After [close] the mutex must not be used.
 *
 * **Return codes** are checked per the "every syscall return value is checked"
 * rule. With the default mutex attribute `pthread_mutex_lock` / `_unlock` can
 * only fail on misuse (uninitialised / cross-thread unlock under ERRORCHECK /
 * recursive overflow), which would silently corrupt the guarded state if ignored.
 * The numeric errno is reported directly (not via `errnoMessage`) because keel-io
 * is a lower-level module than keel-native-posix and cannot depend on it without
 * a cycle.
 */
internal class NativeMutex {
    @kotlin.concurrent.Volatile
    private var closed: Boolean = false

    // @PublishedApi so the inline [withLock] can reach it after expansion at the
    // call site; treated as private otherwise.
    @PublishedApi
    internal val handle = nativeHeap.alloc<pthread_mutex_t>().also {
        val rc = pthread_mutex_init(it.ptr, null)
        check(rc == 0) { "pthread_mutex_init() failed with errno=$rc" }
    }

    /** Acquires the mutex (blocking). Pairs with [unlock]. */
    fun lock() {
        val rc = pthread_mutex_lock(handle.ptr)
        check(rc == 0) { "pthread_mutex_lock() failed with errno=$rc" }
    }

    /** Releases the mutex. Pairs with [lock]. */
    fun unlock() {
        val rc = pthread_mutex_unlock(handle.ptr)
        check(rc == 0) { "pthread_mutex_unlock() failed with errno=$rc" }
    }

    /**
     * Runs [block] under the mutex. `inline` so the critical section has no
     * lambda allocation on the hot path (e.g. [MutexFreelist.push] / `pop`).
     *
     * The unlock-in-finally preserves any in-flight exception: if [block] threw,
     * a unlock failure is attached via `addSuppressed` so the original cause is
     * not masked; otherwise the unlock failure propagates directly.
     */
    inline fun <T> withLock(block: () -> T): T {
        val lockRc = pthread_mutex_lock(handle.ptr)
        check(lockRc == 0) { "pthread_mutex_lock() failed with errno=$lockRc" }
        var primary: Throwable? = null
        try {
            return block()
        } catch (t: Throwable) {
            primary = t
            throw t
        } finally {
            val unlockRc = pthread_mutex_unlock(handle.ptr)
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

    /**
     * Destroys the underlying `pthread_mutex_t` and frees its native-heap slot.
     * Idempotent — a second call is a no-op.
     *
     * POSIX: destroying an initialised, unlocked mutex returns 0. A non-zero
     * return means it is still locked or has waiters (programmer error); it is
     * surfaced so a teardown-race regression does not silently leak the slot.
     */
    fun close() {
        if (closed) return
        closed = true
        val rc = pthread_mutex_destroy(handle.ptr)
        check(rc == 0) { "pthread_mutex_destroy() failed with errno=$rc" }
        nativeHeap.free(handle.rawPtr)
    }
}
