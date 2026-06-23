package io.github.fukusaka.keel.buf

/**
 * Native [ArenaLock] backed by a [NativeMutex] (`pthread_mutex`).
 *
 * The mutex lifecycle — `nativeHeap` alloc / `pthread_mutex_init` / lock / unlock
 * / `pthread_mutex_destroy` + `nativeHeap.free`, all with checked return codes —
 * lives in [NativeMutex], shared with [MutexFreelist] so the contract has a single
 * implementation.
 */
internal actual class ArenaLock actual constructor() {
    private val mutex = NativeMutex()

    actual fun lock() = mutex.lock()

    actual fun unlock() = mutex.unlock()

    actual fun close() = mutex.close()
}
