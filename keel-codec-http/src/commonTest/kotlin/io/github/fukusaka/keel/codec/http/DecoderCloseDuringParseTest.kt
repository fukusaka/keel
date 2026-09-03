package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import io.github.fukusaka.keel.pipeline.DuplexHandler
import io.github.fukusaka.keel.pipeline.PipelineHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull

/**
 * That a close raised from inside a decoder's own downstream dispatch does
 * not alias its pooled accumulator, and does not strand what the accumulator
 * holds.
 *
 * The decoders release their borrowed [HttpHeaders] on `onInactive`. A
 * handler that closes the channel synchronously from a request head — a
 * server's shutdown drain does exactly that — raises the ending *inside* the
 * decoder's parse frame, and the frame keeps writing into the accumulator
 * after control returns. Left on the released instance, the field hands it
 * back at the next borrow and two requests share one header map: measured
 * before the fix, three pipelined requests with a close from the first head
 * delivered the second request's header values on the third.
 *
 * Every case runs on a [TrackingAllocator] and ends by asserting the recv
 * buffers are all back. Claiming the accumulator is only half the problem:
 * whatever is claimed inherits the parse, and an instance that cannot release
 * the buffers its range entries retain turns the aliasing into a leak.
 *
 * The sharpest shape has its own case: an ending that finds a borrow still
 * held, a second connection then taking it, and the first parsing on. Measured
 * on `main`, the second connection's already-emitted head came back carrying a
 * header the first connection had parsed.
 */
class DecoderCloseDuringParseTest {

    private val logger = PrintLogger("DecoderCloseDuringParseTest")

    /**
     * The pool is process-wide and per-thread, so a case that primes it would
     * otherwise hand its leftovers to whatever runs next -- which turns a
     * later case green or red for reasons of its own.
     */
    @AfterTest
    fun emptyThePool() {
        HttpHeadersPool.clear()
    }

    /**
     * A buffer whose capacity is a power of two, which is what the engines
     * hand a decoder. `HttpHeaders` only takes its zero-copy range path on
     * such a capacity (`chainIndexFor` rejects any other), and that path is
     * both where an aliased instance shares another request's values and
     * where a retained recv buffer can be stranded — so sizing exactly to
     * the text would test the copying path alone.
     */
    private fun bufOf(text: String, allocator: TrackingAllocator): IoBuf {
        val bytes = text.encodeToByteArray()
        var capacity = 1
        while (capacity < bytes.size) capacity = capacity shl 1
        val buf = allocator.allocate(capacity)
        for (b in bytes) buf.writeByte(b)
        return buf
    }

    @Test
    fun `a close from the first head does not alias the later requests' headers`() {
        val tracker = TrackingAllocator(DefaultAllocator)
        val transport = TestIoTransport(tracker)
        lateinit var channel: PipelinedChannel
        channel = object : AbstractPipelinedChannel(transport, logger) {}
        val heads = mutableListOf<HttpRequestHead>()
        channel.pipeline.addLast("decoder", HttpRequestDecoder())
        channel.pipeline.addLast(
            "sink",
            object : DuplexHandler {
                override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
                    when (msg) {
                        is HttpRequestHead -> {
                            heads.add(msg)
                            // What a server's shutdown drain does to a
                            // request that arrives while it is draining.
                            if (heads.size == 1) channel.close()
                        }
                        is IoBuf -> msg.release()
                        is HttpBody -> msg.content.release()
                    }
                }
            },
        )
        val request = { marker: String -> "GET /$marker HTTP/1.1\r\nHost: h\r\nX-M: $marker\r\n\r\n" }

        transport.onRead?.invoke(bufOf(request("a") + request("b") + request("c"), tracker))

        assertEquals(
            listOf("a", "b", "c"),
            heads.map { it.headers["X-M"]?.toString() },
            "each pipelined request carries its own header values",
        )
        assertNotSame(
            heads[1].headers,
            heads[2].headers,
            "two requests must not share one pooled HttpHeaders instance",
        )
        // Held until here so the identity check above sees them live; a
        // pooled instance is legitimately reused once released.
        for (head in heads) head.headers.release()
        assertEquals(0, tracker.outstandingCount, "every recv buffer the heads retained is back")
    }

    @Test
    fun `a close from the first response head does not alias the later responses' headers`() {
        val tracker = TrackingAllocator(DefaultAllocator)
        val transport = TestIoTransport(tracker)
        lateinit var channel: PipelinedChannel
        channel = object : AbstractPipelinedChannel(transport, logger) {}
        val heads = mutableListOf<HttpResponseHead>()
        channel.pipeline.addLast("decoder", HttpResponseDecoder())
        channel.pipeline.addLast(
            "sink",
            object : DuplexHandler {
                override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
                    when (msg) {
                        is HttpResponseHead -> {
                            heads.add(msg)
                            if (heads.size == 1) channel.close()
                        }
                        is IoBuf -> msg.release()
                        is HttpBody -> msg.content.release()
                    }
                }
            },
        )
        val response = { marker: String ->
            "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nX-M: $marker\r\n\r\n"
        }

        transport.onRead?.invoke(bufOf(response("a") + response("b") + response("c"), tracker))

        assertEquals(
            listOf("a", "b", "c"),
            heads.map { it.headers["X-M"]?.toString() },
            "each pipelined response carries its own header values",
        )
        assertNotSame(heads[1].headers, heads[2].headers)
        for (head in heads) head.headers.release()
        assertEquals(0, tracker.outstandingCount, "every recv buffer the heads retained is back")
    }

    @Test
    fun `the same bytes decode the same however the reads are split`() {
        val request = { marker: String -> "GET /$marker HTTP/1.1\r\nHost: h\r\nX-M: $marker\r\n\r\n" }

        // One read carrying all four, and the same four split at a message
        // boundary — the close comes from the first head either way. The
        // decoder decodes what arrives, before the ending and after it, and
        // this pins that the fix did not change that: an earlier attempt
        // dropped post-ending reads, which lost the fourth request in the
        // split run alone. A split inside a header block is a different
        // question and has its own case; this one says nothing about it.
        val whole = decodeMarkers(listOf(request("a") + request("b") + request("c") + request("d")))
        val split = decodeMarkers(listOf(request("a") + request("b") + request("c"), request("d")))

        assertEquals(listOf("a", "b", "c", "d"), whole)
        assertEquals(
            whole,
            split,
            "a split at a message boundary does not change which requests are decoded",
        )
    }

    @Test
    fun `the ending gives back the buffer its accumulator was holding`() {
        // What the ending's release is for. The read is cut off inside a
        // second request's header block, so the accumulator is holding range
        // entries -- and therefore a retain on the recv buffer -- at the
        // moment the ending arrives. An ending that finds an empty
        // accumulator has nothing to give back and would pass either way.
        //
        // What is asserted is the buffer, not the pool's arithmetic. Two
        // shapes satisfy the invariant -- recycling the instance in place
        // (release, then borrow it straight back) and swapping in another
        // one -- and they leave different counts in the pool. Asserting the
        // count would pick one of them, which is an implementation detail;
        // asserting the buffer is what the release is for.
        val tracker = TrackingAllocator(DefaultAllocator)
        val transport = TestIoTransport(tracker)
        val channel = object : AbstractPipelinedChannel(transport, logger) {}
        val heads = mutableListOf<HttpRequestHead>()
        channel.pipeline.addLast("decoder", HttpRequestDecoder())
        channel.pipeline.addLast(
            "sink",
            object : DuplexHandler {
                override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
                    when (msg) {
                        is HttpRequestHead -> heads.add(msg)
                        is IoBuf -> msg.release()
                        is HttpBody -> msg.content.release()
                    }
                }
            },
        )

        // One complete request, then a second whose header block is cut off
        // mid-way, so the accumulator still holds it when the close lands.
        transport.onRead?.invoke(
            bufOf("GET /a HTTP/1.1\r\nHost: h\r\n\r\nGET /b HTTP/1.1\r\nX-M: b\r\n", tracker),
        )
        channel.close()

        for (head in heads) head.headers.release()
        assertEquals(0, tracker.outstandingCount, "the ending gives back the recv buffer it retained")
    }

    @Test
    fun `the response decoder's ending gives back the buffer it was holding`() {
        // The request decoder's case above, on the client side. Measured, no
        // case in the module catches a deleted release on this side -- this
        // one included -- because the response decoder's `onInactive` routes
        // every truncation state through a reset that has already returned the
        // buffer, so the trailing release finds nothing to give back. It is
        // here so the two decoders are covered alike.
        val tracker = TrackingAllocator(DefaultAllocator)
        val transport = TestIoTransport(tracker)
        val channel = object : AbstractPipelinedChannel(transport, logger) {}
        val heads = mutableListOf<HttpResponseHead>()
        channel.pipeline.addLast("decoder", HttpResponseDecoder())
        channel.pipeline.addLast(
            "sink",
            object : DuplexHandler {
                override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
                    when (msg) {
                        is HttpResponseHead -> heads.add(msg)
                        is IoBuf -> msg.release()
                        is HttpBody -> msg.content.release()
                    }
                }
            },
        )

        transport.onRead?.invoke(
            bufOf(
                "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n" +
                    "HTTP/1.1 200 OK\r\nX-M: b\r\n",
                tracker,
            ),
        )
        channel.close()

        for (head in heads) head.headers.release()
        assertEquals(0, tracker.outstandingCount, "the ending gives back the recv buffer it retained")
    }

    @Test
    fun `a connection that opens and serves and closes leaves the pool as it found it`() {
        // The other side of the fix. The ending returns the accumulator and
        // holds nothing; a borrow is taken only by whoever next uses it.
        // Taking one at the ending instead would be simpler and is equally
        // free of aliasing, but the connection is over and usually nothing
        // writes again, so that borrow is never given back: measured, a primed
        // pool drained by one for every connection and then allocated a fresh
        // accumulator for each one after that — the per-connection cost this
        // release exists to avoid.
        //
        // Both timings of the head's own release, because they move what the
        // pool holds when the ending runs.
        for (releaseDuringDispatch in listOf(true, false)) {
            assertLeavesThePoolAsItFoundIt("request", releaseDuringDispatch) { HttpRequestDecoder() }
            assertLeavesThePoolAsItFoundIt("response", releaseDuringDispatch) { HttpResponseDecoder() }
        }
    }

    /**
     * Primes the pool, runs six short connections through a fresh [decoder]
     * each, and asserts the pool is back to what it was primed with.
     */
    private fun assertLeavesThePoolAsItFoundIt(
        label: String,
        releaseDuringDispatch: Boolean,
        decoder: () -> PipelineHandler,
    ) {
        HttpHeadersPool.clear()
        val primed = List(PRIMED_POOL_SIZE) { HttpHeaders.borrow() }
        for (instance in primed) instance.release()

        // Half the connections read on past the ending, half stop at it. The
        // second half is what catches a replacement taken at the ending: a
        // post-ending read carries such a borrow away in the message it
        // completes, so a connection that simply stops is the only one where
        // the replacement is left behind.
        repeat(6) { round ->
            val tracker = TrackingAllocator(DefaultAllocator)
            val transport = TestIoTransport(tracker)
            val channel = object : AbstractPipelinedChannel(transport, logger) {}
            val held = mutableListOf<HttpHeaders>()
            channel.pipeline.addLast("decoder", decoder())
            channel.pipeline.addLast(
                "sink",
                object : DuplexHandler {
                    override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
                        val headers = when (msg) {
                            is HttpRequestHead -> msg.headers
                            is HttpResponseHead -> msg.headers
                            is IoBuf -> null.also { msg.release() }
                            is HttpBody -> null.also { msg.content.release() }
                            else -> null
                        } ?: return
                        if (releaseDuringDispatch) headers.release() else held.add(headers)
                    }
                },
            )

            val message = if (label == "response") {
                "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nX-M: a\r\n\r\n"
            } else {
                "GET /x HTTP/1.1\r\nHost: h\r\n\r\n"
            }
            transport.onRead?.invoke(bufOf(message, tracker))
            channel.close()
            // A read after the ending, which is what a drained connection
            // actually looks like. Its borrow has no second ending to give it
            // back, so the message it fills has to carry it away on its own.
            if (round % 2 == 0) transport.onRead?.invoke(bufOf(message, tracker))
            for (headers in held) headers.release()

            assertEquals(0, tracker.outstandingCount, "$label: every recv buffer is back")
        }

        assertEquals(
            PRIMED_POOL_SIZE,
            HttpHeadersPool.size(),
            "$label: the connections gave back every accumulator they took " +
                "(head released during dispatch: " + releaseDuringDispatch + ")",
        )
    }

    @Test
    fun `a request with no header fields after the ending is not handed the pool's accumulator`() {
        // A shape that guarded only the sites writing a header field left this
        // one unguarded: a message with no fields writes nothing, so nothing
        // claimed, and the accumulator reached the application straight from
        // the pool -- with the borrow taken for the message after it being the
        // same instance. Measured that way: `[a, c, c]`, the last two sharing
        // one map. (This design claims on first use, so the hand-over itself
        // takes a borrow.)
        val tracker = TrackingAllocator(DefaultAllocator)
        val transport = TestIoTransport(tracker)
        lateinit var channel: PipelinedChannel
        channel = object : AbstractPipelinedChannel(transport, logger) {}
        val heads = mutableListOf<HttpRequestHead>()
        channel.pipeline.addLast("decoder", HttpRequestDecoder())
        channel.pipeline.addLast(
            "sink",
            object : DuplexHandler {
                override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
                    when (msg) {
                        is HttpRequestHead -> {
                            heads.add(msg)
                            if (heads.size == 1) channel.close()
                        }
                        is IoBuf -> msg.release()
                        is HttpBody -> msg.content.release()
                    }
                }
            },
        )

        transport.onRead?.invoke(
            bufOf(
                "GET /a HTTP/1.1\r\nHost: h\r\nX-M: a\r\n\r\n" +
                    "GET /b HTTP/1.0\r\n\r\n" +
                    "GET /c HTTP/1.1\r\nHost: h\r\nX-M: c\r\n\r\n",
                tracker,
            ),
        )

        assertEquals(
            listOf("a", null, "c"),
            heads.map { it.headers["X-M"]?.toString() },
            "the fieldless request carries no marker and does not take the next one's",
        )
        assertNotSame(heads[1].headers, heads[2].headers, "and does not share its map with the next request")

        // And what it is handed comes from the pool: an instance built outside
        // it satisfies both assertions above while never going back, because
        // `HttpHeaders.release` returns early on one that was never pooled.
        HttpHeadersPool.clear()
        heads[1].headers.release()
        assertEquals(1, HttpHeadersPool.size(), "the fieldless request's map is a pooled one")

        for (head in heads) head.headers.release()
        assertEquals(0, tracker.outstandingCount, "every recv buffer is back")
    }

    @Test
    fun `a header line split across reads after the ending keeps its own fields`() {
        // A header line split across the read boundary that follows the close.
        // It goes through the fallback parser that handles straddling lines, a
        // path no other case takes -- that, not where the split falls, is what
        // separates this from its sibling.
        val tracker = TrackingAllocator(DefaultAllocator)
        val transport = TestIoTransport(tracker)
        lateinit var channel: PipelinedChannel
        channel = object : AbstractPipelinedChannel(transport, logger) {}
        val seen = mutableListOf<Pair<String, String?>>()
        val toRelease = mutableListOf<HttpHeaders>()
        channel.pipeline.addLast("decoder", HttpRequestDecoder())
        channel.pipeline.addLast(
            "sink",
            object : DuplexHandler {
                override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
                    when (msg) {
                        is HttpRequestHead -> {
                            // Snapshot at dispatch: the values are read here
                            // because a later alias would overwrite them.
                            seen.add(msg.uri to msg.headers["X-A"]?.toString())
                            toRelease.add(msg.headers)
                            if (seen.size == 1) {
                                channel.close()
                                for (headers in toRelease) headers.release()
                                toRelease.clear()
                            }
                        }
                        is IoBuf -> msg.release()
                        is HttpBody -> msg.content.release()
                    }
                }
            },
        )

        transport.onRead?.invoke(
            bufOf("GET /a HTTP/1.1\r\nHost: h\r\nX-M: a\r\n\r\n" + "GET /b HTTP/1.0\r\nX-A", tracker),
        )
        transport.onRead?.invoke(
            bufOf(": bee\r\nX-M: b\r\n\r\n" + "GET /c HTTP/1.0\r\nX-M: c\r\n\r\n", tracker),
        )

        assertEquals(
            listOf("/a" to null, "/b" to "bee", "/c" to null),
            seen,
            "the straddled field stays on the request that sent it",
        )
        for (headers in toRelease) headers.release()
        assertEquals(0, tracker.outstandingCount, "every recv buffer is back")
    }

    @Test
    fun `another connection's borrow does not become this one's accumulator`() {
        // The worst shape, and the one that decides how the guard is written.
        // The ending returns the accumulator; a second connection on the same
        // pool -- ordinary, since the stack is per event-loop thread -- borrows
        // it; then this connection parses on. Asking the instance whether it
        // is idle in the pool answers "no" at that point, because the other
        // connection now holds it, so a guard written that way goes quiet
        // exactly when the write is worst: measured that way, connection two's
        // request carried `X-Inj` from connection one.
        val tracker = TrackingAllocator(DefaultAllocator)
        val first = openConnection(tracker)
        val firstHeads = first.second

        // The read ends exactly on head one, so the close is the last thing
        // that happens on this connection before it hands control back --
        // nothing parses again, and nothing takes a replacement.
        first.first.onRead?.invoke(bufOf("GET /a HTTP/1.1\r\nHost: h\r\n\r\n", tracker))

        // A second connection opens and borrows from the same pool, taking
        // the instance the ending just returned.
        val second = openConnection(tracker)
        second.first.onRead?.invoke(bufOf("GET /z HTTP/1.1\r\nHost: z\r\n\r\n", tracker))

        // ...and only then does the first connection parse again.
        first.first.onRead?.invoke(
            bufOf("GET /b HTTP/1.1\r\nX-Inj: from-first\r\nHost: h\r\n\r\n", tracker),
        )

        assertEquals(
            listOf("/z"),
            second.second.map { it.uri },
            "the second connection decoded its own request",
        )
        assertNull(
            second.second[0].headers["X-Inj"],
            "and was not handed a header the first connection parsed",
        )
        assertEquals(listOf("/a", "/b"), firstHeads.map { it.uri })
        for (head in firstHeads + second.second) head.headers.release()
        assertEquals(0, tracker.outstandingCount, "every recv buffer is back")
    }

    /**
     * Opens a request-decoding channel on [tracker] whose handler closes it
     * from the first head, and returns its transport with the heads it sees.
     */
    private fun openConnection(
        tracker: TrackingAllocator,
    ): Pair<TestIoTransport, MutableList<HttpRequestHead>> {
        val transport = TestIoTransport(tracker)
        lateinit var channel: PipelinedChannel
        channel = object : AbstractPipelinedChannel(transport, logger) {}
        val heads = mutableListOf<HttpRequestHead>()
        channel.pipeline.addLast("decoder", HttpRequestDecoder())
        channel.pipeline.addLast(
            "sink",
            object : DuplexHandler {
                override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
                    when (msg) {
                        is HttpRequestHead -> {
                            heads.add(msg)
                            if (heads.size == 1) channel.close()
                        }
                        is IoBuf -> msg.release()
                        is HttpBody -> msg.content.release()
                    }
                }
            },
        )
        return transport to heads
    }

    @Test
    fun `a header block straddling a read after the ending keeps its framing`() {
        // The accumulator carries a part-parsed header block across the read
        // boundary. Giving it back between reads discards the fields already
        // parsed, and the decoder frames what follows against what is left:
        // measured that way, `Content-Length` was lost, the body was framed
        // away, and its bytes were delivered as a request of their own
        // (`HELLOGET /c`). The framing is the assertion.
        val tracker = TrackingAllocator(DefaultAllocator)
        val transport = TestIoTransport(tracker)
        lateinit var channel: PipelinedChannel
        channel = object : AbstractPipelinedChannel(transport, logger) {}
        val seen = mutableListOf<String>()
        val heads = mutableListOf<HttpRequestHead>()
        channel.pipeline.addLast("decoder", HttpRequestDecoder())
        channel.pipeline.addLast(
            "sink",
            object : DuplexHandler {
                override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
                    when (msg) {
                        is HttpRequestHead -> {
                            seen.add(msg.uri + " cl=" + msg.headers.contentLength)
                            heads.add(msg)
                            if (heads.size == 1) channel.close()
                        }
                        is HttpBody -> {
                            seen.add("body " + msg.content.readableBytes)
                            msg.content.release()
                        }
                        is IoBuf -> msg.release()
                    }
                }
            },
        )

        // The close comes from `/a`. `/b`'s header block is then cut in two:
        // `Content-Length` lands in the first read, the rest in the second.
        transport.onRead?.invoke(
            bufOf("GET /a HTTP/1.0\r\n\r\n" + "POST /b HTTP/1.0\r\nContent-Length: 5\r\n", tracker),
        )
        transport.onRead?.invoke(
            bufOf("X-M: b\r\n\r\nHELLO" + "GET /c HTTP/1.0\r\n\r\n", tracker),
        )

        assertEquals(
            listOf("/a cl=null", "/b cl=5", "body 5", "/c cl=null"),
            seen.filter { !it.startsWith("body 0") },
            "the straddled Content-Length still frames the body it belongs to",
        )
        for (head in heads) head.headers.release()
        assertEquals(0, tracker.outstandingCount, "every recv buffer is back")
    }

    @Test
    fun `a rejected request's headers do not reach the next one`() {
        // The abandoned-parse half of the same ownership question. A request
        // that fails to parse never reaches a message, so the accumulator it
        // filled is still this decoder's to give back -- and if it is not
        // given back, the fields it holds are still there when the next
        // request is parsed into it.
        val tracker = TrackingAllocator(DefaultAllocator)
        val transport = TestIoTransport(tracker)
        val channel = object : AbstractPipelinedChannel(transport, logger) {}
        val heads = mutableListOf<HttpRequestHead>()
        val errors = mutableListOf<String>()
        channel.pipeline.addLast("decoder", HttpRequestDecoder())
        channel.pipeline.addLast(
            "sink",
            object : DuplexHandler {
                override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
                    when (msg) {
                        is HttpRequestHead -> heads.add(msg)
                        is HttpBody -> msg.content.release()
                        is IoBuf -> msg.release()
                    }
                }

                override fun onError(ctx: PipelineHandlerContext, cause: Throwable) {
                    errors.add(cause::class.simpleName ?: "?")
                }
            },
        )

        // A header line with no colon aborts the parse after `X-Evil` is in.
        transport.onRead?.invoke(
            bufOf("GET /bad HTTP/1.0\r\nX-Evil: yes\r\nnot-a-header\r\n\r\n", tracker),
        )
        transport.onRead?.invoke(bufOf("GET /good HTTP/1.0\r\n\r\n", tracker))

        assertEquals(listOf("HttpParseException"), errors)
        assertEquals(listOf("/good"), heads.map { it.uri })
        assertNull(heads[0].headers["X-Evil"], "the rejected request's field did not carry over")
        assertEquals(0, heads[0].headers.size, "nor any other")
        for (head in heads) head.headers.release()
        assertEquals(0, tracker.outstandingCount, "every recv buffer is back")
    }

    @Test
    fun `a rejected response's headers do not reach the next one`() {
        // The request decoder's case above, on the client side; the abandoned
        // parse goes through the same `resetState`.
        val tracker = TrackingAllocator(DefaultAllocator)
        val transport = TestIoTransport(tracker)
        val channel = object : AbstractPipelinedChannel(transport, logger) {}
        val heads = mutableListOf<HttpResponseHead>()
        val errors = mutableListOf<String>()
        channel.pipeline.addLast("decoder", HttpResponseDecoder())
        channel.pipeline.addLast(
            "sink",
            object : DuplexHandler {
                override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
                    when (msg) {
                        is HttpResponseHead -> heads.add(msg)
                        is HttpBody -> msg.content.release()
                        is IoBuf -> msg.release()
                    }
                }

                override fun onError(ctx: PipelineHandlerContext, cause: Throwable) {
                    errors.add(cause::class.simpleName ?: "?")
                }
            },
        )

        transport.onRead?.invoke(
            bufOf("HTTP/1.1 200 OK\r\nX-Evil: yes\r\nnot-a-header\r\n\r\n", tracker),
        )
        transport.onRead?.invoke(bufOf("HTTP/1.1 204 No Content\r\n\r\n", tracker))

        assertEquals(listOf("HttpParseException"), errors)
        assertEquals(listOf(204), heads.map { it.status.code })
        assertNull(heads[0].headers["X-Evil"], "the rejected response's field did not carry over")
        assertEquals(0, heads[0].headers.size, "nor any other")
        for (head in heads) head.headers.release()
        assertEquals(0, tracker.outstandingCount, "every recv buffer is back")
    }

    @Test
    fun `a connection parsing on after its ending does not write into another's head`() {
        // The sharpest shape. The ending arrives with a borrow held -- the
        // read stopped inside a header block -- a second connection then
        // borrows what it gave back and emits a head from it, and only then
        // does the first connection parse on. Measured on `main`, the second
        // connection's head came back carrying `X-Inj` from the first.
        val tracker = TrackingAllocator(DefaultAllocator)
        val firstHeads = mutableListOf<HttpRequestHead>()
        val secondHeads = mutableListOf<HttpRequestHead>()
        val (first, firstChannel) = openConnection(tracker, firstHeads, closesFromFirstHead = false)

        // The read stops inside `/b`'s header block, so the ending finds the
        // accumulator held -- the close comes from here rather than from a
        // head, where the emitted message would already have taken it away.
        first.onRead?.invoke(bufOf("POST /b HTTP/1.1\r\nX-Part: one\r\n", tracker))
        firstChannel.close()

        // The second connection arrives after the ending and takes what it
        // gave back, emitting a head from it. It is opened here rather than up
        // front because a decoder that borrows at construction would take a
        // different instance and the shape would not form. It does not close:
        // it is the bystander whose head must not be written into.
        val (second, _) = openConnection(tracker, secondHeads, closesFromFirstHead = false)
        second.onRead?.invoke(bufOf("GET /z HTTP/1.1\r\nHost: z\r\n\r\n", tracker))

        // ...and only now does the first connection continue `/b`.
        first.onRead?.invoke(bufOf("X-Inj: from-first\r\nHost: h\r\n\r\n", tracker))

        assertEquals(listOf("/z"), secondHeads.map { it.uri })
        assertNull(
            secondHeads[0].headers["X-Inj"],
            "the second connection's head is not written into by the first",
        )
        for (head in firstHeads + secondHeads) head.headers.release()
        assertEquals(0, tracker.outstandingCount, "every recv buffer is back")
    }

    /**
     * Opens a request-decoding channel on [tracker], recording every head it
     * sees in [heads] and closing itself from the first one when
     * [closesFromFirstHead].
     */
    private fun openConnection(
        tracker: TrackingAllocator,
        heads: MutableList<HttpRequestHead>,
        closesFromFirstHead: Boolean,
    ): Pair<TestIoTransport, PipelinedChannel> {
        val transport = TestIoTransport(tracker)
        lateinit var channel: PipelinedChannel
        channel = object : AbstractPipelinedChannel(transport, logger) {}
        channel.pipeline.addLast("decoder", HttpRequestDecoder())
        channel.pipeline.addLast(
            "sink",
            object : DuplexHandler {
                override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
                    when (msg) {
                        is HttpRequestHead -> {
                            heads.add(msg)
                            if (closesFromFirstHead && heads.size == 1) channel.close()
                        }
                        is HttpBody -> msg.content.release()
                        is IoBuf -> msg.release()
                    }
                }
            },
        )
        return transport to channel
    }

    @Test
    fun `a response connection parsing on after its ending does not write into another's head`() {
        // The request decoder's sharpest case, on the client side. Measured on
        // `main`, the second connection's already-emitted head lost its
        // `Content-Length`: the first connection's reset wiped the instance
        // that head owns, erasing the framing header from a response the
        // application had already been given.
        val tracker = TrackingAllocator(DefaultAllocator)
        val firstHeads = mutableListOf<HttpResponseHead>()
        val secondHeads = mutableListOf<HttpResponseHead>()
        val (first, firstChannel) = openResponseConnection(tracker, firstHeads)

        first.onRead?.invoke(bufOf("HTTP/1.1 200 OK\r\nX-Part: one\r\n", tracker))
        firstChannel.close()

        val (second, _) = openResponseConnection(tracker, secondHeads)
        second.onRead?.invoke(bufOf("HTTP/1.1 201 Created\r\nContent-Length: 0\r\n\r\n", tracker))

        first.onRead?.invoke(bufOf("X-Inj: from-first\r\n\r\n", tracker))

        assertEquals(listOf(201), secondHeads.map { it.status.code })
        assertEquals(0L, secondHeads[0].headers.contentLength, "its framing header survives")
        assertNull(secondHeads[0].headers["X-Inj"], "and it gains nothing from the first connection")
        for (head in firstHeads + secondHeads) head.headers.release()
        assertEquals(0, tracker.outstandingCount, "every recv buffer is back")
    }

    /**
     * Opens a response-decoding channel on [tracker], recording every head it
     * sees in [heads]. It never closes itself; the caller decides when.
     */
    private fun openResponseConnection(
        tracker: TrackingAllocator,
        heads: MutableList<HttpResponseHead>,
    ): Pair<TestIoTransport, PipelinedChannel> {
        val transport = TestIoTransport(tracker)
        val channel = object : AbstractPipelinedChannel(transport, logger) {}
        channel.pipeline.addLast("decoder", HttpResponseDecoder())
        channel.pipeline.addLast(
            "sink",
            object : DuplexHandler {
                override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
                    when (msg) {
                        is HttpResponseHead -> heads.add(msg)
                        is HttpBody -> msg.content.release()
                        is IoBuf -> msg.release()
                    }
                }
            },
        )
        return transport to channel
    }

    /**
     * Feeds [reads] to a fresh request decoder, closing the channel from the
     * first head, and returns each decoded request's `X-M` marker.
     */
    private fun decodeMarkers(reads: List<String>): List<String?> {
        val tracker = TrackingAllocator(DefaultAllocator)
        val transport = TestIoTransport(tracker)
        lateinit var channel: PipelinedChannel
        channel = object : AbstractPipelinedChannel(transport, logger) {}
        val heads = mutableListOf<HttpRequestHead>()
        channel.pipeline.addLast("decoder", HttpRequestDecoder())
        channel.pipeline.addLast(
            "sink",
            object : DuplexHandler {
                override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
                    when (msg) {
                        is HttpRequestHead -> {
                            heads.add(msg)
                            if (heads.size == 1) channel.close()
                        }
                        is IoBuf -> msg.release()
                        is HttpBody -> msg.content.release()
                    }
                }
            },
        )
        for (read in reads) transport.onRead?.invoke(bufOf(read, tracker))
        val markers = heads.map { it.headers["X-M"]?.toString() }
        for (head in heads) head.headers.release()
        assertEquals(0, tracker.outstandingCount, "every recv buffer is back")
        return markers
    }

    private companion object {
        /** Instances put in the pool before a run, and expected back after it. */
        const val PRIMED_POOL_SIZE: Int = 4
    }
}
