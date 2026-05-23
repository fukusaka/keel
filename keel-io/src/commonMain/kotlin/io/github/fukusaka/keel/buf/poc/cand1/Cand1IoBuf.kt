package io.github.fukusaka.keel.buf.poc.cand1

import io.github.fukusaka.keel.buf.SegmentBacking
import io.github.fukusaka.keel.buf.Releasable

/**
 * PoC candidate 1: multi-segment [io.github.fukusaka.keel.buf.IoBuf] where
 * segment composition is **hidden** from the caller and accessed only via
 * a callback iterator.
 *
 * **Design intent**: replace today's single-segment `IoBuf` with a logical
 * buffer that can span multiple [io.github.fukusaka.keel.buf.Segment]s
 * internally. Caller-facing API ([readByte] / [writeByte] / [getByte] /
 * [readByteArray] / [writeByteArray] / [copyTo]) is identical in shape to
 * the current `IoBuf`, so the codec / pipeline integration cost stays low.
 *
 * **Multi-seg origin (PoC scope)**: append-on-fill — each engine `recv()`
 * fills the tail segment; on `writableBytes == 0` the next `recv()` (or a
 * subsequent `writeByte` from the codec) allocates a fresh segment with
 * fixed capacity and chains it to the tail. Per-syscall scatter-read
 * (`readv`) is **not** in scope here; the interface does not preclude it,
 * but the PoC measures the simpler append flow.
 *
 * **Refcount semantic (B)**: each underlying [io.github.fukusaka.keel.buf.Segment]
 * has its own refcount; [retain] increments **every** segment in the
 * chain, [release] decrements them. A segment can be shared with another
 * `Cand1IoBuf` (e.g. via slice / view) and survives independently once
 * its refcount > 0 on the other side.
 *
 * **Engine scatter-gather**: exposed only via [forEachReadableSegment] /
 * [forEachWritableSegment] callbacks. The callback ([SegmentRangeAction])
 * is a `fun interface` so a lambda at the call site stays SAM-convertible
 * and (under JVM escape analysis or Native inlining) zero-alloc.
 *
 * **NOT a replacement for `IoBuf`**: this lives in `buf.poc.cand1.*` and
 * coexists with the production `IoBuf` for the duration of the PoC. Once
 * the multi-seg redesign lands, the chosen candidate is rolled into the
 * production `IoBuf` and this package is removed.
 */
interface Cand1IoBuf : Releasable {

    /**
     * Total logical capacity = sum of every segment's capacity. Grows as
     * new segments are appended on writer overflow; never shrinks while
     * the IoBuf is live.
     */
    val capacity: Int

    /**
     * Logical reader position across all segments (`0..writerIndex`).
     * Mutating jumps across segment boundaries are valid; the impl
     * resolves which segment owns the target byte internally.
     */
    var readerIndex: Int

    /**
     * Logical writer position across all segments
     * (`readerIndex..capacity`). Mutating beyond [capacity] triggers a
     * tail-segment allocation on the next write.
     */
    var writerIndex: Int

    /** Number of readable bytes (`writerIndex - readerIndex`). */
    val readableBytes: Int

    /** Number of writable bytes (`capacity - writerIndex`). */
    val writableBytes: Int

    /**
     * Writes [value] at the current write position. If the tail segment
     * is full, allocates a new tail segment and writes into it.
     */
    fun writeByte(value: Byte)

    /**
     * Bulk write of [length] bytes from [src] starting at [offset].
     * Split across tail segments on segment-boundary crossings; uses
     * platform-optimised copy within each segment.
     */
    fun writeByteArray(src: ByteArray, offset: Int, length: Int)

    /**
     * ASCII bulk write — interprets each char as a single byte (low 8
     * bits). Split across tail segments on boundary crossings.
     */
    fun writeAscii(src: String, srcOffset: Int, length: Int)

    /**
     * Reads one byte from the current read position and advances
     * [readerIndex]. Crosses segment boundaries transparently.
     */
    fun readByte(): Byte

    /**
     * Bulk read of [length] bytes into [dest] at [offset]. Crosses
     * segment boundaries; uses platform-optimised copy within each
     * segment.
     */
    fun readByteArray(dest: ByteArray, offset: Int, length: Int)

    /**
     * Random byte access at logical [index] (`0..capacity`). Resolves
     * the owning segment internally; **single-seg case** (the common
     * 99 %) takes a primary-segment fast path with cost identical to
     * today's `IoBuf.getByte`.
     */
    fun getByte(index: Int): Byte

    /**
     * Copy [length] bytes from this buffer (at current [readerIndex])
     * into [dest] (at its current [writerIndex]). Both sides advance.
     * Crosses segment boundaries on both sides.
     */
    fun copyTo(dest: Cand1IoBuf, length: Int)

    /** Resets [readerIndex] / [writerIndex] to 0; does not release segments. */
    fun clear()

    /** Increments refcount of every segment in the chain. Returns `this`. */
    fun retain(): Cand1IoBuf

    /**
     * Decrements refcount of every segment in the chain. Each segment is
     * returned to its owner (pool / heap) once its refcount reaches 0.
     */
    fun close()

    /**
     * Iterates over every currently-readable byte range (from
     * [readerIndex] toward [writerIndex]) as `(memory, offset, length)`
     * tuples. Used by the engine to build a `writev` iovec for a
     * gather-send syscall.
     *
     * The `(memory, offset, length)` tuple is delivered through the
     * callback rather than as a return value to keep the per-segment
     * iteration zero-alloc.
     */
    fun forEachReadableSegment(action: SegmentRangeAction)

    /**
     * Iterates over every currently-writable byte range (from
     * [writerIndex] toward [capacity]) as `(memory, offset, length)`
     * tuples. Intended for a future scatter-read (`readv`) engine path;
     * the PoC append-on-fill flow does not exercise this method, but the
     * shape is recorded here so the interface does not preclude (A).
     */
    fun forEachWritableSegment(action: SegmentRangeAction)
}

/**
 * Callback for [Cand1IoBuf.forEachReadableSegment] /
 * [Cand1IoBuf.forEachWritableSegment]. Each invocation delivers one
 * `(memory, offset, length)` triple covering a single segment's
 * contribution to the readable / writable range.
 *
 * **Zero-alloc contract**: callers should pass a SAM-converted lambda or
 * a re-usable callback instance. Under JVM escape analysis the lambda
 * becomes a stack-only object; Kotlin/Native inlines `fun interface`
 * dispatch at compile time when the callee is statically resolvable.
 */
fun interface SegmentRangeAction {
    fun apply(memory: SegmentBacking, offset: Int, length: Int)
}
