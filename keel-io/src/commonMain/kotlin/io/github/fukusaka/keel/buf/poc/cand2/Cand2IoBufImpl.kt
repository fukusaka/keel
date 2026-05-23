package io.github.fukusaka.keel.buf.poc.cand2

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.KeelBufferOverflowException
import io.github.fukusaka.keel.buf.Segment
import io.github.fukusaka.keel.buf.poc.extractSegment
import io.github.fukusaka.keel.buf.poc.segmentGetByte
import io.github.fukusaka.keel.buf.poc.segmentGetBytes
import io.github.fukusaka.keel.buf.poc.segmentPutByte
import io.github.fukusaka.keel.buf.poc.segmentPutBytes

/**
 * PoC candidate-2 implementation: multi-segment [Cand2IoBuf] with the
 * same internal layout as the candidate-1 impl, but exposing segment
 * structure via [readableSegments] / [writableSegments] returning a
 * pre-allocated [SegmentRangeList] for zero per-call allocation.
 *
 * Hot-path byte access is identical to candidate 1 — segment field
 * cache for the single-seg fast path; out-param fields
 * ([_locSeg] / [_locOff]) for the slow path's segment walk so no
 * `Pair` is allocated.
 *
 * The candidate-2 specific cost is the [readableSegments] /
 * [writableSegments] iteration: each call rewrites the
 * pre-allocated [SegmentRange] instances inside [readableList] /
 * [writableList] to reflect the current `(readerIndex..writerIndex)`
 * and `(writerIndex..capacity)` ranges respectively. The list grows by
 * appending a fresh [SegmentRange] each time the chain itself grows
 * (i.e. on a tail-segment allocation triggered by writer overflow);
 * the lists are reused across subsequent calls.
 */
// Visibility note: see Cand1IoBufImpl — public for the PoC cross-module
// bench, removed with the rest of `buf.poc.*` once the candidate
// decision lands.
public class Cand2IoBufImpl(
    private val allocator: BufferAllocator,
    private val segmentCapacity: Int,
    override val maxCapacity: Int,
) : Cand2IoBuf {

    init {
        require(maxCapacity >= segmentCapacity) {
            "maxCapacity ($maxCapacity) must be >= segmentCapacity ($segmentCapacity)"
        }
    }

    private val segments: ArrayList<Segment> = ArrayList(2)
    private var primary: Segment

    private var _readerIndex: Int = 0
    private var _writerIndex: Int = 0

    private var _locSeg: Segment
    private var _locOff: Int = 0

    // Pre-allocated SegmentRange instances backing readable / writable
    // SegmentRangeList views. Each list grows alongside [segments];
    // sizes match `segments.size` at all times.
    private val readableRanges: ArrayList<SegmentRange> = ArrayList(2)
    private val writableRanges: ArrayList<SegmentRange> = ArrayList(2)

    /**
     * Mutable size shared by both lists' views; the field is set each
     * time [readableSegments] / [writableSegments] runs and reflects how
     * many of the pre-allocated [SegmentRange]s carry meaningful data
     * for the requested view (chain segments contribute either to the
     * readable view or the writable view, possibly both partially).
     */
    private var readableListSize: Int = 0
    private var writableListSize: Int = 0

    private val readableList: SegmentRangeList = object : SegmentRangeList {
        override val size: Int get() = readableListSize
        override fun get(index: Int): SegmentRange = readableRanges[index]
    }

    private val writableList: SegmentRangeList = object : SegmentRangeList {
        override val size: Int get() = writableListSize
        override fun get(index: Int): SegmentRange = writableRanges[index]
    }

    init {
        primary = extractSegment(allocator.allocate(segmentCapacity))
        segments.add(primary)
        readableRanges.add(SegmentRange())
        writableRanges.add(SegmentRange())
        _locSeg = primary
    }

    override val capacity: Int
        get() {
            var sum = 0
            for (s in segments) sum += s.capacity
            return sum
        }

    override var readerIndex: Int
        get() = _readerIndex
        set(value) { _readerIndex = value }

    override var writerIndex: Int
        get() = _writerIndex
        set(value) { _writerIndex = value }

    override val readableBytes: Int get() = _writerIndex - _readerIndex
    override val writableBytes: Int get() = capacity - _writerIndex

    // ---- Byte-level reads / writes (same shape as candidate 1) ----

    override fun writeByte(value: Byte) {
        if (segments.size == 1 && _writerIndex < primary.capacity) {
            segmentPutByte(primary, _writerIndex, value)
            _writerIndex++
            return
        }
        writeByteSlow(value)
    }

    private fun writeByteSlow(value: Byte) {
        if (_writerIndex >= capacity) {
            throw KeelBufferOverflowException(
                "writeByte at writerIndex=$_writerIndex but chain capacity=$capacity; " +
                    "appendSegment first (no auto-grow on write)",
            )
        }
        locateLogical(_writerIndex)
        segmentPutByte(_locSeg, _locOff, value)
        _writerIndex++
    }

    override fun readByte(): Byte {
        if (segments.size == 1) {
            val b = segmentGetByte(primary, _readerIndex)
            _readerIndex++
            return b
        }
        locateLogical(_readerIndex)
        val b = segmentGetByte(_locSeg, _locOff)
        _readerIndex++
        return b
    }

    override fun getByte(index: Int): Byte {
        if (segments.size == 1) return segmentGetByte(primary, index)
        locateLogical(index)
        return segmentGetByte(_locSeg, _locOff)
    }

    override fun writeByteArray(src: ByteArray, offset: Int, length: Int) {
        if (length > writableBytes) {
            throw KeelBufferOverflowException(
                "writeByteArray length=$length exceeds writableBytes=$writableBytes; " +
                    "appendSegment to grow the chain (no auto-grow on write)",
            )
        }
        var remaining = length
        var srcCursor = offset
        while (remaining > 0) {
            locateLogical(_writerIndex)
            val seg = _locSeg
            val segOff = _locOff
            val segRoom = seg.capacity - segOff
            val chunk = if (segRoom < remaining) segRoom else remaining
            segmentPutBytes(seg, segOff, src, srcCursor, chunk)
            _writerIndex += chunk
            srcCursor += chunk
            remaining -= chunk
        }
    }

    override fun writeAscii(src: String, srcOffset: Int, length: Int) {
        for (i in 0 until length) {
            writeByte(src[srcOffset + i].code.toByte())
        }
    }

    override fun readByteArray(dest: ByteArray, offset: Int, length: Int) {
        var remaining = length
        var destCursor = offset
        while (remaining > 0) {
            locateLogical(_readerIndex)
            val seg = _locSeg
            val segOff = _locOff
            val segAvail = seg.capacity - segOff
            val chunk = if (segAvail < remaining) segAvail else remaining
            segmentGetBytes(seg, segOff, dest, destCursor, chunk)
            _readerIndex += chunk
            destCursor += chunk
            remaining -= chunk
        }
    }

    override fun copyTo(dest: Cand2IoBuf, length: Int) {
        for (i in 0 until length) dest.writeByte(readByte())
    }

    // ---- Lifecycle ----

    override fun clear() {
        _readerIndex = 0
        _writerIndex = 0
    }

    override fun retain(): Cand2IoBuf {
        for (s in segments) s.retain()
        return this
    }

    override fun close() {
        release()
    }

    override fun release(): Boolean {
        var freedAny = false
        for (s in segments) {
            if (s.release()) freedAny = true
        }
        return freedAny
    }

    // ---- Engine-facing segment iteration (explicit list shape per Cand2) ----

    override fun readableSegments(): SegmentRangeList {
        var written = 0
        var logicalStart = 0
        for (i in 0 until segments.size) {
            val s = segments[i]
            val logicalEnd = logicalStart + s.capacity
            val rStart = if (_readerIndex > logicalStart) _readerIndex else logicalStart
            val rEnd = if (_writerIndex < logicalEnd) _writerIndex else logicalEnd
            if (rStart < rEnd) {
                val range = readableRanges[written]
                range.memory = s.backing
                range.offset = rStart - logicalStart
                range.length = rEnd - rStart
                written++
            }
            logicalStart = logicalEnd
        }
        readableListSize = written
        return readableList
    }

    override fun writableSegments(): SegmentRangeList {
        var written = 0
        var logicalStart = 0
        for (i in 0 until segments.size) {
            val s = segments[i]
            val logicalEnd = logicalStart + s.capacity
            val wStart = if (_writerIndex > logicalStart) _writerIndex else logicalStart
            if (wStart < logicalEnd) {
                val range = writableRanges[written]
                range.memory = s.backing
                range.offset = wStart - logicalStart
                range.length = logicalEnd - wStart
                written++
            }
            logicalStart = logicalEnd
        }
        writableListSize = written
        return writableList
    }

    override fun appendSegment(seg: Segment) {
        if (capacity + seg.capacity > maxCapacity) {
            throw KeelBufferOverflowException(
                "appendSegment would push capacity from $capacity to " +
                    "${capacity + seg.capacity}, past maxCapacity=$maxCapacity",
            )
        }
        segments.add(seg)
        readableRanges.add(SegmentRange())
        writableRanges.add(SegmentRange())
    }

    // ---- Internal helpers (mirrors Cand1IoBufImpl) ----

    private fun locateLogical(logical: Int) {
        var remaining = logical
        val list = segments
        for (i in 0 until list.size) {
            val seg = list[i]
            if (remaining < seg.capacity) {
                _locSeg = seg
                _locOff = remaining
                return
            }
            remaining -= seg.capacity
        }
        throw IndexOutOfBoundsException("logical $logical out of capacity $capacity")
    }
}
