package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import io.github.fukusaka.keel.pipeline.InboundHandler
import io.github.fukusaka.keel.pipeline.Pipeline
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpBodyAggregatorTest {

    // --- Test infrastructure ---

    private val tracker = TrackingAllocator(DefaultAllocator)
    private val transport = TestIoTransport(allocator = tracker)
    private val channel = object : AbstractPipelinedChannel(transport, PrintLogger("test")) {}

    /** Collects aggregated [HttpRequest] and errors. */
    private class RequestCollector : InboundHandler {
        val requests = mutableListOf<HttpRequest>()
        val errors = mutableListOf<Throwable>()

        override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
            requests.add(msg as HttpRequest)
        }

        override fun onError(ctx: PipelineHandlerContext, cause: Throwable) {
            errors.add(cause)
        }
    }

    private fun createPipeline(
        maxContentLength: Int = 1 shl 20,
    ): Pair<Pipeline, RequestCollector> {
        val collector = RequestCollector()
        val pipeline = channel.pipeline
        pipeline.addLast("decoder", HttpRequestDecoder())
        pipeline.addLast("aggregator", HttpBodyAggregator(maxContentLength))
        pipeline.addLast("collector", collector)
        return pipeline to collector
    }

    private fun bufOf(text: String): IoBuf {
        val bytes = text.encodeToByteArray()
        val buf = tracker.allocate(bytes.size)
        buf.writeByteArray(bytes, 0, bytes.size)
        return buf
    }

    /**
     * Like [bufOf] but rounds the capacity up to a power of two, so the
     * decoder's `addRange` zero-copy header path is taken (it retains the recv
     * buffer in `head.headers`). Exact-size [bufOf] buffers degrade to the
     * String-materialise path, which does not retain — so only this helper
     * exercises the recv-buffer retention that the mid-body-close fix must
     * release.
     */
    private fun bufOfPow2(text: String): IoBuf {
        val bytes = text.encodeToByteArray()
        var cap = 1
        while (cap < bytes.size) cap = cap shl 1
        val buf = tracker.allocate(cap)
        buf.writeByteArray(bytes, 0, bytes.size)
        return buf
    }

    // --- Tests ---

    @Test
    fun `aggregates HttpRequestHead plus multiple HttpBody plus HttpBodyEnd into HttpRequest`() {
        val (pipeline, collector) = createPipeline()

        pipeline.notifyRead(
            bufOf(
                "POST /data HTTP/1.1\r\n" +
                    "Host: example.com\r\n" +
                    "Content-Length: 11\r\n" +
                    "\r\n" +
                    "hello",
            ),
        )
        // First IoBuf delivers 5 body bytes; 6 remaining.
        pipeline.notifyRead(bufOf(" world"))

        assertEquals(1, collector.requests.size)
        val req = collector.requests[0]
        assertEquals(HttpMethod.POST, req.method)
        assertEquals("/data", req.path)
        assertNotNull(req.body)
        assertEquals("hello world", req.body!!.decodeToString())
        tracker.assertNoLeaks("multi-chunk aggregation must release every held chunk")
    }

    @Test
    fun `zero-body request yields HttpRequest with null body`() {
        val (pipeline, collector) = createPipeline()

        pipeline.notifyRead(
            bufOf("GET /empty HTTP/1.1\r\nHost: example.com\r\n\r\n"),
        )

        assertEquals(1, collector.requests.size)
        assertNull(collector.requests[0].body)
        tracker.assertNoLeaks("zero-body must leave no held chunk")
    }

    @Test
    fun `content exceeding maxContentLength propagates error`() {
        val (pipeline, collector) = createPipeline(maxContentLength = 5)

        pipeline.notifyRead(
            bufOf(
                "POST /big HTTP/1.1\r\n" +
                    "Host: example.com\r\n" +
                    "Content-Length: 10\r\n" +
                    "\r\n" +
                    "0123456789",
            ),
        )

        assertEquals(0, collector.requests.size)
        assertEquals(1, collector.errors.size)
        assertIs<HttpParseException>(collector.errors[0])
        assertTrue(collector.errors[0].message!!.contains("maxContentLength"))
        tracker.assertNoLeaks("overflow must release every held + remaining chunk")
    }

    @Test
    fun `overflow after partial accumulation releases the held chunks`() {
        val (pipeline, collector) = createPipeline(maxContentLength = 8)

        // "hello" (5) is held, then " world" (6) tips the total to 11 > 8.
        pipeline.notifyRead(
            bufOf(
                "POST /big HTTP/1.1\r\n" +
                    "Host: example.com\r\n" +
                    "Content-Length: 11\r\n" +
                    "\r\n" +
                    "hello",
            ),
        )
        pipeline.notifyRead(bufOf(" world"))

        assertEquals(0, collector.requests.size)
        assertEquals(1, collector.errors.size)
        assertIs<HttpParseException>(collector.errors[0])
        // The chunk held before overflow plus the overflowing chunk are released.
        tracker.assertNoLeaks("overflow after partial accumulation must release the held chunk")
    }

    @Test
    fun `trailers on HttpBodyEnd are discarded by aggregator`() {
        val (pipeline, collector) = createPipeline()

        pipeline.notifyRead(
            bufOf(
                "POST /chunked HTTP/1.1\r\n" +
                    "Host: example.com\r\n" +
                    "Transfer-Encoding: chunked\r\n" +
                    "\r\n" +
                    "5\r\nhello\r\n" +
                    "0\r\n" +
                    "Trailer-Key: trailer-value\r\n" +
                    "\r\n",
            ),
        )

        assertEquals(1, collector.requests.size)
        val req = collector.requests[0]
        assertEquals("hello", req.body!!.decodeToString())
        // Trailers are not surfaced on HttpRequest — they are discarded.
        assertNull(req.headers.getString("Trailer-Key"))
        tracker.assertNoLeaks("chunked aggregation must release every held chunk")
    }

    @Test
    fun `all HttpBody IoBufs are released after aggregation`() {
        val (pipeline, collector) = createPipeline()

        // Send a POST with body split across IoBufs.
        pipeline.notifyRead(
            bufOf(
                "POST /rel HTTP/1.1\r\n" +
                    "Host: example.com\r\n" +
                    "Content-Length: 6\r\n" +
                    "\r\n" +
                    "abc",
            ),
        )
        pipeline.notifyRead(bufOf("def"))

        assertEquals(1, collector.requests.size)
        assertEquals("abcdef", collector.requests[0].body!!.decodeToString())
        // The held chunks must be released by the end-of-body flatten.
        tracker.assertNoLeaks("every held + input IoBuf must be released after aggregation")
    }

    @Test
    fun `a connection closed mid-body releases the held body chunks`() {
        val (pipeline, collector) = createPipeline()

        // A POST that declares a 10000-byte body but delivers only a small
        // prefix; the aggregator holds the partial chunk waiting for more.
        pipeline.notifyRead(
            bufOf(
                "POST /upload HTTP/1.1\r\n" +
                    "Host: example.com\r\n" +
                    "Content-Length: 10000\r\n" +
                    "\r\n" +
                    "partial-body-bytes",
            ),
        )
        // The peer drops the connection before the body completes.
        pipeline.notifyInactive()

        assertEquals(0, collector.requests.size)
        tracker.assertNoLeaks("a connection closed mid-body must release the held body chunks")
    }

    @Test
    fun `a connection closed mid-body releases the recv buffer retained by head headers`() {
        val (pipeline, collector) = createPipeline()

        // Power-of-two recv buffer: the decoder stores the header values as
        // addRange zero-copy views that retain THIS buffer in head.headers.
        // On a mid-body close the aggregator is the sole owner of that head, so
        // it must release head.headers too — not just the body chunks —
        // otherwise the (much larger) recv buffer leaks (slow-loris).
        pipeline.notifyRead(
            bufOfPow2(
                "POST /upload HTTP/1.1\r\n" +
                    "Host: example.com\r\n" +
                    "Content-Length: 10000\r\n" +
                    "\r\n" +
                    "partial",
            ),
        )
        pipeline.notifyInactive()

        assertEquals(0, collector.requests.size)
        tracker.assertNoLeaks("mid-body close must release the recv buffer retained by head.headers")
    }

    @Test
    fun `stray HttpBodyEnd without preceding head is ignored defensively`() {
        val collector = RequestCollector()
        val pipeline = channel.pipeline
        pipeline.addLast("aggregator", HttpBodyAggregator())
        pipeline.addLast("collector", collector)

        // Directly feed an HttpBodyEnd without a prior HttpRequestHead.
        pipeline.notifyRead(HttpBodyEnd.EMPTY)

        assertEquals(0, collector.requests.size)
        assertEquals(0, collector.errors.size)
    }

    @Test
    fun `a new head before HttpBodyEnd releases the chunks held for the previous request`() {
        val collector = RequestCollector()
        val pipeline = channel.pipeline
        pipeline.addLast("aggregator", HttpBodyAggregator())
        pipeline.addLast("collector", collector)

        // First request: a head and a held body chunk, but no HttpBodyEnd.
        pipeline.notifyRead(HttpRequestHead(HttpMethod.POST, "/first"))
        pipeline.notifyRead(HttpBody(bufOf("orphaned")))
        // A second head arrives mid-body: startAggregation must release the
        // chunk held for the first request before starting the second.
        pipeline.notifyRead(HttpRequestHead(HttpMethod.POST, "/second"))
        pipeline.notifyRead(HttpBodyEnd.EMPTY)

        assertEquals(1, collector.requests.size)
        assertEquals("/second", collector.requests[0].path)
        assertNull(collector.requests[0].body)
        tracker.assertNoLeaks("a new head mid-body must release the previously held chunk")
    }
}
