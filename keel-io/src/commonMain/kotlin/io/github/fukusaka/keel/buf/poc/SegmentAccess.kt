package io.github.fukusaka.keel.buf.poc

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.Segment

/**
 * Internal shim that lets PoC multi-segment `IoBuf` implementations
 * (`buf.poc.cand1.*` / `buf.poc.cand2.*`) operate directly against
 * [Segment]s without going through the existing single-segment `IoBuf`
 * view's reader / writer state machine.
 *
 * **Why a shim**: keel-io's [Segment] is `internal` and platform
 * backings (`DirectByteBufferBacking` on JVM, `NativeBacking` on Native)
 * are likewise `internal`, so the PoC can — and must — stay within
 * keel-io to reach them. Each platform's `actual` casts the backing to
 * its concrete impl and calls the platform-native byte-level accessor
 * (`ByteBuffer.get` / `Cpointer.get`) directly. There is no virtual
 * dispatch through the `IoBuf` interface, which is the point — the PoC
 * measures the multi-segment `IoBuf` design without inheriting the
 * existing single-seg view's call shape.
 */

/**
 * Extracts the underlying [Segment] from a freshly-allocated [IoBuf]
 * primary view (`BufferAllocator.allocate(N)`). The PoC uses this to
 * obtain pool-managed segments without adding a new public allocator
 * API; the IoBuf wrapper is discarded after extraction and the segment
 * lives independently under PoC ownership (refCount stays 1 from
 * allocation).
 */
internal expect fun extractSegment(buf: IoBuf): Segment

/** Reads the byte at the given absolute [offset] within [seg]'s backing. */
internal expect fun segmentGetByte(seg: Segment, offset: Int): Byte

/** Writes [value] at the given absolute [offset] within [seg]'s backing. */
internal expect fun segmentPutByte(seg: Segment, offset: Int, value: Byte)

/**
 * Bulk read: copies [length] bytes from [seg]'s backing starting at
 * [srcOffset] into [dest] starting at [destOffset].
 */
internal expect fun segmentGetBytes(
    seg: Segment,
    srcOffset: Int,
    dest: ByteArray,
    destOffset: Int,
    length: Int,
)

/**
 * Bulk write: copies [length] bytes from [src] starting at [srcOffset]
 * into [seg]'s backing starting at [destOffset].
 */
internal expect fun segmentPutBytes(
    seg: Segment,
    destOffset: Int,
    src: ByteArray,
    srcOffset: Int,
    length: Int,
)
