package io.github.fukusaka.keel.buf

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Cross-type dispatch tests for [DirectIoBuf.copyTo].
 *
 * The fast path (dest is [DirectIoBuf]) uses ByteBuffer-to-ByteBuffer
 * bulk copy. When the dest is a foreign [IoBuf] impl (engines may ship
 * their own, e.g. the Netty engine's `NettyByteBufIoBuf`), copyTo must
 * fall back to a portable ByteArray round-trip.
 */
class DirectIoBufCopyToTest {

    /** Minimal foreign [IoBuf] impl that does NOT share a backing [java.nio.ByteBuffer]. */
    private class ArrayBackedIoBuf(size: Int) : IoBuf {
        private val data = ByteArray(size)
        override val capacity: Int = size
        override var readerIndex: Int = 0
        override var writerIndex: Int = 0
        override val readableBytes: Int get() = writerIndex - readerIndex
        override val writableBytes: Int get() = capacity - writerIndex
        override val memoryOwner: IoBufMemoryOwner = HeapOwner
        override fun writeByte(value: Byte) { data[writerIndex++] = value }
        override fun writeByteArray(src: ByteArray, offset: Int, length: Int) {
            src.copyInto(data, writerIndex, offset, offset + length)
            writerIndex += length
        }
        override fun writeAscii(src: String, srcOffset: Int, length: Int) {
            for (i in 0 until length) data[writerIndex + i] = src[srcOffset + i].code.toByte()
            writerIndex += length
        }
        override fun readByte(): Byte = data[readerIndex++]
        override fun readByteArray(dest: ByteArray, offset: Int, length: Int) {
            data.copyInto(dest, offset, readerIndex, readerIndex + length)
            readerIndex += length
        }
        override fun getByte(index: Int): Byte = data[index]
        override fun copyTo(dest: IoBuf, length: Int) {
            val tmp = ByteArray(length)
            readByteArray(tmp, 0, length)
            dest.writeByteArray(tmp, 0, length)
        }
        override fun clear() {
            readerIndex = 0
            writerIndex = 0
        }
        override fun retain(): IoBuf = this
        override fun release(): Boolean = true
        override fun close() {}
    }

    @Test
    fun `copyTo falls back to byte-level path when dest is not DirectIoBuf`() {
        val src = DirectIoBuf(16)
        src.writeByteArray(byteArrayOf(1, 2, 3, 4, 5), 0, 5)
        val dest = ArrayBackedIoBuf(16)
        src.copyTo(dest, 5)
        assertEquals(5, src.readerIndex)
        assertEquals(5, dest.writerIndex)
        val out = ByteArray(5)
        dest.readByteArray(out, 0, 5)
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), out)
        src.release()
    }

    @Test
    fun `copyTo same-type preserves the ByteBuffer fast path`() {
        val src = DirectIoBuf(8)
        src.writeByteArray(byteArrayOf(9, 8, 7, 6), 0, 4)
        val dest = DirectIoBuf(8)
        src.copyTo(dest, 4)
        assertEquals(4, src.readerIndex)
        assertEquals(4, dest.writerIndex)
        val out = ByteArray(4)
        dest.readByteArray(out, 0, 4)
        assertContentEquals(byteArrayOf(9, 8, 7, 6), out)
        src.release()
        dest.release()
    }

    @Test
    fun `zero-length copyTo is a no-op`() {
        val src = DirectIoBuf(4)
        src.writeByte(1)
        val dest = ArrayBackedIoBuf(4)
        src.copyTo(dest, 0)
        assertEquals(0, src.readerIndex)
        assertEquals(0, dest.writerIndex)
        src.release()
    }
}
