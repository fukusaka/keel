package io.github.fukusaka.keel.engine.netty

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.NioByteBufferBacking
import io.github.fukusaka.keel.buf.UnsafeIoBufApi
import io.netty.buffer.ByteBuf
import java.nio.ByteBuffer

/**
 * [IoBuf] implementation backed directly by a Netty [ByteBuf]
 * (engine-direct — no [io.github.fukusaka.keel.buf.Segment]).
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
 *   readable region as `[0, readableBytes)`. This replaces the previous
 *   path that built a `DirectIoBuf` + `Segment` + `RawSegmentBacking` +
 *   `NettyByteBufOwner` (4 allocations per receive) with a single
 *   `NettyByteBufIoBuf` allocation per receive.
 *
 * **Refcount bridging**: Netty `ByteBuf.refCnt` is atomic (multi-threaded
 * pool safety); keel [IoBuf] refcount is non-atomic (EventLoop-confined).
 * keel's refcount is the logical ref count; the underlying `ByteBuf`
 * carries one reserve that is released when keel's refcount drops to
 * zero (engine-direct: no [io.github.fukusaka.keel.buf.Segment], the
 * wrapper self-manages the `ByteBuf` reserve in [release]). Explicit
 * extra holds on the `ByteBuf` (e.g. `retainedSlice` during flush) are
 * independent of the keel-side count.
 *
 * **`close()` semantics**: escape hatch — drops the refcount to zero
 * and does NOT release the underlying ByteBuf (matches the contract
 * introduced by PR #351). Callers relying on normal lifecycle use
 * [release].
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

    private var refCount: Int = 1

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
        check(refCount > 0) { "Cannot retain a released buffer" }
        refCount++
        return this
    }

    override fun release(): Boolean {
        check(refCount > 0) { "Buffer already released" }
        if (--refCount == 0) {
            // Engine-direct: no Segment. Release the backing Netty ByteBuf
            // reserve directly. ByteBuf.release() is thread-safe (atomic CAS).
            byteBuf.release()
            return true
        }
        return false
    }

    override fun close() {
        refCount = 0
        // Escape hatch. Does NOT release the underlying Netty ByteBuf
        // (matches PR #351's close() contract). Callers should use
        // release() for the normal lifecycle.
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
