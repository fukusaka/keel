package io.github.fukusaka.keel.io

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.IoBufView
import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.buf.createDefaultIoBuf
import kotlinx.coroutines.test.runTest
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
    fun readLineSimple() = runTest {
        val source = BufferedSuspendSource(sourceOf("hello\r\nworld\r\n"), DefaultAllocator)
        assertEquals("hello", source.readLine())
        assertEquals("world", source.readLine())
        assertNull(source.readLine())
        source.close()
    }

    @Test
    fun readLineLfOnly() = runTest {
        val source = BufferedSuspendSource(sourceOf("abc\ndef\n"), DefaultAllocator)
        assertEquals("abc", source.readLine())
        assertEquals("def", source.readLine())
        source.close()
    }

    @Test
    fun readLineEofWithoutNewline() = runTest {
        val source = BufferedSuspendSource(sourceOf("no-newline"), DefaultAllocator)
        assertEquals("no-newline", source.readLine())
        assertNull(source.readLine())
        source.close()
    }

    @Test
    fun readLineEmptySource() = runTest {
        val source = BufferedSuspendSource(sourceOf(""), DefaultAllocator)
        assertNull(source.readLine())
        source.close()
    }

    @Test
    fun readByte() = runTest {
        val source = BufferedSuspendSource(sourceOf("AB"), DefaultAllocator)
        assertEquals('A'.code.toByte(), source.readByte())
        assertEquals('B'.code.toByte(), source.readByte())
        source.close()
    }

    @Test
    fun readByteEofThrows() = runTest {
        val source = BufferedSuspendSource(sourceOf(""), DefaultAllocator)
        assertFailsWith<KeelEofException> { source.readByte() }
        source.close()
    }

    @Test
    fun readByteArray() = runTest {
        val source = BufferedSuspendSource(sourceOf("hello"), DefaultAllocator)
        val bytes = source.readByteArray(5)
        assertEquals("hello", bytes.decodeToString())
        source.close()
    }

    @Test
    fun readByteArrayEofThrows() = runTest {
        val source = BufferedSuspendSource(sourceOf("hi"), DefaultAllocator)
        assertFailsWith<KeelEofException> { source.readByteArray(5) }
        source.close()
    }

    @Test
    fun readAtMostTo() = runTest {
        val source = BufferedSuspendSource(sourceOf("data"), DefaultAllocator)
        val dest = ByteArray(10)
        val n = source.readAtMostTo(dest, 0, 10)
        assertEquals(4, n)
        assertEquals("data", dest.decodeToString(0, n))
        source.close()
    }

    @Test
    fun readAtMostToEof() = runTest {
        val source = BufferedSuspendSource(sourceOf(""), DefaultAllocator)
        val dest = ByteArray(10)
        assertEquals(-1, source.readAtMostTo(dest, 0, 10))
        source.close()
    }

    // -- scanLine --

    @Test
    fun scanLineSimple() = runTest {
        val source = BufferedSuspendSource(sourceOf("hello\r\nworld\r\n"), DefaultAllocator)
        assertEquals("hello", source.scanLine()?.decodeToString())
        assertEquals("world", source.scanLine()?.decodeToString())
        assertNull(source.scanLine())
        source.close()
    }

    @Test
    fun scanLineLfOnly() = runTest {
        val source = BufferedSuspendSource(sourceOf("abc\ndef\n"), DefaultAllocator)
        assertEquals("abc", source.scanLine()?.decodeToString())
        assertEquals("def", source.scanLine()?.decodeToString())
        source.close()
    }

    @Test
    fun scanLineEofWithoutNewline() = runTest {
        val source = BufferedSuspendSource(sourceOf("no-newline"), DefaultAllocator)
        assertEquals("no-newline", source.scanLine()?.decodeToString())
        assertNull(source.scanLine())
        source.close()
    }

    @Test
    fun scanLineEmptySource() = runTest {
        val source = BufferedSuspendSource(sourceOf(""), DefaultAllocator)
        assertNull(source.scanLine())
        source.close()
    }

    @Test
    fun scanLineReturnsZeroCopySlice() = runTest {
        val source = BufferedSuspendSource(sourceOf("GET /hello HTTP/1.1\r\n"), DefaultAllocator)
        val slice = source.scanLine()!!
        // Verify it's a real IoBufView, not a copy
        assertTrue(slice.contentEquals("GET /hello HTTP/1.1"))
        assertEquals(19, slice.length) // "GET /hello HTTP/1.1" = 19 bytes
        source.close()
    }

    @Test
    fun scanLineEmptyLine() = runTest {
        val source = BufferedSuspendSource(sourceOf("first\r\n\r\n"), DefaultAllocator)
        assertEquals("first", source.scanLine()?.decodeToString())
        val empty = source.scanLine()!!
        assertEquals(0, empty.length)
        assertTrue(empty.isEmpty())
        source.close()
    }

    // ============================================================
    // Pull-mode: buffer boundary tests
    // ============================================================

    /** Creates a SuspendSource that delivers [data] in fixed-size chunks. */
    private fun chunkedSourceOf(data: String, chunkSize: Int): SuspendSource = object : SuspendSource {
        private val bytes = data.encodeToByteArray()
        private var pos = 0
        override suspend fun read(buf: IoBuf): Int {
            if (pos >= bytes.size) return -1
            val n = minOf(chunkSize, bytes.size - pos, buf.writableBytes)
            for (i in 0 until n) buf.writeByte(bytes[pos++])
            return n
        }
        override fun close() {}
    }

    @Test
    fun pullMode_scanLine_lineAtBufferBoundary() = runTest {
        // Line exactly fills BUFFER_SIZE (8192). The LF is at the buffer boundary.
        val line = "x".repeat(8191) + "\n"
        val source = BufferedSuspendSource(sourceOf(line), DefaultAllocator)
        val result = source.scanLine()?.decodeToString()
        assertEquals("x".repeat(8191), result)
        assertNull(source.scanLine())
        source.close()
    }

    @Test
    fun pullMode_scanLine_secondLineSpansRefill() = runTest {
        // First line consumes most of the first refill buffer. The second
        // line starts near the buffer end and continues into the next
        // refill, exercising the cross-buffer IoBufView path in pull mode.
        val line1 = "A".repeat(8000) + "\n"
        val line2 = "B".repeat(500) + "\n"
        val source = BufferedSuspendSource(sourceOf(line1 + line2), DefaultAllocator)
        assertEquals("A".repeat(8000), source.scanLine()?.decodeToString())
        assertEquals("B".repeat(500), source.scanLine()?.decodeToString())
        assertNull(source.scanLine())
        source.close()
    }

    @Test
    fun pullMode_scanLine_crossBuffer_multiSegment() = runTest {
        // A single line longer than BUFFER_SIZE (8192): the LF lands in the
        // second refill buffer, so scanLine returns a multi-segment IoBufView.
        val line = "C".repeat(10000) + "\r\n"
        val source = BufferedSuspendSource(sourceOf(line), DefaultAllocator)
        val slice = source.scanLine()
        assertNotNull(slice)
        assertEquals("C".repeat(10000), slice.decodeToString())
        assertEquals(10000, slice.totalLength)
        assertNotNull(slice.next) // spans two refill buffers
        assertNull(source.scanLine())
        source.close()
    }

    @Test
    fun pullMode_scanLine_crossBuffer_crAtBoundary() = runTest {
        // CR is the last byte of the first refill buffer (8192 bytes),
        // LF is the first byte of the next refill buffer.
        val line = "z".repeat(8191) + "\r\n"
        val source = BufferedSuspendSource(sourceOf(line), DefaultAllocator)
        assertEquals("z".repeat(8191), source.scanLine()?.decodeToString())
        assertNull(source.scanLine())
        source.close()
    }

    @Test
    fun pullMode_releasesAllRefillBuffersAfterConsume() = runTest {
        // Consuming a multi-refill stream and closing must leave no
        // outstanding refill buffers — each drained buffer is released
        // back to the allocator, none are leaked.
        val tracker = TrackingAllocator(DefaultAllocator)
        val source = BufferedSuspendSource(chunkedSourceOf("D".repeat(20000), 4096), tracker)
        val result = source.readByteArray(20000)
        assertEquals(20000, result.size)
        source.close()
        assertTrue(tracker.allocateCount >= 2, "expected multiple refills, got ${tracker.allocateCount}")
        tracker.assertNoLeaks()
    }

    @Test
    fun pullMode_readLine_largerThanBuffer() = runTest {
        // Line larger than BUFFER_SIZE (8192) — requires multiple refills.
        val line = "y".repeat(20000) + "\n"
        val source = BufferedSuspendSource(sourceOf(line), DefaultAllocator)
        assertEquals("y".repeat(20000), source.readLine())
        assertNull(source.readLine())
        source.close()
    }

    @Test
    fun pullMode_readByteArray_acrossRefill() = runTest {
        // Request more bytes than initial buffer fill provides.
        val data = "Z".repeat(100)
        val source = BufferedSuspendSource(chunkedSourceOf(data, 30), DefaultAllocator)
        val result = source.readByteArray(100)
        assertEquals(data, result.decodeToString())
        source.close()
    }

    // ============================================================
    // Push-mode tests
    // ============================================================

    /** Creates a OwnedSuspendSource that delivers each string as a separate IoBuf. */
    private fun pushSourceOf(vararg chunks: String): OwnedSuspendSource {
        val buffers = chunks.map { chunk ->
            val bytes = chunk.encodeToByteArray()
            val buf = createDefaultIoBuf(bytes.size)
            buf.writeByteArray(bytes, 0, bytes.size)
            buf
        }.toMutableList()
        return object : OwnedSuspendSource {
            override suspend fun readOwned(): IoBuf? = buffers.removeFirstOrNull()
            override fun close() { buffers.forEach { it.release() } }
        }
    }

    @Test
    fun pushMode_readLine() = runTest {
        val source = BufferedSuspendSource(pushSourceOf("hello\r\nworld\r\n"))
        assertEquals("hello", source.readLine())
        assertEquals("world", source.readLine())
        assertNull(source.readLine())
        source.close()
    }

    @Test
    fun pushMode_readByte() = runTest {
        val source = BufferedSuspendSource(pushSourceOf("AB"))
        assertEquals('A'.code.toByte(), source.readByte())
        assertEquals('B'.code.toByte(), source.readByte())
        assertFailsWith<KeelEofException> { source.readByte() }
        source.close()
    }

    @Test
    fun pushMode_readByteAcrossChunks() = runTest {
        val source = BufferedSuspendSource(pushSourceOf("A", "B", "C"))
        assertEquals('A'.code.toByte(), source.readByte())
        assertEquals('B'.code.toByte(), source.readByte())
        assertEquals('C'.code.toByte(), source.readByte())
        source.close()
    }

    @Test
    fun pushMode_scanLine_singleBuffer() = runTest {
        val source = BufferedSuspendSource(pushSourceOf("GET /hello HTTP/1.1\r\n"))
        val slice = source.scanLine()
        assertNotNull(slice)
        assertTrue(slice.contentEquals("GET /hello HTTP/1.1"))
        assertNull(slice.next) // single segment
        source.close()
    }

    @Test
    fun pushMode_scanLine_crossBuffer() = runTest {
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
    fun pushMode_scanLine_crossBuffer_crAtBoundary() = runTest {
        // CR at end of first chunk, LF at start of second
        val source = BufferedSuspendSource(pushSourceOf("Header\r", "\nBody\r\n"))
        val slice = source.scanLine()
        assertNotNull(slice)
        assertEquals("Header", slice.decodeToString())
        assertEquals("Body", source.scanLine()?.decodeToString())
        source.close()
    }

    @Test
    fun pushMode_scanLine_eofWithoutNewline() = runTest {
        val source = BufferedSuspendSource(pushSourceOf("no-newline"))
        assertEquals("no-newline", source.scanLine()?.decodeToString())
        assertNull(source.scanLine())
        source.close()
    }

    @Test
    fun pushMode_readByteArray() = runTest {
        val source = BufferedSuspendSource(pushSourceOf("ABCD", "EF"))
        val result = source.readByteArray(6)
        assertEquals("ABCDEF", result.decodeToString())
        source.close()
    }

    @Test
    fun pushMode_readAtMostTo() = runTest {
        val source = BufferedSuspendSource(pushSourceOf("hello"))
        val dest = ByteArray(10)
        val n = source.readAtMostTo(dest, 0, 10)
        assertEquals(5, n)
        assertEquals("hello", dest.copyOfRange(0, n).decodeToString())
        source.close()
    }

    @Test
    fun pushMode_readAtMostToEof() = runTest {
        val source = BufferedSuspendSource(pushSourceOf())
        val dest = ByteArray(10)
        assertEquals(-1, source.readAtMostTo(dest, 0, 10))
        source.close()
    }

    @Test
    fun pushMode_readLineAcrossChunks() = runTest {
        // Line spans two chunks
        val source = BufferedSuspendSource(pushSourceOf("hel", "lo\r\n"))
        assertEquals("hello", source.readLine())
        source.close()
    }

    @Test
    fun pushMode_multipleLines() = runTest {
        val source = BufferedSuspendSource(pushSourceOf("line1\r\nline2\r\n", "line3\r\n"))
        assertEquals("line1", source.readLine())
        assertEquals("line2", source.readLine())
        assertEquals("line3", source.readLine())
        assertNull(source.readLine())
        source.close()
    }
}
