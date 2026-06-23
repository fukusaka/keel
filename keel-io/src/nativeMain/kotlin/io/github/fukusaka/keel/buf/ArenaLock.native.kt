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
 * Native [ArenaLock] backed by a blocking `pthread_mutex`. Mirrors the lifecycle
 * of [MutexFreelist]'s mutex: allocated from `nativeHeap` at construction,
 * released on [close] (`pthread_mutex_destroy` + `nativeHeap.free`).
 *
 * Return codes are checked per the "every syscall return value is checked" rule.
 * With the default mutex attribute, `pthread_mutex_lock` / `_unlock` can only fail
 * on misuse (uninitialised / cross-thread unlock under ERRORCHECK), which would
 * silently corrupt the arena state if ignored. The numeric errno is reported
 * directly (not via `errnoMessage`) because keel-io is a lower-level module than
 * keel-native-posix and cannot depend on it without a cycle — the same convention
 * [MutexFreelist] uses.
 */
internal actual class ArenaLock actual constructor() {
    @kotlin.concurrent.Volatile
    private var closed: Boolean = false

    private val mutex = nativeHeap.alloc<pthread_mutex_t>().also {
        val rc = pthread_mutex_init(it.ptr, null)
        check(rc == 0) { "pthread_mutex_init() failed with errno=$rc" }
    }

    actual fun lock() {
        val rc = pthread_mutex_lock(mutex.ptr)
        check(rc == 0) { "pthread_mutex_lock() failed with errno=$rc" }
    }

    actual fun unlock() {
        val rc = pthread_mutex_unlock(mutex.ptr)
        check(rc == 0) { "pthread_mutex_unlock() failed with errno=$rc" }
    }

    actual fun close() {
        if (closed) return
        closed = true
        // POSIX: destroying an initialised, unlocked mutex returns 0. A non-zero
        // return means the mutex is still locked or has waiters (programmer error);
        // surface it so a teardown-race regression does not silently leak the slot.
        val rc = pthread_mutex_destroy(mutex.ptr)
        check(rc == 0) { "pthread_mutex_destroy() failed with errno=$rc" }
        nativeHeap.free(mutex.rawPtr)
    }
}
