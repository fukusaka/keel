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

    /**
     * Like [bufOf] but rounds the capacity up to a power of two, so the
     * decoder's `addRange` fast path retains this recv buffer (its zero-copy
     * header views) instead of falling back to `String` materialisation. A
     * non-power-of-two capacity would never retain the buffer, so the pooled
     * head-header release contract would go untested (the same blind spot an
     * exact-size buffer left on the server aggregator).
     */
    private fun bufOfPow2(text: String, allocator: BufferAllocator): IoBuf {
        val bytes = text.encodeToByteArray()
        var cap = 1
        while (cap < bytes.size) cap = cap shl 1
        val buf = allocator.allocate(cap)
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
    fun `interim 100 head and end are skipped and the final response aggregates`() {
        val pipeline = createPipeline(HttpResponseBodyAggregator())

        pipeline.notifyRead(HttpResponseHead(HttpStatus.CONTINUE))
        pipeline.notifyRead(HttpBodyEnd.EMPTY)
        pipeline.notifyRead(head())
        pipeline.notifyRead(HttpBodyEnd(bufOf("final"), HttpHeaders.EMPTY))

        // The interim 100 must never surface as an aggregated response —
        // a single-receive consumer would take it as the request's answer.
        val response = collector.responses.single()
        assertEquals(HttpStatus.OK, response.status)
        assertEquals("final", response.body?.decodeToString())
        assertTrue(collector.errors.isEmpty())
    }

    @Test
    fun `a real head after an interim head without its terminator still aggregates`() {
        // Defensive: an interim head not followed by its HttpBodyEnd before the
        // next head must not leave interimPending set (which would drop the real
        // response's body). Not reachable through the paired decoder, but the
        // aggregator is a public standalone handler.
        val pipeline = createPipeline(HttpResponseBodyAggregator())

        pipeline.notifyRead(HttpResponseHead(HttpStatus.CONTINUE)) // interim, no terminator
        pipeline.notifyRead(head())
        pipeline.notifyRead(HttpBodyEnd(bufOf("final"), HttpHeaders.EMPTY))

        val response = collector.responses.single()
        assertEquals(HttpStatus.OK, response.status)
        assertEquals("final", response.body?.decodeToString())
        assertTrue(collector.errors.isEmpty())
    }

    @Test
    fun `an interim head arriving mid-body releases the held chunks`() {
        // Defensive: an interim head while chunks are held must release them
        // (mirroring startAggregation's pool-safety), not leak the pool.
        val tracker = TrackingAllocator(DefaultAllocator)
        val pipeline = createPipeline(HttpResponseBodyAggregator())

        pipeline.notifyRead(head())
        pipeline.notifyRead(HttpBody(bufOf("held", tracker)))
        pipeline.notifyRead(HttpResponseHead(HttpStatus.CONTINUE)) // interim mid-body
        // A following real response completes cleanly.
        pipeline.notifyRead(head())
        pipeline.notifyRead(HttpBodyEnd(bufOf("done", tracker), HttpHeaders.EMPTY))

        assertEquals("done", collector.responses.single().body?.decodeToString())
        assertEquals(0, tracker.outstandingCount)
        assertTrue(collector.errors.isEmpty())
    }

    @Test
    fun `101 switching protocols aggregates as a final response`() {
        val pipeline = createPipeline(HttpResponseBodyAggregator())

        pipeline.notifyRead(HttpResponseHead(HttpStatus.SWITCHING_PROTOCOLS))
        pipeline.notifyRead(HttpBodyEnd.EMPTY)

        assertEquals(HttpStatus.SWITCHING_PROTOCOLS, collector.responses.single().status)
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

    // --- Zero-copy pooled-header release contract ---
    //
    // These drive the real HttpResponseDecoder so the emitted head carries
    // pooled headers whose views retain the recv buffer via addRange (the
    // zero-copy path). The aggregator must release those headers on every
    // path that discards the head unemitted, and the success path must
    // transfer that retention to the aggregated response for the consumer to
    // release at the application boundary. The plain HttpHeaders.of heads used
    // above cannot catch a pooled-header leak: release() is a no-op for them.

    private fun trackedDecoderAggregatorPipeline(
        tracker: TrackingAllocator,
        maxContentLength: Int = HttpResponseBodyAggregator.DEFAULT_MAX_CONTENT_LENGTH,
    ): Pair<Pipeline, TestIoTransport> {
        val trackedTransport = TestIoTransport(tracker)
        val trackedChannel = object : AbstractPipelinedChannel(trackedTransport, PrintLogger("tracked")) {}
        val pipeline = trackedChannel.pipeline
        pipeline.addLast("decoder", HttpResponseDecoder())
        pipeline.addLast("aggregator", HttpResponseBodyAggregator(maxContentLength))
        pipeline.addLast("collector", collector)
        return pipeline to trackedTransport
    }

    @Test
    fun `a pooled response head transfers its retained buffer to the aggregated response`() {
        val tracker = TrackingAllocator(DefaultAllocator)
        val (pipeline, trackedTransport) = trackedDecoderAggregatorPipeline(tracker)

        pipeline.notifyRead(
            bufOfPow2("HTTP/1.1 200 OK\r\nContent-Length: 5\r\nX-Tag: t\r\n\r\nhello", tracker),
        )

        val response = collector.responses.single()
        assertEquals("hello", response.body?.decodeToString())
        // The pooled headers still retain the recv buffer, so the views resolve.
        assertEquals("t", response.headers.getString("X-Tag"))
        // Application boundary: releasing the pooled headers frees the buffer.
        response.headers.release()
        trackedTransport.close()
        assertEquals(0, tracker.outstandingCount)
    }

    @Test
    fun `an overflowing pooled response head releases its retained buffer`() {
        val tracker = TrackingAllocator(DefaultAllocator)
        val (pipeline, trackedTransport) = trackedDecoderAggregatorPipeline(tracker, maxContentLength = 4)

        pipeline.notifyRead(
            bufOfPow2("HTTP/1.1 200 OK\r\nContent-Length: 5\r\nX-Tag: t\r\n\r\nhello", tracker),
        )

        assertEquals(1, collector.errors.size)
        assertIs<HttpParseException>(collector.errors[0])
        assertTrue(collector.responses.isEmpty())
        trackedTransport.close()
        assertEquals(0, tracker.outstandingCount)
    }

    @Test
    fun `a connection close mid-body releases the pooled response head buffer`() {
        val tracker = TrackingAllocator(DefaultAllocator)
        val (pipeline, trackedTransport) = trackedDecoderAggregatorPipeline(tracker)

        // Content-Length 10 but only 7 body bytes arrive: the decoder emits the
        // head + a partial HttpBody and awaits the rest, so the head stays held.
        pipeline.notifyRead(
            bufOfPow2("HTTP/1.1 200 OK\r\nContent-Length: 10\r\nX-Tag: t\r\n\r\npartial", tracker),
        )
        pipeline.notifyInactive()

        assertTrue(collector.responses.isEmpty())
        assertEquals(1, collector.errors.size) // truncation
        trackedTransport.close()
        assertEquals(0, tracker.outstandingCount)
    }

    @Test
    fun `an interim response head releases its pooled headers and retained buffer`() {
        val tracker = TrackingAllocator(DefaultAllocator)
        val (pipeline, trackedTransport) = trackedDecoderAggregatorPipeline(tracker)

        // A 103 Early Hints interim head carrying a Link header (stored via
        // addRange → retains the recv buffer), then the final 200 — both in one
        // pow2 recv buffer. The interim head is swallowed by the aggregator and
        // never emitted, so it must release its own pooled headers.
        pipeline.notifyRead(
            bufOfPow2(
                "HTTP/1.1 103 Early Hints\r\nLink: </s.css>; rel=preload\r\n\r\n" +
                    "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nhi",
                tracker,
            ),
        )

        val response = collector.responses.single()
        assertEquals(HttpStatus.OK, response.status)
        assertEquals("hi", response.body?.decodeToString())
        response.headers.release()
        trackedTransport.close()
        assertEquals(0, tracker.outstandingCount)
    }
}
