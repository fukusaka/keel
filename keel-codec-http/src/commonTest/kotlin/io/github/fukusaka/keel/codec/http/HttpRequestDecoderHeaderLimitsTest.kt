package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import io.github.fukusaka.keel.pipeline.InboundHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Header-count cap enforcement (`HttpHeaderLimitsConfig.maxHeaderCount`)
 * in [HttpRequestDecoder]. Two boundary points pin the contract:
 *
 * 1. A request at the cap parses cleanly and the decoded
 *    `HttpRequestHead` carries every header field.
 * 2. A request one over the cap aborts with
 *    `HttpHeaderLimitExceededException` (subclass of
 *    `HttpParseException`) whose `limitName` / `actual` / `limit`
 *    fields surface the configured cap value verbatim — log readers
 *    match the exception back to the `HttpHeaderLimitsConfig` setter
 *    without parsing the message string.
 *
 * The cap is also asserted to apply to chunked trailers (a malicious
 * peer could otherwise bypass it by splitting fields between the head
 * and the trailer block).
 */
class HttpRequestDecoderHeaderLimitsTest {

    private val transport = TestIoTransport()
    private val channel = object : AbstractPipelinedChannel(transport, PrintLogger("dos-limits")) {}

    private class Collector : InboundHandler {
        var lastHead: HttpRequestHead? = null
        val errors: MutableList<Throwable> = mutableListOf()
        override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
            if (msg is HttpRequestHead) lastHead = msg
            if (msg is HttpBody) msg.content.release()
            if (msg is HttpBodyEnd) {
                msg.trailers.release()
                msg.content?.release()
            }
        }
        override fun onError(ctx: PipelineHandlerContext, cause: Throwable) {
            errors.add(cause)
        }
    }

    private val collector = Collector()

    private fun install(headerLimits: HttpHeaderLimitsConfig) {
        channel.pipeline.addLast("decoder", HttpRequestDecoder(headerLimits))
        channel.pipeline.addLast("collector", collector)
    }

    private fun buildRequest(headerCount: Int, trailing: String = ""): ByteArray {
        val sb = StringBuilder()
        sb.append("GET /test HTTP/1.1\r\n")
        sb.append("Host: example.com\r\n")
        // Host is one of the headers — emit (headerCount - 1) extra
        // X-Foo entries so the total is exactly `headerCount`.
        repeat(headerCount - 1) { i ->
            sb.append("X-Foo-")
            sb.append(i)
            sb.append(": v")
            sb.append(i)
            sb.append("\r\n")
        }
        sb.append("\r\n")
        sb.append(trailing)
        return sb.toString().encodeToByteArray()
    }

    @Test
    fun `parse succeeds when header count is exactly at the configured cap`() {
        val cap = 5
        install(HttpHeaderLimitsConfig(maxHeaderCount = cap))
        val bytes = buildRequest(cap)
        val buf = DefaultAllocator.allocate(bytes.size.coerceAtLeast(64))
        buf.writeByteArray(bytes, 0, bytes.size)
        channel.pipeline.notifyRead(buf)
        val head = assertNotNull(collector.lastHead, "decoder did not emit a head at exactly the cap")
        try {
            assertEquals(cap, head.headers.size, "expected exactly $cap headers")
            assertTrue(collector.errors.isEmpty(), "no error expected at the boundary")
        } finally {
            head.headers.release()
        }
    }

    @Test
    fun `parse aborts with HttpHeaderLimitExceededException one header past the cap`() {
        val cap = 5
        install(HttpHeaderLimitsConfig(maxHeaderCount = cap))
        val bytes = buildRequest(cap + 1)
        val buf = DefaultAllocator.allocate(bytes.size.coerceAtLeast(64))
        buf.writeByteArray(bytes, 0, bytes.size)
        // The decoder (autoRelease = false) takes ownership of the
        // buf via its own try/finally — we do not release it here.
        channel.pipeline.notifyRead(buf)

        // The decoder reset its state and propagated the exception via
        // `onError`; the test pipeline's collector records it.
        assertEquals(1, collector.errors.size, "expected exactly one error")
        val cause = collector.errors.single()
        assertTrue(
            cause is HttpHeaderLimitExceededException,
            "expected HttpHeaderLimitExceededException, got ${cause::class}",
        )
        assertEquals("maxHeaderCount", cause.limitName)
        assertEquals(cap + 1, cause.actual)
        assertEquals(cap, cause.limit)
        // HttpHeaderLimitExceededException MUST be a subclass of
        // HttpParseException so existing pipelines catching the latter
        // keep working.
        assertTrue(cause is HttpParseException)
    }

    @Test
    fun `default HttpHeaderLimitsConfig accepts a CDN-typical 23-header request`() {
        // Sanity: the documented default cap (100) does not interfere
        // with the CDN-typical request shape the codec is benched
        // against.
        install(HttpHeaderLimitsConfig.DEFAULT)
        val bytes = buildRequest(23)
        val buf = DefaultAllocator.allocate(bytes.size.coerceAtLeast(64))
        buf.writeByteArray(bytes, 0, bytes.size)
        channel.pipeline.notifyRead(buf)
        val head = assertNotNull(collector.lastHead, "decoder did not emit a head for 23 headers under the default cap")
        try {
            assertEquals(23, head.headers.size)
            assertTrue(collector.errors.isEmpty())
        } finally {
            head.headers.release()
        }
    }

    @Test
    fun `maxHeaderCount cap also applies to chunked trailers`() {
        // A chunked body whose terminator is followed by 6 trailer
        // fields when the cap is 5: must abort exactly the same way
        // as a 6-header head.
        val cap = 5
        install(HttpHeaderLimitsConfig(maxHeaderCount = cap))
        val request = buildString {
            append("POST /upload HTTP/1.1\r\n")
            append("Host: example.com\r\n")
            append("Transfer-Encoding: chunked\r\n")
            append("\r\n")
            append("0\r\n")
            repeat(cap + 1) { i ->
                append("X-Trailer-")
                append(i)
                append(": v")
                append(i)
                append("\r\n")
            }
            append("\r\n")
        }.encodeToByteArray()
        val buf = DefaultAllocator.allocate(request.size.coerceAtLeast(256))
        buf.writeByteArray(request, 0, request.size)
        channel.pipeline.notifyRead(buf)
        assertTrue(collector.errors.isNotEmpty(), "expected trailer overflow to surface as an error")
        val cause = collector.errors.last()
        assertTrue(cause is HttpHeaderLimitExceededException, "expected HttpHeaderLimitExceededException for trailers")
        assertEquals("maxHeaderCount", cause.limitName)
    }
}
