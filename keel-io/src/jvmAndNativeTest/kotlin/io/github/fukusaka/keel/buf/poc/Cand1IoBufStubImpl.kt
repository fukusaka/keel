package io.github.fukusaka.keel.buf.poc

import io.github.fukusaka.keel.buf.Segment
import io.github.fukusaka.keel.buf.poc.cand1.Cand1IoBuf
import io.github.fukusaka.keel.buf.poc.cand1.SegmentRangeAction

/**
 * Trivial second implementation of [Cand1IoBuf]. Exists only to
 * force `Cand1IoBuf` call sites to be megamorphic during whole-program
 * LTO so the bench can measure the **worst-case** dispatch cost when
 * Kotlin/Native AOT cannot devirtualise the SAM-lambda callback.
 *
 * Does nothing useful — `forEachReadableSegment` is a no-op, every
 * other method returns a zero / `this` / `false`. The bench must
 * never call this impl's methods for actual work; it only uses it to
 * pollute the call site's type set.
 */
internal class Cand1IoBufStubImpl : Cand1IoBuf {
    override val capacity: Int = 0
    override val maxCapacity: Int = Int.MAX_VALUE
    override var readerIndex: Int = 0
    override var writerIndex: Int = 0
    override val readableBytes: Int = 0
    override val writableBytes: Int = 0

    override fun writeByte(value: Byte) {}
    override fun writeByteArray(src: ByteArray, offset: Int, length: Int) {}
    override fun writeAscii(src: String, srcOffset: Int, length: Int) {}
    override fun readByte(): Byte = 0
    override fun readByteArray(dest: ByteArray, offset: Int, length: Int) {}
    override fun getByte(index: Int): Byte = 0
    override fun copyTo(dest: Cand1IoBuf, length: Int) {}
    override fun clear() {}
    override fun retain(): Cand1IoBuf = this
    override fun close() {}
    override fun release(): Boolean = false
    override fun appendSegment(seg: Segment) {}
    override fun forEachReadableSegment(action: SegmentRangeAction) {}
    override fun forEachWritableSegment(action: SegmentRangeAction) {}
}
