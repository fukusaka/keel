package io.github.fukusaka.keel.buf

/**
 * Public marker interface for an opaque handle to a fixed-size raw
 * memory region.
 *
 * Exists so that downstream modules (e.g. engine integrations that
 * need to build scatter-gather iovecs over a multi-segment buffer)
 * can name "the underlying memory carrier" type in a `public` API
 * surface without keel-io's existing `internal RawSegmentBacking`
 * leaking through.
 *
 * **Provisional**: introduced for the multi-segment IoBuf PoC
 * (`buf.poc.cand1` / `buf.poc.cand2`). The longer-term direction is
 * to consolidate `Segment` and `RawSegmentBacking` into a single
 * public memory carrier; this marker is the minimum surface change
 * needed to land the PoC without that broader cleanup.
 *
 * Engines obtain platform memory by down-casting to the concrete
 * platform impl (`NativeHeapBacking` on Native, the JVM
 * `ByteBuffer`-backed impl, etc.) — the marker itself exposes no
 * accessor so the surface stays minimal.
 */
interface SegmentBacking
