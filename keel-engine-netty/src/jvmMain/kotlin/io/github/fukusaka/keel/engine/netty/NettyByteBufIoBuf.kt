package io.github.fukusaka.keel.engine.netty

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.IoBufMemoryOwner
import io.netty.buffer.ByteBuf

/**
 * [IoBuf] implementation backed directly by a Netty [ByteBuf].
 *
 * Returned by [NettyByteBufAllocator]. On the Netty engine, allocating a
 * keel write buffer through this path means the keel pipeline writes bytes
 * straight into a pooled Netty `ByteBuf`; the flush path in
 * [NettyIoTransport] detects the wrapper and hands the underlying
 * `ByteBuf` to `nettyChannel.writeAndFlush` without the
 * `Unpooled.wrappedBuffer` wrapper step that a generic `DirectIoBuf`
 * requires.
 *
 * **Refcount bridging**: Netty `ByteBuf.refCnt` is atomic (multi-threaded
 * pool safety); keel [IoBuf] refcount is non-atomic (EventLoop-confined).
 * keel's refcount is the logical ref count; the underlying `ByteBuf`
 * carries one reserve that is released when keel's refcount drops to
 * zero (via [NettyByteBufOwner]). Explicit extra holds on the
 * `ByteBuf` (e.g. `retainedSlice` during flush) are independent of the
 * keel-side count.
 *
 * **`close()` semantics**: escape hatch — drops the refcount to zero
 * and does NOT release the underlying ByteBuf (matches the contract
 * introduced by PR #351). Callers relying on normal lifecycle use
 * [release].
 *
 * @param byteBuf The Netty [ByteBuf] backing this buffer.
 */
internal class NettyByteBufIoBuf(
    internal val byteBuf: ByteBuf,
) : IoBuf {

    override val capacity: Int get() = byteBuf.capacity()

    override var readerIndex: Int = 0
    override var writerIndex: Int = 0

    override val readableBytes: Int get() = writerIndex - readerIndex
    override val writableBytes: Int get() = capacity - writerIndex

    private var refCount: Int = 1

    override val memoryOwner: IoBufMemoryOwner = NettyByteBufOwner(byteBuf)

    override fun writeByte(value: Byte) {
        byteBuf.setByte(writerIndex, value.toInt())
        writerIndex++
    }

    override fun writeByteArray(src: ByteArray, offset: Int, length: Int) {
        require(length <= writableBytes) { "length $length exceeds writableBytes $writableBytes" }
        byteBuf.setBytes(writerIndex, src, offset, length)
        writerIndex += length
    }

    override fun writeAscii(src: String, srcOffset: Int, length: Int) {
        require(length <= writableBytes) { "length $length exceeds writableBytes $writableBytes" }
        var i = 0
        while (i < length) {
            byteBuf.setByte(writerIndex + i, src[srcOffset + i].code)
            i++
        }
        writerIndex += length
    }

    override fun copyTo(dest: IoBuf, length: Int) {
        require(length <= readableBytes) { "length $length exceeds readableBytes $readableBytes" }
        require(length <= dest.writableBytes) { "length $length exceeds dest.writableBytes ${dest.writableBytes}" }
        if (length == 0) return
        val tmp = ByteArray(length)
        byteBuf.getBytes(readerIndex, tmp, 0, length)
        dest.writeByteArray(tmp, 0, length)
        readerIndex += length
    }

    override fun readByteArray(dest: ByteArray, offset: Int, length: Int) {
        require(length <= readableBytes) { "length $length exceeds readableBytes $readableBytes" }
        if (length == 0) return
        byteBuf.getBytes(readerIndex, dest, offset, length)
        readerIndex += length
    }

    override fun readByte(): Byte = byteBuf.getByte(readerIndex++)

    override fun getByte(index: Int): Byte = byteBuf.getByte(index)

    override fun compact() {
        if (readerIndex > 0) {
            val readable = readableBytes
            if (readable > 0) {
                val tmp = ByteArray(readable)
                byteBuf.getBytes(readerIndex, tmp, 0, readable)
                byteBuf.setBytes(0, tmp, 0, readable)
            }
            readerIndex = 0
            writerIndex = readable
        }
    }

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
            memoryOwner.release(this)
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
}
