package io.github.fukusaka.keel.buf

/**
 * Native [XthreadMap] backed by an identity [HashMap] guarded by a single
 * [NativeMutex]. [IoBuf] has no `equals`/`hashCode` override, so the map keys on
 * reference identity. The map is touched only at the per-buffer listener fire
 * site (not the per-byte hot path), so a single mutex is sufficient — no
 * lock-free map needed.
 *
 * `currentThreadId` is split into the apple/linux source sets because
 * `pthread_self()` returns an opaque pointer on Apple but a `ULong` on Linux.
 */
internal actual class XthreadMap {
    private val mutex = NativeMutex()
    private val map = HashMap<IoBuf, Long>()

    actual fun put(buf: IoBuf, threadId: Long) {
        mutex.withLock { map[buf] = threadId }
    }

    actual fun remove(buf: IoBuf): Long =
        mutex.withLock { map.remove(buf) ?: CrossThreadReleaseProfile.NO_ALLOC_THREAD }

    actual val size: Int get() = mutex.withLock { map.size }
}
