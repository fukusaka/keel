package io.github.fukusaka.keel.buf.poc

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.NativeBacking
import io.github.fukusaka.keel.buf.NativeIoBuf
import io.github.fukusaka.keel.buf.Segment
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.get
import kotlinx.cinterop.plus
import kotlinx.cinterop.set
import kotlinx.cinterop.usePinned
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class)
internal actual fun extractSegment(buf: IoBuf): Segment =
    (buf as NativeIoBuf).segment

@OptIn(ExperimentalForeignApi::class)
internal actual fun segmentGetByte(seg: Segment, offset: Int): Byte =
    (seg.backing as NativeBacking).base[offset]

@OptIn(ExperimentalForeignApi::class)
internal actual fun segmentPutByte(seg: Segment, offset: Int, value: Byte) {
    (seg.backing as NativeBacking).base[offset] = value
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun segmentGetBytes(
    seg: Segment,
    srcOffset: Int,
    dest: ByteArray,
    destOffset: Int,
    length: Int,
) {
    if (length == 0) return
    val src = (seg.backing as NativeBacking).base
    dest.usePinned { pinned ->
        memcpy(pinned.addressOf(destOffset), src + srcOffset, length.toULong())
    }
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun segmentPutBytes(
    seg: Segment,
    destOffset: Int,
    src: ByteArray,
    srcOffset: Int,
    length: Int,
) {
    if (length == 0) return
    val dest = (seg.backing as NativeBacking).base
    src.usePinned { pinned ->
        memcpy(dest + destOffset, pinned.addressOf(srcOffset), length.toULong())
    }
}
