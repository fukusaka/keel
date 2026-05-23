package io.github.fukusaka.keel.buf.poc

import io.github.fukusaka.keel.buf.DirectByteBufferBacking
import io.github.fukusaka.keel.buf.DirectIoBuf
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.Segment

internal actual fun extractSegment(buf: IoBuf): Segment =
    (buf as DirectIoBuf).segment

internal actual fun segmentGetByte(seg: Segment, offset: Int): Byte =
    (seg.backing as DirectByteBufferBacking).base.get(offset)

internal actual fun segmentPutByte(seg: Segment, offset: Int, value: Byte) {
    (seg.backing as DirectByteBufferBacking).base.put(offset, value)
}

internal actual fun segmentGetBytes(
    seg: Segment,
    srcOffset: Int,
    dest: ByteArray,
    destOffset: Int,
    length: Int,
) {
    val src = (seg.backing as DirectByteBufferBacking).base
    val dup = src.duplicate()
    dup.position(srcOffset)
    dup.get(dest, destOffset, length)
}

internal actual fun segmentPutBytes(
    seg: Segment,
    destOffset: Int,
    src: ByteArray,
    srcOffset: Int,
    length: Int,
) {
    val dest = (seg.backing as DirectByteBufferBacking).base
    val dup = dest.duplicate()
    dup.position(destOffset)
    dup.put(src, srcOffset, length)
}
