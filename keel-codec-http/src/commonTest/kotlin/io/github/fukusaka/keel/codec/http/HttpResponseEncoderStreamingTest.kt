package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.pipeline.ChannelPipeline
import io.github.fukusaka.keel.pipeline.DefaultChannelPipeline
import io.github.fukusaka.keel.pipeline.IoTransport
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.pipeline.SuspendBridgeHandler
import io.github.fukusaka.keel.pipeline.ChannelHandlerContext
import io.github.fukusaka.keel.pipeline.ChannelInboundHandler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HttpResponseEncoderStreamingTest {

    // --- Test infrastructure ---

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

    /** Collects errors propagated through the pipeline. */
    private class ErrorCollector : ChannelInboundHandler {
        val errors = mutableListOf<Throwable>()
        override fun onRead(ctx: ChannelHandlerContext, msg: Any) {}
        override fun onError(ctx: ChannelHandlerContext, cause: Throwable) {
            errors.add(cause)
        }
    }

    private val errorCollector = ErrorCollector()

    private fun createEncoderPipeline(): ChannelPipeline {
        val pipeline = DefaultChannelPipeline(channel, transport, PrintLogger("test"))
        channel.pipeline = pipeline
        pipeline.addLast("encoder", HttpResponseEncoder())
        pipeline.addLast("errors", errorCollector)
        return pipeline
    }

    private fun IoBuf.readString(): String {
        val bytes = ByteArray(readableBytes)
        readByteArray(bytes, 0, bytes.size)
        return bytes.decodeToString()
    }

    private fun bufOf(text: String): IoBuf {
        val bytes = text.encodeToByteArray()
        val buf = DefaultAllocator.allocate(bytes.size)
        buf.writeByteArray(bytes, 0, bytes.size)
        return buf
    }

    // --- Legacy path ---

    @Test
    fun `legacy HttpResponse path still produces head plus body single write`() {
        val pipeline = createEncoderPipeline()
        val response = HttpResponse(
            status = HttpStatus.OK,
            headers = HttpHeaders.of("Content-Length" to "5"),
            body = "hello".encodeToByteArray(),
        )
        pipeline.writeFromTail(response)

        assertEquals(1, transport.written.size)
        val text = transport.written[0].readString()
        assertTrue(text.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(text.endsWith("hello"))
    }

    // --- FIXED streaming path ---

    @Test
    fun `HttpResponseHead with Content-Length 0 plus HttpBodyEnd EMPTY writes head only`() {
        val pipeline = createEncoderPipeline()
        val head = HttpResponseHead(
            status = HttpStatus.OK,
            headers = HttpHeaders.of("Content-Length" to "0"),
        )
        pipeline.writeFromTail(head)
        pipeline.writeFromTail(HttpBodyEnd.EMPTY)

        // Head buffer only (EMPTY body has 0 readable bytes, not written).
        assertEquals(1, transport.written.size)
        val text = transport.written[0].readString()
        assertTrue(text.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(text.contains("Content-Length: 0\r\n"))
    }

    @Test
    fun `HttpResponseHead with Content-Length plus HttpBody plus HttpBodyEnd writes sequence`() {
        val pipeline = createEncoderPipeline()
        val head = HttpResponseHead(
            status = HttpStatus.OK,
            headers = HttpHeaders.of("Content-Length" to "10"),
        )
        pipeline.writeFromTail(head)
        pipeline.writeFromTail(HttpBody(bufOf("hello")))
        pipeline.writeFromTail(HttpBodyEnd(bufOf("world"), HttpHeaders.EMPTY))

        // head + body1 + body2 = 3 transport writes.
        assertEquals(3, transport.written.size)
        val headText = transport.written[0].readString()
        assertTrue(headText.startsWith("HTTP/1.1 200 OK\r\n"))
        assertEquals("hello", transport.written[1].readString())
        assertEquals("world", transport.written[2].readString())
    }

    @Test
    fun `FIXED mode content exceeding Content-Length propagates error`() {
        val pipeline = createEncoderPipeline()
        val head = HttpResponseHead(
            status = HttpStatus.OK,
            headers = HttpHeaders.of("Content-Length" to "3"),
        )
        pipeline.writeFromTail(head)
        pipeline.writeFromTail(HttpBody(bufOf("toolong")))

        assertEquals(1, errorCollector.errors.size)
        assertIs<IllegalStateException>(errorCollector.errors[0])
    }

    @Test
    fun `HttpResponseHead without Content-Length or chunked propagates error`() {
        val pipeline = createEncoderPipeline()
        val head = HttpResponseHead(
            status = HttpStatus.OK,
            headers = HttpHeaders(),
        )
        pipeline.writeFromTail(head)

        assertEquals(1, errorCollector.errors.size)
        assertIs<IllegalStateException>(errorCollector.errors[0])
    }

    /** Initiates an outbound write from the tail toward HEAD (through HttpResponseEncoder). */
    private fun ChannelPipeline.writeFromTail(msg: Any) {
        requestWrite(msg)
    }
}
