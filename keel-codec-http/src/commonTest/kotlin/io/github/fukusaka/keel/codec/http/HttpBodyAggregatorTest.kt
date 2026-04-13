package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import io.github.fukusaka.keel.pipeline.InboundHandler
import io.github.fukusaka.keel.pipeline.Pipeline
import io.github.fukusaka.keel.pipeline.DefaultPipeline
import io.github.fukusaka.keel.pipeline.IoTransport
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.pipeline.SuspendBridgeHandler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpBodyAggregatorTest {

    // --- Test infrastructure ---

    private val transport = object : IoTransport {
        override fun write(buf: IoBuf) {}
        override fun flush(): Boolean = true
        override var onFlushComplete: (() -> Unit)? = null
        override fun close() {}
    }

    private val channel = object : PipelinedChannel {
        override lateinit var pipeline: Pipeline
        override val isActive: Boolean = true
        override val isWritable: Boolean = true
        override val allocator: BufferAllocator get() = DefaultAllocator
        override fun ensureBridge(): SuspendBridgeHandler = error("not needed in tests")
    }

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
        val pipeline = DefaultPipeline(channel, transport, PrintLogger("test"))
        channel.pipeline = pipeline
        pipeline.addLast("decoder", HttpRequestDecoder())
        pipeline.addLast("aggregator", HttpBodyAggregator(maxContentLength))
        pipeline.addLast("collector", collector)
        return pipeline to collector
    }

    private fun bufOf(text: String): IoBuf {
        val bytes = text.encodeToByteArray()
        val buf = DefaultAllocator.allocate(bytes.size)
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
    }

    @Test
    fun `zero-body request yields HttpRequest with null body`() {
        val (pipeline, collector) = createPipeline()

        pipeline.notifyRead(
            bufOf("GET /empty HTTP/1.1\r\nHost: example.com\r\n\r\n"),
        )

        assertEquals(1, collector.requests.size)
        assertNull(collector.requests[0].body)
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
        assertNull(req.headers["Trailer-Key"])
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

        // If IoBufs were not released, the allocator would eventually
        // detect a leak (not asserted here, but the test exercises the
        // release path through the aggregator).
        assertEquals(1, collector.requests.size)
        assertEquals("abcdef", collector.requests[0].body!!.decodeToString())
    }

    @Test
    fun `stray HttpBodyEnd without preceding head is ignored defensively`() {
        val collector = RequestCollector()
        val pipeline = DefaultPipeline(channel, transport, PrintLogger("test"))
        channel.pipeline = pipeline
        pipeline.addLast("aggregator", HttpBodyAggregator())
        pipeline.addLast("collector", collector)

        // Directly feed an HttpBodyEnd without a prior HttpRequestHead.
        pipeline.notifyRead(HttpBodyEnd.EMPTY)

        assertEquals(0, collector.requests.size)
        assertEquals(0, collector.errors.size)
    }
}
