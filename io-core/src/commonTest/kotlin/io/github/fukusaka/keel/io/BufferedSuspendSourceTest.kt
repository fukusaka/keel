package io.github.fukusaka.keel.io

import io.github.fukusaka.keel.buf.BufSlice
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.createDefaultIoBuf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BufferedSuspendSourceTest {

    /** Creates a SuspendSource that delivers [data] in one read. */
    private fun sourceOf(data: String): SuspendSource = object : SuspendSource {
        private val bytes = data.encodeToByteArray()
        private var pos = 0
        override suspend fun read(buf: IoBuf): Int {
            if (pos >= bytes.size) return -1
            val n = minOf(bytes.size - pos, buf.writableBytes)
            for (i in 0 until n) buf.writeByte(bytes[pos++])
            return n
        }
        override fun close() {}
    }

    @Test
    fun readLineSimple() = runBlocking {
        val source = BufferedSuspendSource(sourceOf("hello\r\nworld\r\n"), DefaultAllocator)
        assertEquals("hello", source.readLine())
        assertEquals("world", source.readLine())
        assertNull(source.readLine())
        source.close()
    }

    @Test
    fun readLineLfOnly() = runBlocking {
        val source = BufferedSuspendSource(sourceOf("abc\ndef\n"), DefaultAllocator)
        assertEquals("abc", source.readLine())
        assertEquals("def", source.readLine())
        source.close()
    }

    @Test
    fun readLineEofWithoutNewline() = runBlocking {
        val source = BufferedSuspendSource(sourceOf("no-newline"), DefaultAllocator)
        assertEquals("no-newline", source.readLine())
        assertNull(source.readLine())
        source.close()
    }

    @Test
    fun readLineEmptySource() = runBlocking {
        val source = BufferedSuspendSource(sourceOf(""), DefaultAllocator)
        assertNull(source.readLine())
        source.close()
    }

    @Test
    fun readByte() = runBlocking {
        val source = BufferedSuspendSource(sourceOf("AB"), DefaultAllocator)
        assertEquals('A'.code.toByte(), source.readByte())
        assertEquals('B'.code.toByte(), source.readByte())
        source.close()
    }

    @Test
    fun readByteEofThrows() = runBlocking {
        val source = BufferedSuspendSource(sourceOf(""), DefaultAllocator)
        assertFailsWith<KeelEofException> { source.readByte() }
        source.close()
    }

    @Test
    fun readByteArray() = runBlocking {
        val source = BufferedSuspendSource(sourceOf("hello"), DefaultAllocator)
        val bytes = source.readByteArray(5)
        assertEquals("hello", bytes.decodeToString())
        source.close()
    }

    @Test
    fun readByteArrayEofThrows() = runBlocking {
        val source = BufferedSuspendSource(sourceOf("hi"), DefaultAllocator)
        assertFailsWith<KeelEofException> { source.readByteArray(5) }
        source.close()
    }

    @Test
    fun readAtMostTo() = runBlocking {
        val source = BufferedSuspendSource(sourceOf("data"), DefaultAllocator)
        val dest = ByteArray(10)
        val n = source.readAtMostTo(dest, 0, 10)
        assertEquals(4, n)
        assertEquals("data", dest.decodeToString(0, n))
        source.close()
    }

    @Test
    fun readAtMostToEof() = runBlocking {
        val source = BufferedSuspendSource(sourceOf(""), DefaultAllocator)
        val dest = ByteArray(10)
        assertEquals(-1, source.readAtMostTo(dest, 0, 10))
        source.close()
    }

    // -- scanLine --

    @Test
    fun scanLineSimple() = runBlocking {
        val source = BufferedSuspendSource(sourceOf("hello\r\nworld\r\n"), DefaultAllocator)
        assertEquals("hello", source.scanLine()?.decodeToString())
        assertEquals("world", source.scanLine()?.decodeToString())
        assertNull(source.scanLine())
        source.close()
    }

    @Test
    fun scanLineLfOnly() = runBlocking {
        val source = BufferedSuspendSource(sourceOf("abc\ndef\n"), DefaultAllocator)
        assertEquals("abc", source.scanLine()?.decodeToString())
        assertEquals("def", source.scanLine()?.decodeToString())
        source.close()
    }

    @Test
    fun scanLineEofWithoutNewline() = runBlocking {
        val source = BufferedSuspendSource(sourceOf("no-newline"), DefaultAllocator)
        assertEquals("no-newline", source.scanLine()?.decodeToString())
        assertNull(source.scanLine())
        source.close()
    }

    @Test
    fun scanLineEmptySource() = runBlocking {
        val source = BufferedSuspendSource(sourceOf(""), DefaultAllocator)
        assertNull(source.scanLine())
        source.close()
    }

    @Test
    fun scanLineReturnsZeroCopySlice() = runBlocking {
        val source = BufferedSuspendSource(sourceOf("GET /hello HTTP/1.1\r\n"), DefaultAllocator)
        val slice = source.scanLine()!!
        // Verify it's a real BufSlice, not a copy
        assertTrue(slice.contentEquals("GET /hello HTTP/1.1"))
        assertEquals(19, slice.length) // "GET /hello HTTP/1.1" = 19 bytes
        source.close()
    }

    @Test
    fun scanLineEmptyLine() = runBlocking {
        val source = BufferedSuspendSource(sourceOf("first\r\n\r\n"), DefaultAllocator)
        assertEquals("first", source.scanLine()?.decodeToString())
        val empty = source.scanLine()!!
        assertEquals(0, empty.length)
        assertTrue(empty.isEmpty())
        source.close()
    }

    // ============================================================
    // Push-mode tests
    // ============================================================

    /** Creates a PushSuspendSource that delivers each string as a separate IoBuf. */
    private fun pushSourceOf(vararg chunks: String): PushSuspendSource {
        val buffers = chunks.map { chunk ->
            val bytes = chunk.encodeToByteArray()
            val buf = createDefaultIoBuf(bytes.size)
            buf.writeBytes(bytes, 0, bytes.size)
            buf
        }.toMutableList()
        return object : PushSuspendSource {
            override suspend fun readOwned(): IoBuf? = buffers.removeFirstOrNull()
            override fun close() { buffers.forEach { it.release() } }
        }
    }

    @Test
    fun pushMode_readLine() = runBlocking {
        val source = BufferedSuspendSource(pushSourceOf("hello\r\nworld\r\n"))
        assertEquals("hello", source.readLine())
        assertEquals("world", source.readLine())
        assertNull(source.readLine())
        source.close()
    }

    @Test
    fun pushMode_readByte() = runBlocking {
        val source = BufferedSuspendSource(pushSourceOf("AB"))
        assertEquals('A'.code.toByte(), source.readByte())
        assertEquals('B'.code.toByte(), source.readByte())
        assertFailsWith<KeelEofException> { source.readByte() }
        source.close()
    }

    @Test
    fun pushMode_readByteAcrossChunks() = runBlocking {
        val source = BufferedSuspendSource(pushSourceOf("A", "B", "C"))
        assertEquals('A'.code.toByte(), source.readByte())
        assertEquals('B'.code.toByte(), source.readByte())
        assertEquals('C'.code.toByte(), source.readByte())
        source.close()
    }

    @Test
    fun pushMode_scanLine_singleBuffer() = runBlocking {
        val source = BufferedSuspendSource(pushSourceOf("GET /hello HTTP/1.1\r\n"))
        val slice = source.scanLine()
        assertNotNull(slice)
        assertTrue(slice.contentEquals("GET /hello HTTP/1.1"))
        assertNull(slice.next) // single segment
        source.close()
    }

    @Test
    fun pushMode_scanLine_crossBuffer() = runBlocking {
        // Line "Hello-World" spans two chunks: "Hello-" and "World\r\n"
        val source = BufferedSuspendSource(pushSourceOf("Hello-", "World\r\n"))
        val slice = source.scanLine()
        assertNotNull(slice)
        assertEquals("Hello-World", slice.decodeToString())
        assertEquals(11, slice.totalLength)
        // Multi-segment: first="Hello-", next="World"
        assertNotNull(slice.next)
        source.close()
    }

    @Test
    fun pushMode_scanLine_crossBuffer_crAtBoundary() = runBlocking {
        // CR at end of first chunk, LF at start of second
        val source = BufferedSuspendSource(pushSourceOf("Header\r", "\nBody\r\n"))
        val slice = source.scanLine()
        assertNotNull(slice)
        assertEquals("Header", slice.decodeToString())
        assertEquals("Body", source.scanLine()?.decodeToString())
        source.close()
    }

    @Test
    fun pushMode_scanLine_eofWithoutNewline() = runBlocking {
        val source = BufferedSuspendSource(pushSourceOf("no-newline"))
        assertEquals("no-newline", source.scanLine()?.decodeToString())
        assertNull(source.scanLine())
        source.close()
    }

    @Test
    fun pushMode_readByteArray() = runBlocking {
        val source = BufferedSuspendSource(pushSourceOf("ABCD", "EF"))
        val result = source.readByteArray(6)
        assertEquals("ABCDEF", result.decodeToString())
        source.close()
    }

    @Test
    fun pushMode_readAtMostTo() = runBlocking {
        val source = BufferedSuspendSource(pushSourceOf("hello"))
        val dest = ByteArray(10)
        val n = source.readAtMostTo(dest, 0, 10)
        assertEquals(5, n)
        assertEquals("hello", dest.copyOfRange(0, n).decodeToString())
        source.close()
    }

    @Test
    fun pushMode_readAtMostToEof() = runBlocking {
        val source = BufferedSuspendSource(pushSourceOf())
        val dest = ByteArray(10)
        assertEquals(-1, source.readAtMostTo(dest, 0, 10))
        source.close()
    }

    @Test
    fun pushMode_readLineAcrossChunks() = runBlocking {
        // Line spans two chunks
        val source = BufferedSuspendSource(pushSourceOf("hel", "lo\r\n"))
        assertEquals("hello", source.readLine())
        source.close()
    }

    @Test
    fun pushMode_multipleLines() = runBlocking {
        val source = BufferedSuspendSource(pushSourceOf("line1\r\nline2\r\n", "line3\r\n"))
        assertEquals("line1", source.readLine())
        assertEquals("line2", source.readLine())
        assertEquals("line3", source.readLine())
        assertNull(source.readLine())
        source.close()
    }
}
