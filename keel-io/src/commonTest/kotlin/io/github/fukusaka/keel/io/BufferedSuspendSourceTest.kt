package io.github.fukusaka.keel.io

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.buf.createDefaultIoBuf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class BufferedSuspendSourceTest {

    /** Creates a SuspendSource that delivers [data] in one read. */
    private fun sourceOf(data: String): SuspendSource = object : SuspendSource {
        private val bytes = data.encodeToByteArray()
        private var pos = 0
        override suspend fun read(buf: IoBuf): Int {
            if (pos >= bytes.size) return -1
            val n = minOf(bytes.size - pos, buf.writableBytes)
            repeat(n) { buf.writeByte(bytes[pos++]) }
            return n
        }
        override fun close() {}
    }

    @Test
    fun readLineSimple() = runTest(timeout = 15.seconds) {
        val source = BufferedSuspendSource(sourceOf("hello\r\nworld\r\n"), DefaultAllocator)
        assertEquals("hello", source.readLine())
        assertEquals("world", source.readLine())
        assertNull(source.readLine())
        source.close()
    }

    @Test
    fun readLineLfOnly() = runTest(timeout = 15.seconds) {
        val source = BufferedSuspendSource(sourceOf("abc\ndef\n"), DefaultAllocator)
        assertEquals("abc", source.readLine())
        assertEquals("def", source.readLine())
        source.close()
    }

    @Test
    fun readLineEofWithoutNewline() = runTest(timeout = 15.seconds) {
        val source = BufferedSuspendSource(sourceOf("no-newline"), DefaultAllocator)
        assertEquals("no-newline", source.readLine())
        assertNull(source.readLine())
        source.close()
    }

    @Test
    fun readLineEmptySource() = runTest(timeout = 15.seconds) {
        val source = BufferedSuspendSource(sourceOf(""), DefaultAllocator)
        assertNull(source.readLine())
        source.close()
    }

    @Test
    fun readByte() = runTest(timeout = 15.seconds) {
        val source = BufferedSuspendSource(sourceOf("AB"), DefaultAllocator)
        assertEquals('A'.code.toByte(), source.readByte())
        assertEquals('B'.code.toByte(), source.readByte())
        source.close()
    }

    @Test
    fun readByteEofThrows() = runTest(timeout = 15.seconds) {
        val source = BufferedSuspendSource(sourceOf(""), DefaultAllocator)
        assertFailsWith<KeelEofException> { source.readByte() }
        source.close()
    }

    @Test
    fun readByteArray() = runTest(timeout = 15.seconds) {
        val source = BufferedSuspendSource(sourceOf("hello"), DefaultAllocator)
        val bytes = source.readByteArray(5)
        assertEquals("hello", bytes.decodeToString())
        source.close()
    }

    @Test
    fun readByteArrayEofThrows() = runTest(timeout = 15.seconds) {
        val source = BufferedSuspendSource(sourceOf("hi"), DefaultAllocator)
        assertFailsWith<KeelEofException> { source.readByteArray(5) }
        source.close()
    }

    @Test
    fun readAtMostTo() = runTest(timeout = 15.seconds) {
        val source = BufferedSuspendSource(sourceOf("data"), DefaultAllocator)
        val dest = ByteArray(10)
        val n = source.readAtMostTo(dest, 0, 10)
        assertEquals(4, n)
        assertEquals("data", dest.decodeToString(0, n))
        source.close()
    }

    @Test
    fun readAtMostToEof() = runTest(timeout = 15.seconds) {
        val source = BufferedSuspendSource(sourceOf(""), DefaultAllocator)
        val dest = ByteArray(10)
        assertEquals(-1, source.readAtMostTo(dest, 0, 10))
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
            repeat(n) { buf.writeByte(bytes[pos++]) }
            return n
        }
        override fun close() {}
    }

    @Test
    fun pullMode_releasesAllRefillBuffersAfterConsume() = runTest(timeout = 15.seconds) {
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
    fun pullMode_readLine_largerThanBuffer() = runTest(timeout = 15.seconds) {
        // Line larger than BUFFER_SIZE (8192) — requires multiple refills.
        val line = "y".repeat(20000) + "\n"
        val source = BufferedSuspendSource(sourceOf(line), DefaultAllocator)
        assertEquals("y".repeat(20000), source.readLine())
        assertNull(source.readLine())
        source.close()
    }

    @Test
    fun pullMode_readByteArray_acrossRefill() = runTest(timeout = 15.seconds) {
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
    fun pushMode_readLine() = runTest(timeout = 15.seconds) {
        val source = BufferedSuspendSource(pushSourceOf("hello\r\nworld\r\n"))
        assertEquals("hello", source.readLine())
        assertEquals("world", source.readLine())
        assertNull(source.readLine())
        source.close()
    }

    @Test
    fun pushMode_readByte() = runTest(timeout = 15.seconds) {
        val source = BufferedSuspendSource(pushSourceOf("AB"))
        assertEquals('A'.code.toByte(), source.readByte())
        assertEquals('B'.code.toByte(), source.readByte())
        assertFailsWith<KeelEofException> { source.readByte() }
        source.close()
    }

    @Test
    fun pushMode_readByteAcrossChunks() = runTest(timeout = 15.seconds) {
        val source = BufferedSuspendSource(pushSourceOf("A", "B", "C"))
        assertEquals('A'.code.toByte(), source.readByte())
        assertEquals('B'.code.toByte(), source.readByte())
        assertEquals('C'.code.toByte(), source.readByte())
        source.close()
    }

    @Test
    fun pushMode_readByteArray() = runTest(timeout = 15.seconds) {
        val source = BufferedSuspendSource(pushSourceOf("ABCD", "EF"))
        val result = source.readByteArray(6)
        assertEquals("ABCDEF", result.decodeToString())
        source.close()
    }

    @Test
    fun pushMode_readAtMostTo() = runTest(timeout = 15.seconds) {
        val source = BufferedSuspendSource(pushSourceOf("hello"))
        val dest = ByteArray(10)
        val n = source.readAtMostTo(dest, 0, 10)
        assertEquals(5, n)
        assertEquals("hello", dest.copyOfRange(0, n).decodeToString())
        source.close()
    }

    @Test
    fun pushMode_readAtMostToEof() = runTest(timeout = 15.seconds) {
        val source = BufferedSuspendSource(pushSourceOf())
        val dest = ByteArray(10)
        assertEquals(-1, source.readAtMostTo(dest, 0, 10))
        source.close()
    }

    @Test
    fun pushMode_readLineAcrossChunks() = runTest(timeout = 15.seconds) {
        // Line spans two chunks
        val source = BufferedSuspendSource(pushSourceOf("hel", "lo\r\n"))
        assertEquals("hello", source.readLine())
        source.close()
    }

    @Test
    fun pushMode_multipleLines() = runTest(timeout = 15.seconds) {
        val source = BufferedSuspendSource(pushSourceOf("line1\r\nline2\r\n", "line3\r\n"))
        assertEquals("line1", source.readLine())
        assertEquals("line2", source.readLine())
        assertEquals("line3", source.readLine())
        assertNull(source.readLine())
        source.close()
    }
}
