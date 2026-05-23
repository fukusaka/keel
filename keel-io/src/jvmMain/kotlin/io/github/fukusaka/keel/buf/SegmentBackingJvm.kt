package io.github.fukusaka.keel.buf

import java.nio.ByteBuffer

/**
 * Returns the direct [ByteBuffer] that backs this [SegmentBacking].
 *
 * Public so downstream engine integrations (currently the multi-seg
 * IoBuf PoC scatter-gather write paths in `engine-nio` /
 * `engine-netty`) can reach the platform memory carrier without
 * casting to keel-io's internal [DirectByteBufferBacking]. The
 * concrete `DirectByteBufferBacking` impl stays `internal`; this
 * extension is the only public hand-off point.
 *
 * **Throws** [ClassCastException] when called on a non-JVM-direct
 * backing — every JVM-side allocation in keel-io produces a
 * [DirectByteBufferBacking], so this only triggers for foreign
 * `SegmentBacking` implementations that should not be reaching JVM
 * code paths.
 */
fun SegmentBacking.asByteBuffer(): ByteBuffer = (this as DirectByteBufferBacking).base
