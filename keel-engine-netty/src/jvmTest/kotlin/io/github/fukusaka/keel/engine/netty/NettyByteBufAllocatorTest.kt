package io.github.fukusaka.keel.engine.netty

import io.netty.buffer.ByteBufAllocator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NettyByteBufAllocatorTest {

    private val alloc = NettyByteBufAllocator(ByteBufAllocator.DEFAULT)

    @Test
    fun `allocate returns NettyByteBufIoBuf with requested capacity`() {
        val buf = alloc.allocate(128)
        assertTrue(buf is NettyByteBufIoBuf)
        assertEquals(128, buf.capacity)
        assertEquals(0, buf.writerIndex)
        buf.release()
    }

    @Test
    fun `allocate capacity is cap-limited (maxCapacity = cap)`() {
        val buf = alloc.allocate(64) as NettyByteBufIoBuf
        // maxCapacity passed to directBuffer(cap, cap). Writing beyond capacity
        // would throw on the underlying ByteBuf; keel-side bounds checks also
        // guard via writableBytes, so this is just a sanity assert.
        assertEquals(64, buf.byteBuf.maxCapacity())
        buf.release()
    }

    @Test
    fun `createChild returns this (per-EL sharding handled by Netty internally)`() {
        assertSame(alloc, alloc.createChild())
    }

    @Test
    fun `slice is a zero-copy view returning the correct bytes`() {
        val src = alloc.allocate(16)
        "0123456789".encodeToByteArray().let { src.writeByteArray(it, 0, it.size) }
        val sliced = alloc.slice(src, 2, 4) // "2345"
        assertTrue(sliced is NettyByteBufIoBuf)
        assertEquals(4, sliced.capacity)
        assertEquals(4, sliced.writerIndex)
        assertEquals(0, sliced.readerIndex)
        val out = ByteArray(4)
        sliced.readByteArray(out, 0, 4)
        assertEquals("2345", out.decodeToString())
        src.release()
        sliced.release()
    }

    @Test
    fun `a held slice keeps its bytes after the source is released`() {
        val src = alloc.allocate(16)
        "0123456789".encodeToByteArray().let { src.writeByteArray(it, 0, it.size) }
        val sliced = alloc.slice(src, 4, 3) as NettyByteBufIoBuf // "456"
        // Release the source first — the retained slice shares the pooled memory
        // and must keep its own reserve alive (the held-buffer path).
        src.release()
        assertTrue(sliced.byteBuf.refCnt() > 0, "retained slice keeps a live reserve after source release")
        val out = ByteArray(3)
        sliced.readByteArray(out, 0, 3)
        assertEquals("456", out.decodeToString())
        sliced.release()
    }

    @Test
    fun `wrapBytes returns null (unsupported)`() {
        assertNull(alloc.wrapBytes(byteArrayOf(1, 2, 3), 0, 3))
    }

    @Test
    fun `hintSizeClass is a no-op`() {
        alloc.hintSizeClass(1024, 8)
        // Nothing to assert — just verify it doesn't throw or mutate shared state.
        val buf = alloc.allocate(1024)
        assertEquals(1024, buf.capacity)
        buf.release()
    }

    @Test
    fun `slice copies readable region into a fresh NettyByteBufIoBuf`() {
        val src = alloc.allocate(32)
        src.writeByteArray(byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80), 0, 8)
        // slice(offset=2, length=4) → {30, 40, 50, 60}
        val slice = alloc.slice(src, 2, 4)
        assertNotNull(slice)
        assertEquals(4, slice.readableBytes)
        val out = ByteArray(4)
        slice.readByteArray(out, 0, 4)
        assertEquals(listOf(30.toByte(), 40.toByte(), 50.toByte(), 60.toByte()), out.toList())
        src.release()
        slice.release()
    }

    @Test
    fun `release on allocated buf decrements Netty pool refcount`() {
        val buf = alloc.allocate(32) as NettyByteBufIoBuf
        val native = buf.byteBuf
        assertEquals(1, native.refCnt())
        assertTrue(buf.release())
        assertEquals(0, native.refCnt())
    }
}
