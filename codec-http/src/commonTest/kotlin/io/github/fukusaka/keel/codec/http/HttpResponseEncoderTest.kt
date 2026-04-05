package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.pipeline.ChannelHandlerContext
import io.github.fukusaka.keel.pipeline.ChannelOutboundHandler
import io.github.fukusaka.keel.pipeline.ChannelPipeline
import io.github.fukusaka.keel.pipeline.DefaultChannelPipeline
import io.github.fukusaka.keel.pipeline.IoTransport
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.pipeline.SuspendBridgeHandler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HttpResponseEncoderTest {

    // --- Test infrastructure ---

    /** Captures IoBufs delivered to IoTransport.write (the final outbound hop). */
    private class CapturingTransport : IoTransport {
        val written = mutableListOf<IoBuf>()
        override fun write(buf: IoBuf) { written.add(buf) }
        override fun flush(): Boolean = true
        override var onFlushComplete: (() -> Unit)? = null
        override fun close() {}
    }

    private val transport = CapturingTransport()

    private val channel = object : PipelinedChannel {
        override lateinit var pipeline: ChannelPipeline
        override val isActive: Boolean = true
        override val isWritable: Boolean = true
        override val allocator: BufferAllocator get() = DefaultAllocator
        override fun ensureBridge(): SuspendBridgeHandler = error("not needed in tests")
    }

    private fun createPipeline(vararg extraHandlers: Pair<String, ChannelOutboundHandler>): ChannelPipeline {
        val pipeline = DefaultChannelPipeline(channel, transport, PrintLogger("test"))
        channel.pipeline = pipeline
        // Outbound handlers are visited in reverse (tail → head), so add last-to-first.
        for ((name, handler) in extraHandlers) pipeline.addLast(name, handler)
        return pipeline
    }

    private companion object {
        val CR: Byte = '\r'.code.toByte()
        val LF: Byte = '\n'.code.toByte()
    }

    /** Reads the content of [buf] from readerIndex to writerIndex as a String. */
    private fun IoBuf.readString(): String {
        val bytes = ByteArray(readableBytes)
        readByteArray(bytes, 0, bytes.size)
        return bytes.decodeToString()
    }

    // --- Status line ---

    @Test
    fun `200 OK status line is written correctly`() {
        val pipeline = createPipeline("encoder" to HttpResponseEncoder())
        pipeline.requestWrite(HttpResponse.ok())
        pipeline.requestFlush()

        assertEquals(1, transport.written.size)
        val text = transport.written[0].readString()
        assertTrue(text.startsWith("HTTP/1.1 200 OK\r\n"), "status line: $text")
    }

    @Test
    fun `404 Not Found status line is written correctly`() {
        val pipeline = createPipeline("encoder" to HttpResponseEncoder())
        pipeline.requestWrite(HttpResponse.notFound())
        pipeline.requestFlush()

        val text = transport.written[0].readString()
        assertTrue(text.startsWith("HTTP/1.1 404 Not Found\r\n"), "status line: $text")
    }

    @Test
    fun `500 Internal Server Error status line is written correctly`() {
        val pipeline = createPipeline("encoder" to HttpResponseEncoder())
        pipeline.requestWrite(HttpResponse(HttpStatus.INTERNAL_SERVER_ERROR))
        pipeline.requestFlush()

        val text = transport.written[0].readString()
        assertTrue(text.startsWith("HTTP/1.1 500 Internal Server Error\r\n"), "status line: $text")
    }

    // --- Headers ---

    @Test
    fun `headers are written in insertion order with CRLF`() {
        val pipeline = createPipeline("encoder" to HttpResponseEncoder())
        val response = HttpResponse(
            HttpStatus.OK,
            headers = HttpHeaders.of(
                "Content-Type" to "text/plain",
                "Content-Length" to "5",
            ),
        )
        pipeline.requestWrite(response)

        val text = transport.written[0].readString()
        assertTrue(text.contains("Content-Type: text/plain\r\n"), "headers: $text")
        assertTrue(text.contains("Content-Length: 5\r\n"), "headers: $text")
    }

    @Test
    fun `empty headers section ends with double CRLF`() {
        val pipeline = createPipeline("encoder" to HttpResponseEncoder())
        pipeline.requestWrite(HttpResponse(HttpStatus.NO_CONTENT))

        val text = transport.written[0].readString()
        assertTrue(text.endsWith("\r\n\r\n"), "should end with double CRLF: $text")
    }

    @Test
    fun `multi-valued header is written as separate lines`() {
        val pipeline = createPipeline("encoder" to HttpResponseEncoder())
        val headers = HttpHeaders().apply {
            add("Set-Cookie", "a=1")
            add("Set-Cookie", "b=2")
        }
        pipeline.requestWrite(HttpResponse(HttpStatus.OK, headers = headers))

        val text = transport.written[0].readString()
        assertTrue(text.contains("Set-Cookie: a=1\r\n"), "first cookie: $text")
        assertTrue(text.contains("Set-Cookie: b=2\r\n"), "second cookie: $text")
    }

    // --- Body ---

    @Test
    fun `response body bytes are appended after headers`() {
        val pipeline = createPipeline("encoder" to HttpResponseEncoder())
        pipeline.requestWrite(HttpResponse.ok("hello"))

        val text = transport.written[0].readString()
        assertTrue(text.endsWith("hello"), "body: $text")
    }

    @Test
    fun `null body produces no body bytes`() {
        val pipeline = createPipeline("encoder" to HttpResponseEncoder())
        pipeline.requestWrite(HttpResponse(HttpStatus.NO_CONTENT))

        val text = transport.written[0].readString()
        // Only status line + empty headers terminator, no extra bytes.
        assertEquals("HTTP/1.1 204 No Content\r\n\r\n", text)
    }

    @Test
    fun `binary body is written without corruption`() {
        val pipeline = createPipeline("encoder" to HttpResponseEncoder())
        val body = ByteArray(256) { it.toByte() }
        val headers = HttpHeaders.of("Content-Length" to body.size.toString())
        pipeline.requestWrite(HttpResponse(HttpStatus.OK, headers = headers, body = body))

        val buf = transport.written[0]
        // Read raw bytes directly — avoid String round-trip which corrupts bytes > 0x7F.
        val allBytes = ByteArray(buf.readableBytes)
        buf.readByteArray(allBytes, 0, allBytes.size)
        // Find end of headers: "\r\n\r\n"
        var headerEnd = -1
        for (i in 0..allBytes.size - 4) {
            if (allBytes[i] == CR && allBytes[i + 1] == LF && allBytes[i + 2] == CR && allBytes[i + 3] == LF) {
                headerEnd = i + 4
                break
            }
        }
        assertTrue(headerEnd >= 0, "header terminator not found")
        assertEquals(body.size, allBytes.size - headerEnd, "body size mismatch")
        for (i in body.indices) {
            assertEquals(body[i], allBytes[headerEnd + i], "byte[$i] mismatch")
        }
    }

    // --- Full response round-trip ---

    @Test
    fun `full response matches expected wire format`() {
        val pipeline = createPipeline("encoder" to HttpResponseEncoder())
        val response = HttpResponse(
            status = HttpStatus.OK,
            headers = HttpHeaders.of(
                "Content-Type" to "text/plain",
                "Content-Length" to "5",
                "Connection" to "keep-alive",
            ),
            body = "hello".encodeToByteArray(),
        )
        pipeline.requestWrite(response)

        val expected = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/plain\r\n" +
            "Content-Length: 5\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n" +
            "hello"
        assertEquals(expected, transport.written[0].readString())
    }

    // --- Pass-through ---

    @Test
    fun `non-HttpResponse message passes through unchanged`() {
        val pipeline = createPipeline("encoder" to HttpResponseEncoder())
        val rawBuf = DefaultAllocator.allocate(4)
        rawBuf.writeByte(1); rawBuf.writeByte(2); rawBuf.writeByte(3); rawBuf.writeByte(4)

        pipeline.requestWrite(rawBuf)

        assertEquals(1, transport.written.size)
        assertEquals(rawBuf, transport.written[0])
    }

    // --- IoBuf sizing: single allocation, no spare bytes ---

    @Test
    fun `encoded IoBuf has no spare writable bytes`() {
        val pipeline = createPipeline("encoder" to HttpResponseEncoder())
        pipeline.requestWrite(HttpResponse.ok("world"))

        val buf = transport.written[0]
        // After HeadHandler writes the buf, readerIndex == writerIndex == capacity.
        // We check capacity == readableBytes at write time via writerIndex == capacity.
        assertEquals(0, buf.writableBytes, "IoBuf should be exactly sized")
    }

    // --- HTTP version ---

    @Test
    fun `HTTP 1_0 response uses HTTP_1_0 status line`() {
        val pipeline = createPipeline("encoder" to HttpResponseEncoder())
        pipeline.requestWrite(
            HttpResponse(HttpStatus.OK, version = HttpVersion.HTTP_1_0)
        )

        val text = transport.written[0].readString()
        assertTrue(text.startsWith("HTTP/1.0 200 OK\r\n"), "version: $text")
    }
}
