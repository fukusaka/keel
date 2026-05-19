package io.github.fukusaka.keel.buf

/**
 * A pluggable seam for acquiring fixed-size raw memory regions.
 *
 * A [RawMemorySource] is constructed for a single fixed segment size and
 * hands out [RawSegmentBacking] handles of exactly that size — there is
 * no size parameter on [acquire]. Allocators ([SlabAllocator],
 * `PooledDirectAllocator`) obtain raw memory through a source instead of
 * calling a platform routine (`nativeHeap.allocArray`,
 * `ByteBuffer.allocateDirect`) directly, so the raw-memory acquisition
 * strategy is replaceable in later phases (chunk allocator).
 *
 * The trivial per-platform implementation simply forwards to the
 * platform allocation routine; [release] forwards to
 * [RawSegmentBacking.free] (a real free on Native, a no-op on JVM/JS).
 *
 * **Phase 1 note**: internal scaffolding, behaviour-neutral.
 */
internal interface RawMemorySource {
    /**
     * Acquires a raw memory region of this source's fixed size.
     *
     * @return a [RawSegmentBacking] owning a freshly-acquired region.
     */
    fun acquire(): RawSegmentBacking

    /**
     * Releases a backing previously obtained from [acquire].
     *
     * @param backing the backing to release.
     */
    fun release(backing: RawSegmentBacking)
}
