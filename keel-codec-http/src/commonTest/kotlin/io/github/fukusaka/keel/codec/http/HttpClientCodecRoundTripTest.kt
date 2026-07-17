package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import io.github.fukusaka.keel.pipeline.InboundHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round-trip integration of the client codec against the server codec:
 * a typed [HttpRequest] written on the client pipeline crosses to the
 * server pipeline as wire bytes, the server-side stack decodes it and
 * answers, and the response bytes cross back to be decoded and
 * aggregated into an [HttpResponse] on the client.
 *
 * ```
 * client: HttpRequest ─► HttpRequestEncoder ─► bytes ─► server: HttpRequestDecoder ─► HttpRequest
 * client: HttpResponse ◄─ HttpResponseDecoder ◄─ bytes ◄─ server: HttpResponseEncoder ◄─ HttpResponse
 * ```
 */
class HttpClientCodecRoundTripTest {

    // --- Test infrastructure ---

    private val clientTransport = TestIoTransport()
    private val clientChannel = object : AbstractPipelinedChannel(clientTransport, PrintLogger("client")) {}
    private val serverTransport = TestIoTransport()
    private val serverChannel = object : AbstractPipelinedChannel(serverTransport, PrintLogger("server")) {}

    /** Client-side terminal handler collecting aggregated responses. */
    private class ResponseCollector : InboundHandler {
        val responses = mutableListOf<HttpResponse>()
        val errors = mutableListOf<Throwable>()

        override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
            when (msg) {
                is HttpResponse -> responses.add(msg)
                else -> error("Unexpected message: ${msg::class.simpleName}")
            }
        }

        override fun onError(ctx: PipelineHandlerContext, cause: Throwable) {
            errors.add(cause)
        }
    }

    /** Server-side terminal handler echoing each request via [respond]. */
    private class EchoHandler(
        private val respond: (HttpRequest) -> HttpResponse,
    ) : InboundHandler {
        val requests = mutableListOf<HttpRequest>()

        override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
            if (msg !is HttpRequest) error("Unexpected message: ${msg::class.simpleName}")
            requests.add(msg)
            ctx.propagateWrite(respond(msg))
        }
    }

    private val responseCollector = ResponseCollector()

    private fun setUpClient() {
        clientChannel.addHttp1ClientCodec()
        clientChannel.pipeline.addLast("collector", responseCollector)
    }

    private fun setUpServer(respond: (HttpRequest) -> HttpResponse): EchoHandler {
        val handler = EchoHandler(respond)
        serverChannel.addHttp1ServerCodec()
        serverChannel.pipeline.addLast("echo", handler)
        return handler
    }

    /**
     * Moves every captured write from [from] to the peer pipeline as fresh
     * inbound buffers (copies — the transport still owns its captures).
     */
    private fun shuttle(from: TestIoTransport, to: AbstractPipelinedChannel) {
        for (buf in from.written) {
            val len = buf.readableBytes
            val copy = DefaultAllocator.allocate(len)
            for (i in 0 until len) copy.writeByte(buf.getByte(buf.readerIndex + i))
            to.pipeline.notifyRead(copy)
        }
        from.releaseWritten()
    }

    // --- Round trips ---

    @Test
    fun `POST request round-trips to an aggregated response`() {
        setUpClient()
        val server = setUpServer { request ->
            HttpResponse.ok("echo: ${request.body?.decodeToString()}")
        }

        clientChannel.pipeline.requestWrite(
            HttpRequest(
                HttpMethod.POST,
                "/echo",
                headers = HttpHeaders.of("Host" to "example.com", "Content-Length" to "7"),
                body = "payload".encodeToByteArray(),
            ),
        )
        shuttle(clientTransport, serverChannel)
        shuttle(serverTransport, clientChannel)

        val received = server.requests.single()
        assertEquals(HttpMethod.POST, received.method)
        assertEquals("/echo", received.uri)
        assertEquals("payload", received.body?.decodeToString())

        val response = responseCollector.responses.single()
        assertEquals(HttpStatus.OK, response.status)
        assertEquals("echo: payload", response.body?.decodeToString())
        assertTrue(responseCollector.errors.isEmpty())
    }

    @Test
    fun `HEAD request round-trips as a bodyless response`() {
        setUpClient()
        setUpServer { HttpResponse.ok("the body the server suppresses") }

        clientChannel.pipeline.requestWrite(
            HttpRequest(HttpMethod.HEAD, "/page", headers = HttpHeaders.of("Host" to "example.com")),
        )
        shuttle(clientTransport, serverChannel)
        shuttle(serverTransport, clientChannel)

        // The server emits status line + headers (incl. Content-Length) with
        // no body; the client decoder must frame it by the queued HEAD
        // method, not the Content-Length.
        val response = responseCollector.responses.single()
        assertEquals(HttpStatus.OK, response.status)
        assertNull(response.body)
        assertTrue(responseCollector.errors.isEmpty())
    }

    @Test
    fun `pipelined GET requests round-trip in order`() {
        setUpClient()
        setUpServer { request -> HttpResponse.ok("path: ${request.path}") }

        clientChannel.pipeline.requestWrite(
            HttpRequest(HttpMethod.GET, "/first", headers = HttpHeaders.of("Host" to "h")),
        )
        clientChannel.pipeline.requestWrite(
            HttpRequest(HttpMethod.GET, "/second", headers = HttpHeaders.of("Host" to "h")),
        )
        shuttle(clientTransport, serverChannel)
        shuttle(serverTransport, clientChannel)

        assertEquals(2, responseCollector.responses.size)
        assertEquals("path: /first", responseCollector.responses[0].body?.decodeToString())
        assertEquals("path: /second", responseCollector.responses[1].body?.decodeToString())
        assertTrue(responseCollector.errors.isEmpty())
    }
}
