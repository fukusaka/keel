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

internal actual class PlatformLock actual constructor() {

    @kotlin.concurrent.Volatile
    private var closed: Boolean = false

    private val mutex = nativeHeap.alloc<pthread_mutex_t>().also {
        // `pthread_mutex_init` returns 0 on success, the errno code (EAGAIN /
        // ENOMEM / EBUSY / EINVAL / EPERM per POSIX) on failure. Fail fast on
        // any non-zero return — never silently fall through to a partially-
        // initialised mutex, which would later produce undefined behaviour on
        // lock / unlock. Reported as a numeric errno (not via `errnoMessage`)
        // because keel-io is a lower-level module than keel-native-posix and
        // cannot depend on it without a cycle.
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
        // POSIX: destroying an initialised mutex with no waiters returns 0.
        // A non-zero return indicates programmer error (the mutex is still
        // locked or has waiters); surface it so a teardown-race regression
        // does not silently leak the mutex slot.
        val rc = pthread_mutex_destroy(mutex.ptr)
        check(rc == 0) { "pthread_mutex_destroy() failed with errno=$rc" }
        nativeHeap.free(mutex.rawPtr)
    }
}
