package io.github.fukusaka.keel.buf

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.nativeHeap

/**
 * Native [RawMemorySource] backed by [nativeHeap].
 *
 * [acquire] allocates a `nativeHeap` region of [size] bytes wrapped in an
 * owning [RawSegmentBacking]; [release] frees it via
 * [RawSegmentBacking.free].
 *
 * @param size Fixed segment size in bytes for every [acquire] call.
 */
@OptIn(ExperimentalForeignApi::class)
internal class NativeRawMemorySource(
    private val size: Int,
) : RawMemorySource {

    override fun acquire(): RawSegmentBacking =
        RawSegmentBacking(nativeHeap.allocArray<ByteVar>(size), ownsMemory = true)

    override fun release(backing: RawSegmentBacking) {
        backing.free()
    }
}
