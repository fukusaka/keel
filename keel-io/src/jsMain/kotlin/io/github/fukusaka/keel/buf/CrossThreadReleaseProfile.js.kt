package io.github.fukusaka.keel.buf

/**
 * JS runs in a single execution context, so every release is same-thread; the id
 * is a constant and no release is ever cross-thread.
 */
internal actual fun currentThreadId(): Long = 0L

/**
 * JS [XthreadMap] backed by a plain [HashMap] — single-threaded, no lock needed
 * (same rationale as `ArenaLock`'s JS no-op). [IoBuf] has no `equals`/`hashCode`
 * override, so the map keys on reference identity.
 */
internal actual class XthreadMap {
    private val map = HashMap<IoBuf, Long>()

    actual fun put(buf: IoBuf, threadId: Long) {
        map[buf] = threadId
    }

    actual fun remove(buf: IoBuf): Long =
        map.remove(buf) ?: CrossThreadReleaseProfile.NO_ALLOC_THREAD

    actual val size: Int get() = map.size
}
