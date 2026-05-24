package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import io.github.fukusaka.keel.pipeline.InboundHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cross-read parse with uniform power-of-two buffers (the production
 * pooled-allocator case after PR #597 / #598 made the recv segment size
 * a per-connection invariant) must keep every header as a zero-copy
 * range entry over the retained recv buffers. Without chain-global
 * multi-segment addressing the headers landing in the second buffer
 * fall back to `String` materialisation, defeating the byte-range view
 * storage that PR #596 established for the single-buffer case.
 */
class HttpHeadersChainGlobalTest {

    private val transport = TestIoTransport()
    private val channel = object : AbstractPipelinedChannel(transport, PrintLogger("hh-chainglobal")) {}

    private class HeadCollector : InboundHandler {
        var lastHead: HttpRequestHead? = null
        override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
            if (msg is HttpRequestHead) lastHead = msg
            if (msg is HttpBody) msg.content.release()
        }

        override fun onError(ctx: PipelineHandlerContext, cause: Throwable) { /* ignore */ }
    }

    private val collector = HeadCollector()

    init {
        channel.pipeline.addLast("decoder", HttpRequestDecoder())
        channel.pipeline.addLast("collector", collector)
    }

    @Test
    fun `cross-read parse with uniform power-of-two buffers keeps every header as a range entry`() {
        val request = buildString {
            append("GET /hello HTTP/1.1\r\n")
            append("Host: example.com\r\n")
            append("User-Agent: test\r\n")
            append("X-A: alpha\r\n")
            append("X-B: bravo\r\n")
            append("X-C: charlie\r\n")
            append("\r\n")
        }.encodeToByteArray()

        // Find a CRLF line boundary near the middle so the split lands
        // between two whole header lines (not mid-line — the latter is
        // Case B and falls into the accumulator path by design).
        var splitOffset = request.size / 2
        while (splitOffset < request.size - 1 &&
            !(request[splitOffset] == '\r'.code.toByte() && request[splitOffset + 1] == '\n'.code.toByte())
        ) {
            splitOffset++
        }
        splitOffset += 2

        // Same-capacity (2^N) buffers so the chain-global capacity guard
        // engages — this is the production pooled allocator size class.
        val buf1 = DefaultAllocator.allocate(SEGMENT_SIZE)
        buf1.writeByteArray(request, 0, splitOffset)
        val buf2 = DefaultAllocator.allocate(SEGMENT_SIZE)
        buf2.writeByteArray(request, splitOffset, request.size - splitOffset)

        channel.pipeline.notifyRead(buf1)
        channel.pipeline.notifyRead(buf2)

        val head = collector.lastHead ?: error("decoder emitted no HttpRequestHead")
        try {
            assertTrue(head.headers.size >= 5, "expected at least 5 headers, got ${head.headers.size}")
            assertEquals(
                head.headers.size,
                head.headers.rangeEntryCount,
                "every header should remain a range entry with chain-global multi-segment addressing; " +
                    "size=${head.headers.size} rangeEntries=${head.headers.rangeEntryCount}",
            )
            // Sanity: each header readable, including ones that landed in the second buffer.
            assertEquals("example.com", head.headers.getString("Host"))
            assertEquals("test", head.headers.getString("User-Agent"))
            assertEquals("alpha", head.headers.getString("X-A"))
            assertEquals("bravo", head.headers.getString("X-B"))
            assertEquals("charlie", head.headers.getString("X-C"))
        } finally {
            head.headers.release()
        }
    }

    private companion object {
        /**
         * Power-of-two buffer capacity matching the engine-default segment
         * size — see [io.github.fukusaka.keel.pipeline.IoTransport.DEFAULT_READ_BUFFER_SIZE].
         */
        private const val SEGMENT_SIZE: Int = 8192
    }
}
