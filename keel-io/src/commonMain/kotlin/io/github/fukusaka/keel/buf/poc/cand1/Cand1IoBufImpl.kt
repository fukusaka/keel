package io.github.fukusaka.keel.buf.poc.cand1

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.KeelBufferOverflowException
import io.github.fukusaka.keel.buf.Segment
import io.github.fukusaka.keel.buf.poc.extractSegment
import io.github.fukusaka.keel.buf.poc.segmentGetByte
import io.github.fukusaka.keel.buf.poc.segmentGetBytes
import io.github.fukusaka.keel.buf.poc.segmentPutByte
import io.github.fukusaka.keel.buf.poc.segmentPutBytes

/**
 * PoC candidate-1 implementation: multi-segment [Cand1IoBuf] backed by
 * an [ArrayList] of [Segment]s allocated via the supplied
 * [BufferAllocator].
 *
 * **Segment ownership (B)**: per-segment refcount, exactly as the
 * design memo. [retain] iterates the chain and increments every
 * segment; [close] / [release] decrements them. A single segment
 * shared with another `Cand1IoBufImpl` (e.g. through a future slice
 * helper) survives until **all** its sharing IoBufs have released
 * their share.
 *
 * **Multi-seg origin (B, append-on-fill)**: writes target the tail
 * segment; when its capacity is exhausted, a fresh segment is
 * allocated via [allocator] and appended. Reads consume from the head
 * onward and never compact — drained head segments remain in the
 * chain until [clear] / [close] removes them (PoC simplification; the
 * production multi-seg IoBuf will release drained heads eagerly).
 *
 * **Hot-path fast path**: [getByte] / [readByte] / [writeByte] short-
 * circuit the segment walk when `segments.size == 1` (the 99 % single-
 * segment case), reaching the segment-byte access shim
 * (`buf.poc.segmentGet/Put*`) with a single branch on top of the
 * existing single-seg IoBuf cost.
 */
// Visibility note: `public` for the PoC so the cross-module bench
// (engine-nio / engine-kqueue test sources) can construct the impl
// directly. The class itself is PoC-scoped (`buf.poc.cand1`) and the
// whole package goes away once the multi-seg IoBuf candidate decision
// lands, so this does not commit keel-io to a wider API surface.
public class Cand1IoBufImpl(
    private val allocator: BufferAllocator,
    private val segmentCapacity: Int,
    override val maxCapacity: Int,
) : Cand1IoBuf {

    init {
        require(maxCapacity >= segmentCapacity) {
            "maxCapacity ($maxCapacity) must be >= segmentCapacity ($segmentCapacity)"
        }
    }

    private val segments: ArrayList<Segment> = ArrayList(2)

    /**
     * Cached reference to the first (currently only) segment. Kept as a
     * field so the single-seg fast path skips the [ArrayList] lookup.
     * Falls out of sync with [segments] only when [segments] grows past
     * one element, by which point the slow path is taken anyway.
     */
    private var primary: Segment

    private var _readerIndex: Int = 0
    private var _writerIndex: Int = 0

    /**
     * Out-param state for [locateLogical]: the segment that owns the
     * looked-up logical position. Field caches instead of returning a
     * `Pair` so the slow path does not allocate per byte; sound under
     * the single-thread invariant the rest of `IoBuf` already assumes.
     */
    private var _locSeg: Segment

    /** Out-param state for [locateLogical]: the offset within [_locSeg]. */
    private var _locOff: Int = 0

    init {
        primary = extractSegment(allocator.allocate(segmentCapacity))
        segments.add(primary)
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

    // ---- Byte-level reads / writes ----

    override fun writeByte(value: Byte) {
        // Fast path: writerIndex falls into the single primary segment.
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

    override fun copyTo(dest: Cand1IoBuf, length: Int) {
        // Per-byte copy through the destination IoBuf interface. Slow,
        // but the PoC microbench measures end-to-end multi-seg behaviour
        // — not the production-final bulk copy path.
        for (i in 0 until length) dest.writeByte(readByte())
    }

    // ---- Lifecycle ----

    override fun clear() {
        _readerIndex = 0
        _writerIndex = 0
    }

    override fun retain(): Cand1IoBuf {
        for (s in segments) s.retain()
        return this
    }

    override fun close() {
        release()
    }

    /**
     * Decrements every segment's refcount; returns `true` when **any**
     * segment in the chain reached refcount 0 on this call (i.e. some
     * portion of this IoBuf's storage was actually returned to its
     * owner). Matches the [io.github.fukusaka.keel.buf.Releasable]
     * contract that the production `IoBuf` uses.
     */
    override fun release(): Boolean {
        var freedAny = false
        for (s in segments) {
            if (s.release()) freedAny = true
        }
        return freedAny
    }

    // ---- Engine-facing segment iteration (callback shape per Cand1) ----

    override fun forEachReadableSegment(action: SegmentRangeAction) {
        var logicalStart = 0
        for (s in segments) {
            val logicalEnd = logicalStart + s.capacity
            val rStart = if (_readerIndex > logicalStart) _readerIndex else logicalStart
            val rEnd = if (_writerIndex < logicalEnd) _writerIndex else logicalEnd
            if (rStart < rEnd) {
                action.apply(s.backing, rStart - logicalStart, rEnd - rStart)
            }
            logicalStart = logicalEnd
        }
    }

    override fun forEachWritableSegment(action: SegmentRangeAction) {
        var logicalStart = 0
        for (s in segments) {
            val logicalEnd = logicalStart + s.capacity
            val wStart = if (_writerIndex > logicalStart) _writerIndex else logicalStart
            val wEnd = logicalEnd
            if (wStart < wEnd) {
                action.apply(s.backing, wStart - logicalStart, wEnd - wStart)
            }
            logicalStart = logicalEnd
        }
    }

    override fun appendSegment(seg: Segment) {
        if (capacity + seg.capacity > maxCapacity) {
            throw KeelBufferOverflowException(
                "appendSegment would push capacity from $capacity to " +
                    "${capacity + seg.capacity}, past maxCapacity=$maxCapacity",
            )
        }
        segments.add(seg)
    }

    // ---- Internal helpers ----

    /**
     * Resolves a logical position [logical] into the owning segment and
     * its absolute offset within that segment's backing. Walks the
     * chain linearly; for `segments.size == 1` the caller short-circuits
     * before reaching here.
     *
     * Writes the result to the [_locSeg] / [_locOff] field pair (no
     * tuple allocation). Caller reads those fields immediately after the
     * call, before any other operation that might re-invoke
     * [locateLogical].
     */
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
        // Walked past the end — the caller is responsible for
        // [ensureWritable] before driving the writer past capacity.
        throw IndexOutOfBoundsException("logical $logical out of capacity $capacity")
    }
}
