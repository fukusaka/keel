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
 * `HttpHeaderLimitsConfig.maxHeaderBytes` enforcement boundaries in
 * [HttpRequestDecoder]. Two cases pin the cumulative-bytes contract
 * that complements the count + per-line caps PR-α already
 * established:
 *
 * 1. A request whose cumulative `name+value` bytes sum is at the cap
 *    parses cleanly.
 * 2. A request that overshoots the cap aborts with
 *    `HttpHeaderLimitExceededException(limitName = "maxHeaderBytes")`.
 *
 * The cap also covers trailer fields — a malicious peer cannot
 * bypass it by stuffing bytes into the chunked trailer block.
 */
class HttpRequestDecoderHeaderBytesLimitTest {

    private val transport = TestIoTransport()
    private val channel = object : AbstractPipelinedChannel(transport, PrintLogger("dos-bytes")) {}

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
    fun `parse succeeds when cumulative header bytes are exactly at the cap`() {
        // Field byte arithmetic:
        //   "Host: example.com" → name="Host" (4) + value="example.com" (11) = 15
        //   each "X-Foo-N: v" pair → name="X-Foo-N" (7) + value="v" (1) = 8
        // 6 headers (Host + 5 X-Foo) → 15 + 5 * 8 = 55.
        val cap = 55
        install(HttpHeaderLimitsConfig(maxHeaderCount = 100, maxHeaderBytes = cap, maxLineSize = 8192))
        val request = buildString {
            append("GET /test HTTP/1.1\r\n")
            append("Host: example.com\r\n")
            for (i in 0..4) append("X-Foo-$i: v\r\n")
            append("\r\n")
        }.encodeToByteArray()
        deliver(request)
        val head = assertNotNull(collector.lastHead, "decoder did not emit at exactly the cap")
        try {
            assertTrue(collector.errors.isEmpty(), "no error expected at the boundary")
            assertEquals(6, head.headers.size, "expected exactly 6 headers (Host + 5 X-Foo)")
        } finally {
            head.headers.release()
        }
    }

    @Test
    fun `parse aborts with HttpHeaderLimitExceededException one byte past the cap`() {
        // Same fields plus one more X-Foo-5 (+8 bytes), with cap set one
        // below the at-cap test (54) so the 6th X-Foo (= +8 bytes,
        // total 63) is the trigger.
        val cap = 54
        install(HttpHeaderLimitsConfig(maxHeaderCount = 100, maxHeaderBytes = cap, maxLineSize = 8192))
        val request = buildString {
            append("GET /test HTTP/1.1\r\n")
            append("Host: example.com\r\n")
            for (i in 0..5) append("X-Foo-$i: v\r\n")
            append("\r\n")
        }.encodeToByteArray()
        deliver(request)
        assertEquals(1, collector.errors.size, "expected exactly one error")
        val cause = collector.errors.single()
        assertTrue(
            cause is HttpHeaderLimitExceededException,
            "expected HttpHeaderLimitExceededException, got ${cause::class}",
        )
        assertEquals("maxHeaderBytes", cause.limitName)
        assertTrue(cause.actual > cap, "actual (${cause.actual}) must be over cap ($cap)")
        assertEquals(cap, cause.limit)
    }

    @Test
    fun `maxHeaderBytes cap also applies to chunked trailers`() {
        // Cap sized so the head fits and the trailer pushes over.
        //   Head: Host (15) + Transfer-Encoding=chunked (17+7=24) = 39.
        //   Trailer: X-T: x (3+1=4) → cumulative 43.
        // Cap = 40 → trailer add is the trigger (39 ≤ 40, 43 > 40).
        val cap = 40
        install(HttpHeaderLimitsConfig(maxHeaderCount = 100, maxHeaderBytes = cap, maxLineSize = 8192))
        val request = buildString {
            append("POST /u HTTP/1.1\r\n")
            append("Host: example.com\r\n")
            append("Transfer-Encoding: chunked\r\n")
            append("\r\n")
            append("0\r\n")
            append("X-T: x\r\n")
            append("\r\n")
        }.encodeToByteArray()
        deliver(request)
        assertTrue(collector.errors.isNotEmpty(), "expected over-cap error from the trailer add")
        val cause = collector.errors.last()
        assertTrue(
            cause is HttpHeaderLimitExceededException,
            "expected HttpHeaderLimitExceededException, got ${cause::class}",
        )
        assertEquals("maxHeaderBytes", cause.limitName)
        // The head must have emitted (Host + Transfer-Encoding under cap)
        // before the trailer-add overshoot — a head emit then a trailer
        // error is the contract.
        assertNotNull(collector.lastHead, "head should emit before trailer cap fires")
    }

    @Test
    fun `default HttpHeaderLimitsConfig admits a realistic 1 KiB header set`() {
        // Sanity: documented default 16 KiB easily admits CDN-typical
        // traffic. Synthesise ~1 KiB of header bytes — well under the cap.
        install(HttpHeaderLimitsConfig.DEFAULT)
        val request = buildString {
            append("GET /test HTTP/1.1\r\n")
            append("Host: example.com\r\n")
            // 20 fields × ~50 bytes each ≈ 1 KiB
            repeat(20) { i ->
                append("X-Header-$i: ")
                repeat(40) { append('x') }
                append("\r\n")
            }
            append("\r\n")
        }.encodeToByteArray()
        deliver(request)
        val head = assertNotNull(collector.lastHead, "decoder did not emit for ~1 KiB headers under the default cap")
        try {
            assertTrue(collector.errors.isEmpty())
        } finally {
            head.headers.release()
        }
    }
}
