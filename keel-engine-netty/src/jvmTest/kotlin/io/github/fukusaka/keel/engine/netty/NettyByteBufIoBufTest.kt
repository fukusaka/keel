package io.github.fukusaka.keel.engine.netty

import io.netty.buffer.ByteBufAllocator
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NettyByteBufIoBufTest {

    private fun newBuf(cap: Int = 16): NettyByteBufIoBuf {
        val byteBuf = ByteBufAllocator.DEFAULT.directBuffer(cap, cap)
        return NettyByteBufIoBuf(byteBuf)
    }

    @Test
    fun `initial state`() {
        val buf = newBuf(16)
        assertEquals(16, buf.capacity)
        assertEquals(0, buf.readerIndex)
        assertEquals(0, buf.writerIndex)
        assertEquals(0, buf.readableBytes)
        assertEquals(16, buf.writableBytes)
        buf.release()
    }

    @Test
    fun `writeByte advances writerIndex and persists`() {
        val buf = newBuf()
        buf.writeByte(0x41)
        buf.writeByte(0x42)
        assertEquals(2, buf.writerIndex)
        assertEquals(0x41.toByte(), buf.byteBuf.getByte(0))
        assertEquals(0x42.toByte(), buf.byteBuf.getByte(1))
        buf.release()
    }

    @Test
    fun `writeByteArray bulk copies into underlying ByteBuf`() {
        val buf = newBuf()
        val src = byteArrayOf(1, 2, 3, 4, 5)
        buf.writeByteArray(src, 0, 5)
        assertEquals(5, buf.writerIndex)
        val out = ByteArray(5)
        buf.byteBuf.getBytes(0, out, 0, 5)
        assertContentEquals(src, out)
        buf.release()
    }

    @Test
    fun `writeAscii encodes single-byte characters`() {
        val buf = newBuf()
        buf.writeAscii("Hello", 0, 5)
        assertEquals(5, buf.writerIndex)
        val out = ByteArray(5)
        buf.byteBuf.getBytes(0, out, 0, 5)
        assertContentEquals("Hello".toByteArray(Charsets.US_ASCII), out)
        buf.release()
    }

    @Test
    fun `writeAscii with srcOffset`() {
        val buf = newBuf()
        buf.writeAscii("HelloWorld", 5, 5)
        val out = ByteArray(5)
        buf.byteBuf.getBytes(0, out, 0, 5)
        assertContentEquals("World".toByteArray(Charsets.US_ASCII), out)
        buf.release()
    }

    @Test
    fun `writeByteArray rejects oversize`() {
        val buf = newBuf(8)
        assertFailsWith<IllegalArgumentException> {
            buf.writeByteArray(ByteArray(16), 0, 16)
        }
        buf.release()
    }

    @Test
    fun `readByte advances readerIndex`() {
        val buf = newBuf()
        buf.writeByte(0x30); buf.writeByte(0x31); buf.writeByte(0x32)
        assertEquals(0x30.toByte(), buf.readByte())
        assertEquals(0x31.toByte(), buf.readByte())
        assertEquals(2, buf.readerIndex)
        assertEquals(1, buf.readableBytes)
        buf.release()
    }

    @Test
    fun `readByteArray bulk reads and advances`() {
        val buf = newBuf()
        buf.writeByteArray(byteArrayOf(10, 20, 30, 40), 0, 4)
        val dest = ByteArray(4)
        buf.readByteArray(dest, 0, 4)
        assertContentEquals(byteArrayOf(10, 20, 30, 40), dest)
        assertEquals(4, buf.readerIndex)
        buf.release()
    }

    @Test
    fun `getByte is absolute and does not move indices`() {
        val buf = newBuf()
        buf.writeByte(0xAA.toByte())
        buf.writeByte(0xBB.toByte())
        assertEquals(0xAA.toByte(), buf.getByte(0))
        assertEquals(0xBB.toByte(), buf.getByte(1))
        assertEquals(0, buf.readerIndex)
        buf.release()
    }

    @Test
    fun `clear resets indices`() {
        val buf = newBuf()
        buf.writeByte(1); buf.writeByte(2); buf.readByte()
        buf.clear()
        assertEquals(0, buf.readerIndex)
        assertEquals(0, buf.writerIndex)
        buf.release()
    }

    @Test
    fun `retain increments refcount, only release to zero triggers native release`() {
        val buf = newBuf()
        val nativeRef = buf.byteBuf
        assertEquals(1, nativeRef.refCnt())

        buf.retain()
        buf.retain()
        assertFalse(buf.release()) // 3 -> 2
        assertFalse(buf.release()) // 2 -> 1
        assertEquals(1, nativeRef.refCnt(), "native not released yet")
        assertTrue(buf.release()) // 1 -> 0
        assertEquals(0, nativeRef.refCnt())
    }

    @Test
    fun `release after zero throws`() {
        val buf = newBuf()
        buf.release()
        assertFailsWith<IllegalStateException> { buf.release() }
    }

    @Test
    fun `retain after release throws`() {
        val buf = newBuf()
        buf.release()
        assertFailsWith<IllegalStateException> { buf.retain() }
    }

    @Test
    fun `close is escape hatch, does NOT release underlying ByteBuf`() {
        val buf = newBuf()
        val nativeRef = buf.byteBuf
        buf.close()
        assertEquals(1, nativeRef.refCnt(), "close must not drop the native refcount")
        assertFailsWith<IllegalStateException> { buf.retain() } // refCount=0
        nativeRef.release() // manual cleanup
    }

    @Test
    fun `copyTo copies between NettyByteBufIoBuf instances`() {
        val src = newBuf()
        src.writeByteArray(byteArrayOf(1, 2, 3, 4), 0, 4)
        val dest = newBuf()
        src.copyTo(dest, 4)
        assertEquals(4, src.readerIndex)
        assertEquals(4, dest.writerIndex)
        val out = ByteArray(4)
        dest.readByteArray(out, 0, 4)
        assertContentEquals(byteArrayOf(1, 2, 3, 4), out)
        src.release(); dest.release()
    }

    @Test
    fun `release at refcount zero releases backing ByteBuf`() {
        val buf = newBuf()
        val nativeRef = buf.byteBuf
        assertEquals(1, nativeRef.refCnt())
        // release() at refcount zero is the single path that decrements the native ref.
        assertTrue(buf.release())
        assertEquals(0, nativeRef.refCnt())
    }
}
