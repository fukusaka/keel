package io.github.fukusaka.keel.buf

/**
 * Public marker for the platform-native memory carrier behind a [Segment].
 *
 * Engine modules obtain a platform-typed handle via the platform
 * extension functions
 *
 * - JVM: `SegmentBacking.asByteBuffer(): ByteBuffer`
 * - Native: `SegmentBacking.asNativePointer(): CPointer<ByteVar>`
 * - JS: `SegmentBacking.asInt8Array(): Int8Array`
 *
 * The internal teardown contract ([RawSegmentBacking.free]) is *not* part
 * of this surface — engines must never invoke free; the [Segment]'s
 * reference counting governs raw-memory reclamation.
 *
 * **Provenance**: distinct provenances (heap-allocated `nativeHeap`
 * memory, externally-wrapped pointers, direct `ByteBuffer`s, `Int8Array`s,
 * future shared-memory carriers, …) all implement this marker. Engines
 * call only the platform extension and treat the result as the standard
 * platform handle.
 *
 * **Type narrowing**: a future shared-memory carrier on JVM would not be
 * a `DirectByteBufferBacking`, so `asByteBuffer()` could throw on it.
 * Until additional carriers exist, all current `SegmentBacking`s resolve
 * cleanly under their platform extension.
 */
public interface SegmentBacking
