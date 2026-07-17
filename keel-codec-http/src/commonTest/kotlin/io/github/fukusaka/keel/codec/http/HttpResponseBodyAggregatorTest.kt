package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.BufferAllocator
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpResponseBodyAggregatorTest {

    // --- Test infrastructure ---

    private val transport = TestIoTransport()
    private val channel = object : AbstractPipelinedChannel(transport, PrintLogger("test")) {}

    /** Collects aggregated [HttpResponse] messages, other pass-throughs, and errors. */
    private class ResponseCollector : InboundHandler {
        val responses = mutableListOf<HttpResponse>()
        val others = mutableListOf<Any>()
        val errors = mutableListOf<Throwable>()

        override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
            when (msg) {
                is HttpResponse -> responses.add(msg)
                else -> others.add(msg)
            }
        }

        override fun onError(ctx: PipelineHandlerContext, cause: Throwable) {
            errors.add(cause)
        }
    }

    private val collector = ResponseCollector()

    private fun createPipeline(aggregator: HttpResponseBodyAggregator): Pipeline {
        val pipeline = channel.pipeline
        pipeline.addLast("aggregator", aggregator)
        pipeline.addLast("collector", collector)
        return pipeline
    }

    private fun bufOf(text: String, allocator: BufferAllocator = DefaultAllocator): IoBuf {
        val bytes = text.encodeToByteArray()
        val buf = allocator.allocate(bytes.size)
        buf.writeByteArray(bytes, 0, bytes.size)
        return buf
    }

    private fun head(status: HttpStatus = HttpStatus.OK): HttpResponseHead =
        HttpResponseHead(status, headers = HttpHeaders.of("X-Tag" to "t"))

    // --- Aggregation ---

    @Test
    fun `head plus body chunks plus end aggregate into one HttpResponse`() {
        val pipeline = createPipeline(HttpResponseBodyAggregator())

        pipeline.notifyRead(head())
        pipeline.notifyRead(HttpBody(bufOf("hello ")))
        pipeline.notifyRead(HttpBody(bufOf("wor")))
        pipeline.notifyRead(HttpBodyEnd(bufOf("ld"), HttpHeaders.EMPTY))

        val response = collector.responses.single()
        assertEquals(HttpStatus.OK, response.status)
        assertEquals("t", response.headers.getString("X-Tag"))
        assertEquals("hello world", response.body?.decodeToString())
        assertTrue(collector.errors.isEmpty())
    }

    @Test
    fun `bodyless sequence aggregates into a null body`() {
        val pipeline = createPipeline(HttpResponseBodyAggregator())

        pipeline.notifyRead(head(HttpStatus.NO_CONTENT))
        pipeline.notifyRead(HttpBodyEnd.EMPTY)

        val response = collector.responses.single()
        assertEquals(HttpStatus.NO_CONTENT, response.status)
        assertNull(response.body)
        assertTrue(collector.errors.isEmpty())
    }

    @Test
    fun `two consecutive responses aggregate independently`() {
        val pipeline = createPipeline(HttpResponseBodyAggregator())

        pipeline.notifyRead(head())
        pipeline.notifyRead(HttpBodyEnd(bufOf("one"), HttpHeaders.EMPTY))
        pipeline.notifyRead(head(HttpStatus.NOT_FOUND))
        pipeline.notifyRead(HttpBodyEnd(bufOf("two"), HttpHeaders.EMPTY))

        assertEquals(2, collector.responses.size)
        assertEquals("one", collector.responses[0].body?.decodeToString())
        assertEquals("two", collector.responses[1].body?.decodeToString())
        assertTrue(collector.errors.isEmpty())
    }

    @Test
    fun `non-HTTP messages pass through unchanged`() {
        val pipeline = createPipeline(HttpResponseBodyAggregator())

        pipeline.notifyRead("not an http message")

        assertEquals("not an http message", collector.others.single())
        assertTrue(collector.responses.isEmpty())
        assertTrue(collector.errors.isEmpty())
    }

    // --- Overflow and lifecycle ---

    @Test
    fun `body over maxContentLength propagates an error and releases chunks`() {
        val tracker = TrackingAllocator(DefaultAllocator)
        val pipeline = createPipeline(HttpResponseBodyAggregator(maxContentLength = 8))

        pipeline.notifyRead(head())
        pipeline.notifyRead(HttpBody(bufOf("12345", tracker)))
        pipeline.notifyRead(HttpBody(bufOf("67890", tracker)))
        pipeline.notifyRead(HttpBodyEnd(bufOf("", tracker), HttpHeaders.EMPTY))

        assertEquals(1, collector.errors.size)
        assertIs<HttpParseException>(collector.errors[0])
        assertTrue(collector.responses.isEmpty())
        assertEquals(0, tracker.outstandingCount)
    }

    @Test
    fun `connection close mid aggregation releases held chunks`() {
        val tracker = TrackingAllocator(DefaultAllocator)
        val pipeline = createPipeline(HttpResponseBodyAggregator())

        pipeline.notifyRead(head())
        pipeline.notifyRead(HttpBody(bufOf("partial", tracker)))
        pipeline.notifyInactive()

        assertEquals(0, tracker.outstandingCount)
        assertTrue(collector.responses.isEmpty())
    }

    @Test
    fun `aggregation balances every buffer allocation`() {
        val tracker = TrackingAllocator(DefaultAllocator)
        val pipeline = createPipeline(HttpResponseBodyAggregator())

        pipeline.notifyRead(head())
        pipeline.notifyRead(HttpBody(bufOf("abc", tracker)))
        pipeline.notifyRead(HttpBodyEnd(bufOf("def", tracker), HttpHeaders.EMPTY))

        assertEquals("abcdef", collector.responses.single().body?.decodeToString())
        assertEquals(0, tracker.outstandingCount)
    }
}
