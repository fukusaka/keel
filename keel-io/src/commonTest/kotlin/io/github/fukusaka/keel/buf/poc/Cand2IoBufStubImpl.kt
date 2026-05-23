package io.github.fukusaka.keel.buf.poc

import io.github.fukusaka.keel.buf.Segment
import io.github.fukusaka.keel.buf.poc.cand2.Cand2IoBuf
import io.github.fukusaka.keel.buf.poc.cand2.SegmentRangeList

/**
 * Stub second [Cand2IoBuf] impl — see [Cand1IoBufStubImpl] for
 * rationale (forcing megamorphic call sites under whole-program LTO).
 */
internal class Cand2IoBufStubImpl : Cand2IoBuf {
    override val capacity: Int = 0
    override val maxCapacity: Int = Int.MAX_VALUE
    override var readerIndex: Int = 0
    override var writerIndex: Int = 0
    override val readableBytes: Int = 0
    override val writableBytes: Int = 0

    private val empty: SegmentRangeList = object : SegmentRangeList {
        override val size: Int = 0
        override fun get(index: Int): Nothing =
            throw IndexOutOfBoundsException("empty stub list")
    }

    override fun writeByte(value: Byte) {}
    override fun writeByteArray(src: ByteArray, offset: Int, length: Int) {}
    override fun writeAscii(src: String, srcOffset: Int, length: Int) {}
    override fun readByte(): Byte = 0
    override fun readByteArray(dest: ByteArray, offset: Int, length: Int) {}
    override fun getByte(index: Int): Byte = 0
    override fun copyTo(dest: Cand2IoBuf, length: Int) {}
    override fun clear() {}
    override fun retain(): Cand2IoBuf = this
    override fun close() {}
    override fun release(): Boolean = false
    override fun appendSegment(seg: Segment) {}
    override fun readableSegments(): SegmentRangeList = empty
    override fun writableSegments(): SegmentRangeList = empty
}
