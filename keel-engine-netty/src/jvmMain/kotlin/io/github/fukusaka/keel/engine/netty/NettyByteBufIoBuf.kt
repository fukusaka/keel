package io.github.fukusaka.keel.engine.netty

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.NioByteBufferBacking
import io.github.fukusaka.keel.buf.UnsafeIoBufApi
import io.netty.buffer.ByteBuf
import io.netty.util.IllegalReferenceCountException
import java.nio.ByteBuffer

/**
 * [IoBuf] implementation backed directly by a Netty [ByteBuf]
 * (engine-direct — does not extend
 * [io.github.fukusaka.keel.buf.AbstractIoBuf]).
 *
 * Used by two paths on the Netty engine:
 *
 * - **Allocator path** (write side): [NettyByteBufAllocator] hands out
 *   a fresh empty [ByteBuf] wrapped as `NettyByteBufIoBuf(byteBuf)`
 *   (default constructor: `baseIndex = 0`, `initialWriterIndex = 0`).
 *   The keel pipeline writes bytes straight into the pooled `ByteBuf`;
 *   the flush path in [NettyIoTransport] detects the wrapper and hands
 *   the underlying `ByteBuf` to `nettyChannel.writeAndFlush` without
 *   the `Unpooled.wrappedBuffer` wrapper step that a generic
 *   `DirectIoBuf` requires.
 *
 * - **Inbound path** (read side): [NettyIoTransport]'s `channelRead`
 *   handler wraps the incoming `ByteBuf` via [wrapInbound] (zero-copy,
 *   ownership transferred to the wrapper). [baseOffset] biases keel
 *   indices to the `ByteBuf`'s current `readerIndex` so keel sees the
 *   readable region as `[0, readableBytes)`. This replaces a generic
 *   `DirectIoBuf.wrapExternal` + ad-hoc
 *   [io.github.fukusaka.keel.buf.IoBufOwner] closure path with a
 *   single `NettyByteBufIoBuf` allocation per receive.
 *
 * **Refcount**: delegated 1:1 to the underlying Netty [ByteBuf]'s atomic
 * `refCnt`. Every keel-side [retain] / [release] is a direct pass-through
 * to [ByteBuf.retain] / [ByteBuf.release] — the wrapper carries no
 * separate counter and no non-atomic mirror. This makes the lifecycle
 * thread-safe through to the underlying ByteBuf's atomic CAS, matching
 * the [IoBuf] contract that lifecycle (retain / release / close) is
 * thread-safe across every keel buffer implementation. Explicit extra
 * holds on the `ByteBuf` (e.g. `retainedSlice` during flush) compose
 * naturally: every reserve, regardless of where it originated,
 * contributes to the same `refCnt` and the buffer goes back to the
 * Netty pool when `refCnt` reaches zero. [IllegalReferenceCountException]
 * from a retain / release on an already-freed `ByteBuf` is rewrapped as
 * [IllegalStateException] to honour the [IoBuf] contract's exception
 * type.
 *
 * **`close()` semantics**: escape hatch — marks the wrapper as closed
 * so future [retain] / [release] throw [IllegalStateException], but
 * does NOT release the underlying ByteBuf (matches the contract
 * introduced by PR #351 — the underlying pool slot is intentionally
 * leaked because the wrapper's owning context is going away anyway).
 * Callers relying on normal lifecycle use [release].
 *
 * **Race window between `close()` and a concurrent `retain()`.**
 * The `!closed` check in [retain] is not atomically bonded with the
 * `byteBuf.retain()` call that follows (the underlying ByteBuf has its
 * own atomic refcount, but we cannot fuse a single CAS across the two
 * primitives). If thread A passes the `!closed` check just before
 * thread B's `close()` flips `closed = true`, A then runs
 * `byteBuf.retain()` and returns a wrapper that the caller treats as
 * successfully retained — but A's subsequent [release] will observe
 * `closed = true` and throw [IllegalStateException], leaving one extra
 * reserve on the underlying ByteBuf that the wrapper no longer knows
 * how to release. The leak is bounded (one ByteBuf reserve per race
 * occurrence) and structurally inseparable from the `close()`
 * escape-hatch contract that already promises to intentionally leak
 * the wrapper's underlying pool slot. No use-after-free, no
 * double-free, no cross-thread state corruption — only an additional
 * reserve added to the pool slot that close already abandons.
 * Closing the gap entirely would require either folding the wrapper
 * close into `byteBuf.release()` (breaks the PR #351 intentional-leak
 * contract that this wrapper exists to honour) or coordinating every
 * retain through a wrapper-side lock (defeats the lock-free delegate
 * design). Acceptable as a documented edge of the escape-hatch
 * contract; the standard usage pattern of [close] — single-threaded
 * engine shutdown coordinator with no concurrent retain — does not
 * exercise the race.
 *
 * @param byteBuf    The Netty [ByteBuf] backing this buffer.
 * @param baseOffset Index in [byteBuf] that corresponds to keel-index 0.
 *                   Zero for the allocator path (fresh buf, fill from 0);
 *                   `byteBuf.readerIndex()` for the inbound path (wrap
 *                   an already-filled buf so keel sees the readable
 *                   region as `[0, readableBytes)`).
 * @param initialWriterIndex Initial value for [writerIndex]. Zero for
 *                   the allocator path; `byteBuf.readableBytes()` for
 *                   the inbound path.
 */
internal class NettyByteBufIoBuf(
    internal val byteBuf: ByteBuf,
    private val baseOffset: Int = 0,
    initialWriterIndex: Int = 0,
) : IoBuf, NioByteBufferBacking {

    override val capacity: Int = byteBuf.capacity() - baseOffset

    override var readerIndex: Int = 0
    override var writerIndex: Int = initialWriterIndex

    override val readableBytes: Int get() = writerIndex - readerIndex
    override val writableBytes: Int get() = capacity - writerIndex

    /**
     * Writable [ByteBuffer] view over `[baseOffset, baseOffset + capacity)`
     * in the underlying [ByteBuf], i.e. the same keel-visible window as
     * indices `[0, capacity)` exposed by this wrapper.
     *
     * Cached once at construction to avoid per-record allocation on the
     * TLS hot path
     * ([io.github.fukusaka.keel.tls.jsse.JsseTlsCodec] calls this on
     * every [javax.net.ssl.SSLEngine.wrap] / [javax.net.ssl.SSLEngine.unwrap]).
     * The view shares the same off-heap memory as the underlying
     * [ByteBuf], so bytes written by SSLEngine are immediately visible
     * via [byteBuf] accessor methods used by the flush path in
     * [NettyIoTransport]. Callers must set [ByteBuffer.position] and
     * [ByteBuffer.limit] before each use.
     *
     * **Capacity**: fixed to `[0, capacity)` at construction.
     * The underlying [ByteBuf] is never resized after allocation, so
     * the range remains valid for this object's lifetime.
     *
     * **Lifetime**: valid only while this [IoBuf]'s keel refcount is
     * greater than zero. Once [release] drops the refcount to zero, the
     * underlying [ByteBuf] is returned to the Netty pool and the
     * off-heap memory may be reused. Accessing this [ByteBuffer] after
     * [release] is a use-after-free.
     */
    @UnsafeIoBufApi
    override val unsafeNioByteBuffer: ByteBuffer = byteBuf.nioBuffer(baseOffset, capacity)

    /**
     * Wrapper-level closed flag. Set by [close] to mark the wrapper as
     * abandoned (the underlying [ByteBuf] is intentionally leaked per
     * the [close] contract). `@Volatile` so the flip is visible across
     * threads — the [retain] / [release] checks rely on it to translate
     * post-close operations into [IllegalStateException] without
     * touching the underlying `ByteBuf`'s atomic `refCnt`.
     */
    @Volatile
    private var closed: Boolean = false

    override fun writeByte(value: Byte) {
        byteBuf.setByte(baseOffset + writerIndex, value.toInt())
        writerIndex++
    }

    override fun writeByteArray(src: ByteArray, offset: Int, length: Int) {
        require(length <= writableBytes) { "length $length exceeds writableBytes $writableBytes" }
        byteBuf.setBytes(baseOffset + writerIndex, src, offset, length)
        writerIndex += length
    }

    override fun writeAscii(src: String, srcOffset: Int, length: Int) {
        require(length <= writableBytes) { "length $length exceeds writableBytes $writableBytes" }
        var i = 0
        while (i < length) {
            byteBuf.setByte(baseOffset + writerIndex + i, src[srcOffset + i].code)
            i++
        }
        writerIndex += length
    }

    override fun copyTo(dest: IoBuf, length: Int) {
        require(length <= readableBytes) { "length $length exceeds readableBytes $readableBytes" }
        require(length <= dest.writableBytes) { "length $length exceeds dest.writableBytes ${dest.writableBytes}" }
        if (length == 0) return
        val tmp = ByteArray(length)
        byteBuf.getBytes(baseOffset + readerIndex, tmp, 0, length)
        dest.writeByteArray(tmp, 0, length)
        readerIndex += length
    }

    override fun readByteArray(dest: ByteArray, offset: Int, length: Int) {
        require(length <= readableBytes) { "length $length exceeds readableBytes $readableBytes" }
        if (length == 0) return
        byteBuf.getBytes(baseOffset + readerIndex, dest, offset, length)
        readerIndex += length
    }

    override fun readByte(): Byte = byteBuf.getByte(baseOffset + readerIndex++)

    override fun getByte(index: Int): Byte = byteBuf.getByte(baseOffset + index)

    override fun clear() {
        readerIndex = 0
        writerIndex = 0
    }

    override fun retain(): IoBuf {
        check(!closed) { "Cannot retain a released buffer" }
        try {
            byteBuf.retain()
        } catch (e: IllegalReferenceCountException) {
            // Underlying ByteBuf was released between the closed check and
            // here, or naturally drained to refCnt == 0 by a prior release().
            // Translate to the IoBuf contract's exception type.
            throw IllegalStateException("Cannot retain a released buffer", e)
        }
        return this
    }

    override fun release(): Boolean {
        check(!closed) { "Buffer already released" }
        return try {
            // Delegate directly to the atomic refCnt. Returns true when the
            // underlying ByteBuf went back to the Netty pool (refCnt 1 → 0).
            byteBuf.release()
        } catch (e: IllegalReferenceCountException) {
            throw IllegalStateException("Buffer already released", e)
        }
    }

    override fun close() {
        // Idempotent: marks the wrapper as abandoned. Does NOT release the
        // underlying Netty ByteBuf (matches PR #351's close() contract —
        // the pool slot is intentionally leaked because the wrapper's
        // owning context is going away anyway). Callers should use
        // release() for the normal lifecycle.
        closed = true
    }

    companion object {
        /**
         * Wraps an already-populated inbound [ByteBuf] as an
         * engine-direct [NettyByteBufIoBuf] (the `channelRead`
         * zero-copy path). The keel-side view covers
         * `[readerIndex(), capacity())`, with [writerIndex] preset
         * to [ByteBuf.readableBytes].
         *
         * Ownership of the [ByteBuf] is transferred to the returned
         * wrapper — the pooled buffer is returned to Netty's arena
         * when the wrapper's keel refcount reaches zero.
         */
        fun wrapInbound(byteBuf: ByteBuf): NettyByteBufIoBuf = NettyByteBufIoBuf(
            byteBuf,
            baseOffset = byteBuf.readerIndex(),
            initialWriterIndex = byteBuf.readableBytes(),
        )
    }
}
