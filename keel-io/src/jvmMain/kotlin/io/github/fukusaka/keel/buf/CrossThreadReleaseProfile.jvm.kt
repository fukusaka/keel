package io.github.fukusaka.keel.buf

import java.util.concurrent.ConcurrentHashMap

internal actual fun currentThreadId(): Long = Thread.currentThread().threadId()

/**
 * JVM [XthreadMap] backed by a [ConcurrentHashMap]. [IoBuf] has no
 * `equals`/`hashCode` override, so the map keys on reference identity.
 */
internal actual class XthreadMap {
    private val map = ConcurrentHashMap<IoBuf, Long>()

    actual fun put(buf: IoBuf, threadId: Long) {
        map[buf] = threadId
    }

    actual fun remove(buf: IoBuf): Long =
        map.remove(buf) ?: CrossThreadReleaseProfile.NO_ALLOC_THREAD

    actual val size: Int get() = map.size
}
