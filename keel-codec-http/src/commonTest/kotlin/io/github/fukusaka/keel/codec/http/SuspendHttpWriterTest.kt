package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.io.BufferedSuspendSink
import io.github.fukusaka.keel.io.BufferedSuspendSource
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.io.SuspendSink
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SuspendHttpWriterTest {

    private class CollectingSink : SuspendSink {
        val chunks = mutableListOf<ByteArray>()
        override suspend fun write(buf: IoBuf): Int {
            val bytes = ByteArray(buf.readableBytes)
            for (i in bytes.indices) bytes[i] = buf.readByte()
            chunks.add(bytes)
            return bytes.size
        }
        override suspend fun flush() {}
        override fun close() {}

        fun collected(): String = chunks.flatMap { it.toList() }
            .toByteArray().decodeToString()
    }

    @Test
    fun `writeResponseHead suspend variant writes status and headers`() = runBlocking {
        val sink = CollectingSink()
        val buffered = BufferedSuspendSink(sink, DefaultAllocator)

        val headers = HttpHeaders()
            .add("Content-Length", "13")
            .add("Content-Type", "text/plain")

        writeResponseHead(
            status = HttpStatus(200),
            version = HttpVersion.HTTP_1_1,
            headers = headers,
            sink = buffered,
        )
        buffered.flush()

        val output = sink.collected()
        assertTrue(output.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(output.contains("Content-Length: 13\r\n"))
        assertTrue(output.contains("Content-Type: text/plain\r\n"))
        assertTrue(output.endsWith("\r\n\r\n"))
        buffered.close()
    }

    @Test
    fun `writeResponseHead with no headers`() = runBlocking {
        val sink = CollectingSink()
        val buffered = BufferedSuspendSink(sink, DefaultAllocator)

        writeResponseHead(
            status = HttpStatus(204),
            version = HttpVersion.HTTP_1_1,
            headers = HttpHeaders(),
            sink = buffered,
        )
        buffered.flush()

        val output = sink.collected()
        assertEquals("HTTP/1.1 204 No Content\r\n\r\n", output)
        buffered.close()
    }

    @Test
    fun `writeResponseHead with HTTP 1_0`() = runBlocking {
        val sink = CollectingSink()
        val buffered = BufferedSuspendSink(sink, DefaultAllocator)

        val headers = HttpHeaders().add("Content-Length", "0")
        writeResponseHead(
            status = HttpStatus(200),
            version = HttpVersion.HTTP_1_0,
            headers = headers,
            sink = buffered,
        )
        buffered.flush()

        val output = sink.collected()
        assertTrue(output.startsWith("HTTP/1.0 200 OK\r\n"))
        assertTrue(output.contains("Content-Length: 0\r\n"))
        buffered.close()
    }

    @Test
    fun `writeResponseHead round-trip with parseResponseHead`() = runBlocking {
        val sink = CollectingSink()
        val buffered = BufferedSuspendSink(sink, DefaultAllocator)

        val headers = HttpHeaders()
            .add("Content-Type", "text/html")
            .add("X-Custom", "value")
        writeResponseHead(
            status = HttpStatus(200),
            version = HttpVersion.HTTP_1_1,
            headers = headers,
            sink = buffered,
        )
        buffered.flush()
        buffered.close()

        // Parse back from the written bytes
        val source = BufferedSuspendSource(
            byteSource(sink.collected().encodeToByteArray()),
            DefaultAllocator,
        )
        val parsed = parseResponseHead(source)
        assertEquals(200, parsed.status.code)
        assertEquals(HttpVersion.HTTP_1_1, parsed.version)
        assertEquals("text/html", parsed.headers["Content-Type"])
        assertEquals("value", parsed.headers["X-Custom"])
        source.close()
    }

    /** Creates a SuspendSource that reads from a ByteArray. */
    private fun byteSource(data: ByteArray): io.github.fukusaka.keel.io.SuspendSource =
        object : io.github.fukusaka.keel.io.SuspendSource {
            private var pos = 0
            override suspend fun read(buf: IoBuf): Int {
                if (pos >= data.size) return -1
                val n = minOf(data.size - pos, buf.writableBytes)
                for (i in 0 until n) buf.writeByte(data[pos++])
                return n
            }
            override fun close() {}
        }
}
