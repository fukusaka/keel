package io.github.fukusaka.keel.buf

import java.nio.ByteBuffer

/**
 * JVM [RawSegmentBacking] over a direct [ByteBuffer].
 *
 * The direct [ByteBuffer] is reclaimed by the JVM's Cleaner, so [free]
 * is a no-op regardless of whether the backing was allocated by the
 * allocator (`ByteBuffer.allocateDirect`) or wraps an externally-supplied
 * buffer.
 *
 * @property base The direct [ByteBuffer] holding the raw memory region.
 */
internal actual class RawSegmentBacking(
    val base: ByteBuffer,
) {
    /** No-op: the direct [ByteBuffer] is GC-reclaimed. */
    actual fun free() {
        // ByteBuffer is GC-managed; nothing to free.
    }
}
