package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.pipeline.AbstractIoTransport
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import io.github.fukusaka.keel.pipeline.Pipeline
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [installPipelineHttpHandlers] routing behaviour.
 *
 * Drives the full encoder → decoder → routing stack via raw HTTP/1.1 byte
 * input and asserts on the captured wire output — identical to the pattern
 * used by `RoutingHandlerTest` in `:keel-codec-http`.
 */
class PipelineHttpRoutesTest {

    private class CapturingTransport : AbstractIoTransport(DefaultAllocator) {
        val written: MutableList<IoBuf> = mutableListOf()
        override var readEnabled: Boolean = false
        override val ioDispatcher: CoroutineDispatcher get() = Dispatchers.Unconfined
        override fun write(buf: IoBuf) { buf.retain(); written.add(buf) }
        override fun flush(): Boolean = true
        override fun shutdownOutput() {}
        override fun close() {
            if (!markClosing()) return
            if (!markTeardownStarted()) return
            for (buf in written) buf.release()
            written.clear()
        }
    }

    private val transport = CapturingTransport()
    private val channel = object : AbstractPipelinedChannel(transport, PrintLogger("test")) {}

    @AfterTest
    fun tearDown() {
        channel.close()
    }

    private fun createPipeline(): Pipeline {
        val pipeline = channel.pipeline
        installPipelineHttpHandlers(pipeline)
        return pipeline
    }

    private fun Pipeline.feed(text: String) {
        val bytes = text.encodeToByteArray()
        val buf = DefaultAllocator.allocate(bytes.size)
        buf.writeByteArray(bytes, 0, bytes.size)
        notifyRead(buf)
    }

    private fun IoBuf.readString(): String {
        val bytes = ByteArray(readableBytes)
        readByteArray(bytes, 0, bytes.size)
        return bytes.decodeToString()
    }

    private fun collectWire(): String =
        transport.written.joinToString("") { it.readString() }

    @Test
    fun `hello returns 200 with expected body`() {
        createPipeline()
        channel.pipeline.feed("GET /hello HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n")

        val wire = collectWire()
        assertTrue(wire.startsWith("HTTP/1.1 200 OK\r\n"), "expected 200: $wire")
        assertTrue(wire.endsWith("Hello, World!"), "expected body: $wire")
    }

    @Test
    fun `sse-stream emits exactly one response and does not send unsolicited second response on keep-alive`() {
        // Regression test for K3: BenchmarkRoutingHandler was resetting currentPath
        // to null inside emitSseStream (called on HttpRequestHead) but did NOT set a
        // flag to suppress the HttpBodyEnd handler. When HttpBodyEnd arrived, it fell
        // to emitResponse(currentPath=null) → 404 Not Found. That second, unsolicited
        // response broke HTTP/1.1 keep-alive for all subsequent requests on the same
        // connection.
        createPipeline()

        // First SSE request.
        channel.pipeline.feed(
            "GET /sse-stream?count=2&size=5 HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n",
        )
        val wire = collectWire()

        // Must start with exactly one 200 OK, not a second HTTP/ response afterwards.
        val statusLineCount = "HTTP/1.1".toRegex().findAll(wire).count()
        assertEquals(
            1,
            statusLineCount,
            "expected exactly 1 HTTP response for SSE stream, got $statusLineCount. Wire:\n$wire",
        )
        assertTrue(wire.startsWith("HTTP/1.1 200 OK\r\n"), "expected 200: $wire")
        assertTrue(wire.contains("Transfer-Encoding: chunked"), "expected chunked: $wire")
        // Verify terminator present (complete chunked stream).
        assertTrue(wire.endsWith("0\r\n\r\n"), "expected chunked terminator: $wire")
    }

    @Test
    fun `sse-stream followed by hello on keep-alive returns correct responses for both`() {
        // Verifies that the connection stays alive after SSE and the next request works.
        createPipeline()

        channel.pipeline.feed(
            "GET /sse-stream?count=1&size=3 HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n" +
                "GET /hello HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n",
        )
        val wire = collectWire()

        // Two HTTP responses total.
        val statusLineCount = "HTTP/1.1".toRegex().findAll(wire).count()
        assertEquals(2, statusLineCount, "expected 2 HTTP responses, got $statusLineCount. Wire:\n$wire")

        // Second response must be 200 Hello, World! (not 404 from the spurious emitResponse).
        assertTrue(wire.endsWith("Hello, World!"), "expected /hello body at end: $wire")
    }
}
