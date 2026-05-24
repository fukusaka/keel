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
 * `HttpHeaderLimitsConfig.maxLineSize` enforcement boundaries in
 * [HttpRequestDecoder]. The cap is per-line (request line / header /
 * trailer / chunk size) and distinguishes its over-cap exception by
 * line type:
 *
 * - **request line** over-cap → [HttpUriLengthExceededException]
 *   (subclass of [HttpHeaderLimitExceededException], for the 414 mapper)
 * - **header / trailer / chunk size line** over-cap →
 *   [HttpHeaderLimitExceededException] (for the 431 mapper)
 *
 * The historical default (8 KiB) is byte-identical to the
 * pre-PR-β `MAX_LINE_SIZE` constant so existing deployments are
 * unaffected.
 */
class HttpRequestDecoderLineSizeLimitTest {

    private val transport = TestIoTransport()
    private val channel = object : AbstractPipelinedChannel(transport, PrintLogger("dos-line")) {}

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

    private fun deliver(bytes: ByteArray) {
        val buf = DefaultAllocator.allocate(bytes.size.coerceAtLeast(64))
        buf.writeByteArray(bytes, 0, bytes.size)
        channel.pipeline.notifyRead(buf)
    }

    @Test
    fun `request line over maxLineSize raises HttpUriLengthExceededException`() {
        install(HttpHeaderLimitsConfig(maxHeaderCount = 100, maxHeaderBytes = 16384, maxLineSize = 1024))
        // request line "GET /aaaa...a HTTP/1.1" — pad URI so the whole
        // line exceeds 1024 bytes.
        val padding = "a".repeat(1100)
        val request = buildString {
            append("GET /").append(padding).append(" HTTP/1.1\r\n")
            append("Host: example.com\r\n\r\n")
        }.encodeToByteArray()
        deliver(request)
        assertEquals(1, collector.errors.size, "expected exactly one error")
        val cause = collector.errors.single()
        assertTrue(
            cause is HttpUriLengthExceededException,
            "expected HttpUriLengthExceededException, got ${cause::class}",
        )
        assertTrue(cause.actual > 1024, "actual (${cause.actual}) must exceed cap (1024)")
        assertEquals(1024, cause.limit)
        // Subtype relationship: HttpHeaderLimitExceededException catch
        // arms (per the PR-α tests) still match.
        assertTrue(cause is HttpHeaderLimitExceededException)
    }

    @Test
    fun `header line over maxLineSize raises generic HttpHeaderLimitExceededException`() {
        install(HttpHeaderLimitsConfig(maxHeaderCount = 100, maxHeaderBytes = 16384, maxLineSize = 1024))
        val padding = "v".repeat(1100)
        val request = buildString {
            append("GET / HTTP/1.1\r\n")
            append("Host: example.com\r\n")
            append("X-Big: ").append(padding).append("\r\n")
            append("\r\n")
        }.encodeToByteArray()
        deliver(request)
        assertEquals(1, collector.errors.size, "expected exactly one error")
        val cause = collector.errors.single()
        assertTrue(
            cause is HttpHeaderLimitExceededException,
            "expected HttpHeaderLimitExceededException, got ${cause::class}",
        )
        // Crucial: must NOT be the URI-specific subtype — header lines
        // are 431, not 414.
        assertTrue(
            cause !is HttpUriLengthExceededException,
            "expected generic HttpHeaderLimitExceededException (431), not the URI subtype (414)",
        )
        assertEquals("maxLineSize", cause.limitName)
        assertEquals(1024, cause.limit)
    }

    @Test
    fun `default maxLineSize is byte-identical to the historical 8192 constant`() {
        // PR-β introduces the field but the default keeps the historical
        // 8192 byte cap, so deployments that did not opt-in to a custom
        // value see identical behaviour.
        assertEquals(8192, HttpHeaderLimitsConfig.DEFAULT.maxLineSize)
    }

    @Test
    fun `maxLineSize validator rejects non-power-of-two`() {
        // Validator pin: any non-power-of-two value throws at config
        // construction so misconfiguration surfaces early, not at parse
        // time.
        try {
            HttpHeaderLimitsConfig(maxHeaderCount = 100, maxHeaderBytes = 16384, maxLineSize = 7000)
            error("expected IllegalArgumentException for non-power-of-two maxLineSize")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("power of two"), "wrong reason: ${e.message}")
        }
    }

    @Test
    fun `maxLineSize validator rejects out-of-range values`() {
        // Below the 1 KiB floor (e.g. 512).
        try {
            HttpHeaderLimitsConfig(maxHeaderCount = 100, maxHeaderBytes = 16384, maxLineSize = 512)
            error("expected IllegalArgumentException for maxLineSize < 1 KiB")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("1024..1048576"), "wrong reason: ${e.message}")
        }
        // Above the 1 MiB ceiling (e.g. 2 MiB).
        try {
            HttpHeaderLimitsConfig(maxHeaderCount = 100, maxHeaderBytes = 16384, maxLineSize = 1024 * 1024 * 2)
            error("expected IllegalArgumentException for maxLineSize > 1 MiB")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("1024..1048576"), "wrong reason: ${e.message}")
        }
    }

    @Test
    fun `configurable maxLineSize accepts a larger custom value`() {
        // 16 KiB cap (2× default) accommodates a longer URI than the
        // default 8 KiB would. Cross-check that the new knob actually
        // changes behaviour, not just gets ignored.
        install(HttpHeaderLimitsConfig(maxHeaderCount = 100, maxHeaderBytes = 32768, maxLineSize = 16384))
        // Request line of ~10 KiB — would fail under default 8 KiB, must
        // succeed under 16 KiB.
        val padding = "a".repeat(10000)
        val request = buildString {
            append("GET /").append(padding).append(" HTTP/1.1\r\n")
            append("Host: example.com\r\n\r\n")
        }.encodeToByteArray()
        deliver(request)
        val head = assertNotNull(collector.lastHead, "expected emit for 10 KiB URI under 16 KiB cap")
        try {
            assertTrue(collector.errors.isEmpty())
        } finally {
            head.headers.release()
        }
    }
}
