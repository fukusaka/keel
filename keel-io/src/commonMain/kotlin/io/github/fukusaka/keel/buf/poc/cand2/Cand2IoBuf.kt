package io.github.fukusaka.keel.buf.poc.cand2

import io.github.fukusaka.keel.buf.SegmentBacking
import io.github.fukusaka.keel.buf.Releasable

/**
 * PoC candidate 2: multi-segment [io.github.fukusaka.keel.buf.IoBuf] where
 * segment composition is **exposed** to the caller via an explicit list
 * of `(memory, offset, length)` ranges.
 *
 * **Design intent**: same multi-seg logical buffer as candidate 1, but
 * the engine + codec can iterate segments through a first-class list API
 * rather than a callback. Scatter-gather syscalls (`writev`, future
 * `readv`) build their iovec directly from
 * [readableSegments] / [writableSegments] with no intermediate object
 * per element.
 *
 * **Multi-seg origin (PoC scope)**: identical to candidate 1 — append-
 * on-fill via successive engine `recv()` calls. Per-syscall scatter-
 * read is not in scope.
 *
 * **Refcount semantic (B)**: identical to candidate 1 — per-segment
 * refcount, [retain] / [release] iterate every segment in the chain.
 *
 * **`SegmentRange` is mutable + reusable**: each `Cand2IoBuf` instance
 * pre-allocates one [SegmentRange] per chained segment and rewrites
 * their `(memory, offset, length)` fields when [readableSegments] /
 * [writableSegments] is called. Callers must consume the returned
 * [SegmentRangeList] **before** the next operation that mutates this
 * buffer (write / read / refcount / `clear`); a subsequent call may
 * rewrite the same instances. The intent is to drive the per-syscall
 * iovec build with zero per-call allocation.
 */
interface Cand2IoBuf : Releasable {

    val capacity: Int
    var readerIndex: Int
    var writerIndex: Int
    val readableBytes: Int
    val writableBytes: Int

    fun writeByte(value: Byte)
    fun writeByteArray(src: ByteArray, offset: Int, length: Int)
    fun writeAscii(src: String, srcOffset: Int, length: Int)
    fun readByte(): Byte
    fun readByteArray(dest: ByteArray, offset: Int, length: Int)
    fun getByte(index: Int): Byte
    fun copyTo(dest: Cand2IoBuf, length: Int)
    fun clear()
    fun retain(): Cand2IoBuf
    fun close()

    /**
     * Returns the readable byte ranges as a [SegmentRangeList]. The same
     * list instance and the same [SegmentRange] instances are returned
     * across calls; the impl rewrites their fields to reflect the
     * current `(readerIndex..writerIndex)` range. Caller must consume
     * before any state-mutating operation on this buffer.
     */
    fun readableSegments(): SegmentRangeList

    /** Symmetric counterpart over `(writerIndex..capacity)`. */
    fun writableSegments(): SegmentRangeList
}

/**
 * Mutable `(memory, offset, length)` triple. **Re-used** across
 * [Cand2IoBuf.readableSegments] / [Cand2IoBuf.writableSegments] calls
 * on the same [Cand2IoBuf]: the impl rewrites these fields rather than
 * allocating fresh instances. Callers must finish iterating before the
 * next operation that may rewrite them.
 *
 * Plain `var` properties — `@JvmField` (which would bypass the JVM
 * property accessor) is JVM-only and not legal in commonMain. The JVM
 * JIT inlines simple property access through C2 escape analysis, so
 * the surface cost is on par with a direct field read.
 */
class SegmentRange internal constructor() {
    var memory: SegmentBacking? = null
        internal set

    var offset: Int = 0
        internal set

    var length: Int = 0
        internal set
}

/**
 * Fixed-size, index-addressable view over the pre-allocated
 * [SegmentRange] instances owned by a [Cand2IoBuf]. The list contents
 * (i.e. the underlying [SegmentRange] field values) are refreshed each
 * call to [Cand2IoBuf.readableSegments] / [Cand2IoBuf.writableSegments].
 *
 * Implementations are expected to be backed by a simple `Array` —
 * `get(i)` is a direct array index access.
 */
interface SegmentRangeList {
    val size: Int
    operator fun get(index: Int): SegmentRange
}
