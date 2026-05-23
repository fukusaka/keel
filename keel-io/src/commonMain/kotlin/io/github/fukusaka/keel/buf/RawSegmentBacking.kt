package io.github.fukusaka.keel.buf

/**
 * An opaque handle to a fixed-size raw memory region.
 *
 * [RawSegmentBacking] is the lowest layer of keel's buffer stack — it
 * abstracts over the platform-native memory carrier (a `CPointer<ByteVar>`
 * on Native, a direct [java.nio.ByteBuffer] on JVM, an `Int8Array` on
 * JS) and knows how to free that memory. Everything above it ([Segment],
 * the platform [IoBuf] views) is a thin layer that reads the platform
 * memory out of a backing and never owns the teardown itself.
 *
 * **Provenance-as-type**: each provenance is a distinct implementation
 * (heap-allocated, externally-wrapped, future shared-memory etc.) and
 * carries its own free strategy in [free]. There is no boolean flag for
 * ownership — `NativeHeapBacking` frees its `nativeHeap` allocation,
 * `ExternalNativeBacking` no-ops because the external owner reclaims its
 * memory, JVM / JS backings no-op because the GC reclaims them.
 *
 * **Teardown routing**: raw-memory release must always route through
 * [free] — no other code path may hardcode a platform free routine
 * (`nativeHeap.free`, …).
 *
 * **Thread safety**: a backing is owned by the single EventLoop thread
 * that owns the [IoBuf] view over it; [free] is non-atomic and must run
 * on that thread.
 */
internal interface RawSegmentBacking : SegmentBacking {
    /**
     * Releases the raw memory region.
     *
     * Idempotency and ownership: callers (the [Segment] / [IoBuf] view)
     * must invoke this at most once for an owned backing. For a backing
     * over externally-owned memory this is a no-op.
     */
    fun free()
}
