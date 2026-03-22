package io.github.fukusaka.keel.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class BufferedSuspendSourceTest {

    /** Creates a SuspendSource that delivers [data] in one read. */
    private fun sourceOf(data: String): SuspendSource = object : SuspendSource {
        private val bytes = data.encodeToByteArray()
        private var pos = 0
        override suspend fun read(buf: NativeBuf): Int {
            if (pos >= bytes.size) return -1
            val n = minOf(bytes.size - pos, buf.writableBytes)
            for (i in 0 until n) buf.writeByte(bytes[pos++])
            return n
        }
        override fun close() {}
    }

    @Test
    fun readLineSimple() = runBlocking {
        val source = BufferedSuspendSource(sourceOf("hello\r\nworld\r\n"), HeapAllocator)
        assertEquals("hello", source.readLine())
        assertEquals("world", source.readLine())
        assertNull(source.readLine())
        source.close()
    }

    @Test
    fun readLineLfOnly() = runBlocking {
        val source = BufferedSuspendSource(sourceOf("abc\ndef\n"), HeapAllocator)
        assertEquals("abc", source.readLine())
        assertEquals("def", source.readLine())
        source.close()
    }

    @Test
    fun readLineEofWithoutNewline() = runBlocking {
        val source = BufferedSuspendSource(sourceOf("no-newline"), HeapAllocator)
        assertEquals("no-newline", source.readLine())
        assertNull(source.readLine())
        source.close()
    }

    @Test
    fun readLineEmptySource() = runBlocking {
        val source = BufferedSuspendSource(sourceOf(""), HeapAllocator)
        assertNull(source.readLine())
        source.close()
    }

    @Test
    fun readByte() = runBlocking {
        val source = BufferedSuspendSource(sourceOf("AB"), HeapAllocator)
        assertEquals('A'.code.toByte(), source.readByte())
        assertEquals('B'.code.toByte(), source.readByte())
        source.close()
    }

    @Test
    fun readByteEofThrows() = runBlocking {
        val source = BufferedSuspendSource(sourceOf(""), HeapAllocator)
        assertFailsWith<IllegalStateException> { source.readByte() }
        source.close()
    }

    @Test
    fun readByteArray() = runBlocking {
        val source = BufferedSuspendSource(sourceOf("hello"), HeapAllocator)
        val bytes = source.readByteArray(5)
        assertEquals("hello", bytes.decodeToString())
        source.close()
    }

    @Test
    fun readByteArrayEofThrows() = runBlocking {
        val source = BufferedSuspendSource(sourceOf("hi"), HeapAllocator)
        assertFailsWith<IllegalStateException> { source.readByteArray(5) }
        source.close()
    }

    @Test
    fun readAtMostTo() = runBlocking {
        val source = BufferedSuspendSource(sourceOf("data"), HeapAllocator)
        val dest = ByteArray(10)
        val n = source.readAtMostTo(dest, 0, 10)
        assertEquals(4, n)
        assertEquals("data", dest.decodeToString(0, n))
        source.close()
    }

    @Test
    fun readAtMostToEof() = runBlocking {
        val source = BufferedSuspendSource(sourceOf(""), HeapAllocator)
        val dest = ByteArray(10)
        assertEquals(-1, source.readAtMostTo(dest, 0, 10))
        source.close()
    }
}
