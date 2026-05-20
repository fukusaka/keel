package io.github.fukusaka.keel.buf

import java.nio.ByteBuffer

/**
 * JVM [RawSegmentBacking] over a direct [ByteBuffer].
 *
 * Backs every JVM-side allocation path: [DirectIoBuf] allocator-allocated
 * primary views, [DirectIoBuf.wrapExternal] callers, and pooled
 * allocators ([PooledDirectAllocator]). The direct [ByteBuffer] is
 * reclaimed by the JVM's Cleaner regardless of provenance, so [free] is
 * a single no-op — there is no allocator-vs-external split on JVM
 * (compare with the Native side's [NativeHeapBacking] /
 * [ExternalNativeBacking]).
 *
 * @property base The direct [ByteBuffer] holding the raw memory region.
 */
internal class DirectByteBufferBacking(
    val base: ByteBuffer,
) : RawSegmentBacking {
    /** No-op: the direct [ByteBuffer] is GC-reclaimed by the Cleaner. */
    override fun free() {
        // ByteBuffer is GC-managed; nothing to free.
    }
}
