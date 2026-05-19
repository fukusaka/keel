package io.github.fukusaka.keel.buf

import java.nio.ByteBuffer

/**
 * JVM [RawMemorySource] backed by [ByteBuffer.allocateDirect].
 *
 * [acquire] allocates a direct [ByteBuffer] of [size] bytes wrapped in a
 * [RawSegmentBacking]; [release] is a no-op because the direct buffer is
 * GC-reclaimed.
 *
 * @param size Fixed segment size in bytes for every [acquire] call.
 */
internal class JvmRawMemorySource(
    private val size: Int,
) : RawMemorySource {

    override fun acquire(): RawSegmentBacking =
        RawSegmentBacking(ByteBuffer.allocateDirect(size))

    override fun release(backing: RawSegmentBacking) {
        backing.free()
    }
}
