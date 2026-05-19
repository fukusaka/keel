package io.github.fukusaka.keel.buf

import org.khronos.webgl.Int8Array

/**
 * JS [RawMemorySource] backed by [Int8Array].
 *
 * [acquire] allocates an [Int8Array] of [size] bytes wrapped in a
 * [RawSegmentBacking]; [release] is a no-op because V8's GC reclaims the
 * backing array.
 *
 * @param size Fixed segment size in bytes for every [acquire] call.
 */
internal class JsRawMemorySource(
    private val size: Int,
) : RawMemorySource {

    override fun acquire(): RawSegmentBacking =
        RawSegmentBacking(Int8Array(size))

    override fun release(backing: RawSegmentBacking) {
        backing.free()
    }
}
