package io.github.fukusaka.keel.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NativeBufTest {

    @Test
    fun writeByte_readByte_roundTrip() {
        val buf = NativeBuf(4)
        buf.writeByte(0x41)
        buf.writeByte(0x42)
        assertEquals(0x41.toByte(), buf.readByte())
        assertEquals(0x42.toByte(), buf.readByte())
        buf.release()
    }

    @Test
    fun readerIndex_writerIndex_tracking() {
        val buf = NativeBuf(8)
        assertEquals(0, buf.readerIndex)
        assertEquals(0, buf.writerIndex)

        buf.writeByte(0x01)
        buf.writeByte(0x02)
        assertEquals(0, buf.readerIndex)
        assertEquals(2, buf.writerIndex)

        buf.readByte()
        assertEquals(1, buf.readerIndex)
        assertEquals(2, buf.writerIndex)
        buf.release()
    }

    @Test
    fun readableBytes_writableBytes() {
        val buf = NativeBuf(8)
        assertEquals(0, buf.readableBytes)
        assertEquals(8, buf.writableBytes)

        buf.writeByte(0x01)
        buf.writeByte(0x02)
        buf.writeByte(0x03)
        assertEquals(3, buf.readableBytes)
        assertEquals(5, buf.writableBytes)

        buf.readByte()
        assertEquals(2, buf.readableBytes)
        assertEquals(5, buf.writableBytes)
        buf.release()
    }

    @Test
    fun retain_release_lifecycle() {
        val buf = NativeBuf(4)
        val same = buf.retain()
        assertTrue(same === buf)

        // refCount is now 2; first release should not free
        assertFalse(buf.release())
        // refCount is now 1; second release frees
        assertTrue(buf.release())
    }

    @Test
    fun release_returns_true_when_freed() {
        val buf = NativeBuf(4)
        assertTrue(buf.release())
    }

    @Test
    fun release_after_retain_returns_false() {
        val buf = NativeBuf(4)
        buf.retain() // refCount = 2
        assertFalse(buf.release()) // refCount = 1
        assertTrue(buf.release())  // refCount = 0
    }

    @Test
    fun double_release_throws() {
        val buf = NativeBuf(4)
        buf.release()
        assertFailsWith<IllegalStateException> {
            buf.release()
        }
    }

    @Test
    fun retain_after_release_throws() {
        val buf = NativeBuf(4)
        buf.release()
        assertFailsWith<IllegalStateException> {
            buf.retain()
        }
    }

    @Test
    fun readerIndex_writerIndex_are_settable() {
        val buf = NativeBuf(8)
        buf.writeByte(0x01)
        buf.writeByte(0x02)
        buf.writeByte(0x03)

        buf.readerIndex = 0
        assertEquals(0x01.toByte(), buf.readByte())

        buf.writerIndex = 1
        buf.writeByte(0xFF.toByte())
        buf.readerIndex = 1
        assertEquals(0xFF.toByte(), buf.readByte())
        buf.release()
    }

    // --- compact ---

    @Test
    fun compactMovesReadableBytesToBeginning() {
        val buf = NativeBuf(8)
        buf.writeByte(0x41)
        buf.writeByte(0x42)
        buf.writeByte(0x43)
        buf.readByte() // discard 0x41

        assertEquals(1, buf.readerIndex)
        assertEquals(3, buf.writerIndex)
        assertEquals(2, buf.readableBytes)

        buf.compact()

        assertEquals(0, buf.readerIndex)
        assertEquals(2, buf.writerIndex)
        assertEquals(2, buf.readableBytes)
        assertEquals(6, buf.writableBytes)
        assertEquals(0x42, buf.readByte())
        assertEquals(0x43, buf.readByte())
        buf.release()
    }

    @Test
    fun compactNoOpWhenReaderIndexIsZero() {
        val buf = NativeBuf(8)
        buf.writeByte(0x41)
        buf.compact()
        assertEquals(0, buf.readerIndex)
        assertEquals(1, buf.writerIndex)
        buf.release()
    }

    @Test
    fun compactWithEmptyBuffer() {
        val buf = NativeBuf(8)
        buf.writeByte(0x41)
        buf.readByte() // empty
        buf.compact()
        assertEquals(0, buf.readerIndex)
        assertEquals(0, buf.writerIndex)
        buf.release()
    }

    // --- clear ---

    @Test
    fun clearResetsBothIndices() {
        val buf = NativeBuf(8)
        buf.writeByte(0x41)
        buf.writeByte(0x42)
        buf.readByte()
        buf.clear()
        assertEquals(0, buf.readerIndex)
        assertEquals(0, buf.writerIndex)
        assertEquals(8, buf.writableBytes)
        buf.release()
    }
}
