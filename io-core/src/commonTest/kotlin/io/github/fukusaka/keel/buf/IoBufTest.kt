package io.github.fukusaka.keel.buf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IoBufTest {

    @Test
    fun writeByte_readByte_roundTrip() {
        val buf = createDefaultIoBuf(4)
        buf.writeByte(0x41)
        buf.writeByte(0x42)
        assertEquals(0x41.toByte(), buf.readByte())
        assertEquals(0x42.toByte(), buf.readByte())
        buf.release()
    }

    @Test
    fun readerIndex_writerIndex_tracking() {
        val buf = createDefaultIoBuf(8)
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
        val buf = createDefaultIoBuf(8)
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
        val buf = createDefaultIoBuf(4)
        val same = buf.retain()
        assertTrue(same === buf)

        // refCount is now 2; first release should not free
        assertFalse(buf.release())
        // refCount is now 1; second release frees
        assertTrue(buf.release())
    }

    @Test
    fun release_returns_true_when_freed() {
        val buf = createDefaultIoBuf(4)
        assertTrue(buf.release())
    }

    @Test
    fun release_after_retain_returns_false() {
        val buf = createDefaultIoBuf(4)
        buf.retain() // refCount = 2
        assertFalse(buf.release()) // refCount = 1
        assertTrue(buf.release())  // refCount = 0
    }

    @Test
    fun double_release_throws() {
        val buf = createDefaultIoBuf(4)
        buf.release()
        assertFailsWith<IllegalStateException> {
            buf.release()
        }
    }

    @Test
    fun retain_after_release_throws() {
        val buf = createDefaultIoBuf(4)
        buf.release()
        assertFailsWith<IllegalStateException> {
            buf.retain()
        }
    }

    @Test
    fun readerIndex_writerIndex_are_settable() {
        val buf = createDefaultIoBuf(8)
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
        val buf = createDefaultIoBuf(8)
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
        val buf = createDefaultIoBuf(8)
        buf.writeByte(0x41)
        buf.compact()
        assertEquals(0, buf.readerIndex)
        assertEquals(1, buf.writerIndex)
        buf.release()
    }

    @Test
    fun compactWithEmptyBuffer() {
        val buf = createDefaultIoBuf(8)
        buf.writeByte(0x41)
        buf.readByte() // empty
        buf.compact()
        assertEquals(0, buf.readerIndex)
        assertEquals(0, buf.writerIndex)
        buf.release()
    }

    // --- writeBytes ---

    @Test
    fun writeBytesBasic() {
        val buf = createDefaultIoBuf(16)
        val src = byteArrayOf(0x41, 0x42, 0x43)
        buf.writeBytes(src, 0, 3)
        assertEquals(3, buf.writerIndex)
        assertEquals(0x41.toByte(), buf.readByte())
        assertEquals(0x42.toByte(), buf.readByte())
        assertEquals(0x43.toByte(), buf.readByte())
        buf.release()
    }

    @Test
    fun writeBytesWithOffset() {
        val buf = createDefaultIoBuf(16)
        val src = byteArrayOf(0x10, 0x20, 0x30, 0x40)
        buf.writeBytes(src, 1, 2)
        assertEquals(2, buf.writerIndex)
        assertEquals(0x20.toByte(), buf.readByte())
        assertEquals(0x30.toByte(), buf.readByte())
        buf.release()
    }

    @Test
    fun writeBytesExceedsCapacityThrows() {
        val buf = createDefaultIoBuf(4)
        val src = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        assertFailsWith<IllegalArgumentException> {
            buf.writeBytes(src, 0, 5)
        }
        buf.release()
    }

    @Test
    fun writeBytesZeroLength() {
        val buf = createDefaultIoBuf(4)
        buf.writeBytes(byteArrayOf(), 0, 0)
        assertEquals(0, buf.writerIndex)
        buf.release()
    }

    @Test
    fun writeBytesFullCapacity() {
        val buf = createDefaultIoBuf(4)
        val src = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        buf.writeBytes(src, 0, 4)
        assertEquals(4, buf.writerIndex)
        assertEquals(0, buf.writableBytes)
        assertEquals(0x01.toByte(), buf.readByte())
        assertEquals(0x02.toByte(), buf.readByte())
        assertEquals(0x03.toByte(), buf.readByte())
        assertEquals(0x04.toByte(), buf.readByte())
        buf.release()
    }

    // --- writeAsciiString ---

    @Test
    fun writeAsciiStringBasic() {
        val buf = createDefaultIoBuf(16)
        buf.writeAsciiString("ABC", 0, 3)
        assertEquals(3, buf.writerIndex)
        assertEquals(0x41.toByte(), buf.readByte())
        assertEquals(0x42.toByte(), buf.readByte())
        assertEquals(0x43.toByte(), buf.readByte())
        buf.release()
    }

    @Test
    fun writeAsciiStringWithOffset() {
        val buf = createDefaultIoBuf(16)
        buf.writeAsciiString("Hello", 1, 3)
        assertEquals(3, buf.writerIndex)
        assertEquals('e'.code.toByte(), buf.readByte())
        assertEquals('l'.code.toByte(), buf.readByte())
        assertEquals('l'.code.toByte(), buf.readByte())
        buf.release()
    }

    @Test
    fun writeAsciiStringExceedsCapacityThrows() {
        val buf = createDefaultIoBuf(4)
        assertFailsWith<IllegalArgumentException> {
            buf.writeAsciiString("Hello", 0, 5)
        }
        buf.release()
    }

    @Test
    fun writeAsciiStringZeroLength() {
        val buf = createDefaultIoBuf(4)
        buf.writeAsciiString("Hello", 0, 0)
        assertEquals(0, buf.writerIndex)
        buf.release()
    }

    // --- clear ---

    @Test
    fun clearResetsBothIndices() {
        val buf = createDefaultIoBuf(8)
        buf.writeByte(0x41)
        buf.writeByte(0x42)
        buf.readByte()
        buf.clear()
        assertEquals(0, buf.readerIndex)
        assertEquals(0, buf.writerIndex)
        assertEquals(8, buf.writableBytes)
        buf.release()
    }

    // --- getByte ---

    @Test
    fun getByteReadsAtAbsoluteIndex() {
        val buf = createDefaultIoBuf(8)
        buf.writeByte(0x41) // 'A'
        buf.writeByte(0x42) // 'B'
        buf.writeByte(0x43) // 'C'
        assertEquals(0x41.toByte(), buf.getByte(0))
        assertEquals(0x42.toByte(), buf.getByte(1))
        assertEquals(0x43.toByte(), buf.getByte(2))
        // getByte does not advance readerIndex
        assertEquals(0, buf.readerIndex)
        buf.release()
    }

    @Test
    fun getByteAfterReadByte() {
        val buf = createDefaultIoBuf(8)
        buf.writeByte(0x41)
        buf.writeByte(0x42)
        buf.readByte() // advance readerIndex to 1
        // getByte uses absolute index, not relative to readerIndex
        assertEquals(0x41.toByte(), buf.getByte(0))
        assertEquals(0x42.toByte(), buf.getByte(1))
        buf.release()
    }

    // --- copyTo ---

    @Test
    fun copyToBasic() {
        val src = createDefaultIoBuf(8)
        val dst = createDefaultIoBuf(8)
        src.writeBytes("Hello".encodeToByteArray(), 0, 5)
        src.copyTo(dst, 5)
        assertEquals(5, src.readerIndex)
        assertEquals(0, src.readableBytes)
        assertEquals(5, dst.writerIndex)
        assertEquals(5, dst.readableBytes)
        for (i in 0 until 5) {
            assertEquals("Hello".encodeToByteArray()[i], dst.readByte())
        }
        src.release()
        dst.release()
    }

    @Test
    fun copyToZeroBytes() {
        val src = createDefaultIoBuf(8)
        val dst = createDefaultIoBuf(8)
        src.writeByte(0x41)
        src.copyTo(dst, 0)
        assertEquals(0, src.readerIndex) // unchanged
        assertEquals(0, dst.writerIndex) // unchanged
        src.release()
        dst.release()
    }

    @Test
    fun copyToFullCapacity() {
        val src = createDefaultIoBuf(4)
        val dst = createDefaultIoBuf(4)
        src.writeBytes(byteArrayOf(1, 2, 3, 4), 0, 4)
        src.copyTo(dst, 4)
        assertEquals(4, dst.readableBytes)
        assertEquals(1.toByte(), dst.readByte())
        assertEquals(2.toByte(), dst.readByte())
        assertEquals(3.toByte(), dst.readByte())
        assertEquals(4.toByte(), dst.readByte())
        src.release()
        dst.release()
    }

    @Test
    fun copyToExceedsReadableThrows() {
        val src = createDefaultIoBuf(8)
        val dst = createDefaultIoBuf(8)
        src.writeByte(0x41) // 1 readable byte
        assertFailsWith<IllegalArgumentException> {
            src.copyTo(dst, 2) // request 2 bytes but only 1 available
        }
        src.release()
        dst.release()
    }

    @Test
    fun copyToExceedsWritableThrows() {
        val src = createDefaultIoBuf(8)
        val dst = createDefaultIoBuf(2)
        src.writeBytes(byteArrayOf(1, 2, 3, 4), 0, 4)
        assertFailsWith<IllegalArgumentException> {
            src.copyTo(dst, 4) // dest only has 2 writable bytes
        }
        src.release()
        dst.release()
    }

    @Test
    fun copyToPartialThenMore() {
        val src = createDefaultIoBuf(8)
        val dst = createDefaultIoBuf(8)
        src.writeBytes("ABCDEF".encodeToByteArray(), 0, 6)
        src.copyTo(dst, 3) // copy "ABC"
        assertEquals(3, src.readerIndex)
        assertEquals(3, dst.writerIndex)
        src.copyTo(dst, 3) // copy "DEF"
        assertEquals(6, src.readerIndex)
        assertEquals(6, dst.writerIndex)
        assertEquals('A'.code.toByte(), dst.getByte(0))
        assertEquals('F'.code.toByte(), dst.getByte(5))
        src.release()
        dst.release()
    }
}
