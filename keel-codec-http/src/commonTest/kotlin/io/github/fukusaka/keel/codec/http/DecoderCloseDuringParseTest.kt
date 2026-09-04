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
 * not alias its pooled accumulator, does not strand what the accumulator
 * holds, and does not disturb what the decoder was doing before it.
 *
 * The decoders release their borrowed [HttpHeaders] on `onInactive`. A
 * handler that closes the channel synchronously from a request head — a
 * server's shutdown drain does exactly that — raises the ending *inside* the
 * decoder's parse frame. Two things used to go wrong there. The field kept
 * pointing at the released instance, so the frame wrote into an accumulator
 * the pool had handed on: measured before the fix, three pipelined requests
 * with a close from the first head delivered the second request's header
 * values on the third, and a second connection's already-emitted head came
 * back carrying a header the first had parsed. And the frame kept decoding
 * after the ending at all, emitting messages nobody could answer and taking
 * borrows nobody would give back.
 *
 * Both are closed by construction now — the borrow has an owner
 * ([BorrowedHeaders]) and the decoder ends ([DecoderEndedStateTest] pins
 * what "ends" means). What these cases pin is the surroundings: that a close
 * from a head leaves that head and every other connection alone, that what
 * the decoder was in the middle of *before* the ending — a header line or
 * block straddling a read, a message with no fields — decodes the same as it
 * would have without one, and that every borrow and every recv buffer comes
 * back.
 *
 * Every case runs on a [TrackingAllocator] and asserts the recv buffers are
 * all back. Claiming the accumulator is only half the problem: whatever is
 * claimed inherits the parse, and an instance that cannot release the buffers
 * its range entries retain turns the aliasing into a leak.
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
    fun `the same bytes decode the same however the reads are split`() {
        val request = { marker: String -> "GET /$marker HTTP/1.1\r\nHost: h\r\nX-M: $marker\r\n\r\n" }

        // The close comes from the first head, and after it nothing is
        // decoded -- so the cut falls at the ending wherever the peer's
        // segments fall, including inside the very message that raises it.
        // Splits after the ending are the other class's concern; these cut
        // `/a` itself, in its request line and in its header block.
        val whole = decodeMarkers(listOf(request("a") + request("b") + request("c")))
        val inRequestLine = decodeMarkers(listOf("GET /a HT", "TP/1.1\r\nHost: h\r\nX-M: a\r\n\r\n" + request("b")))
        val inHeaders = decodeMarkers(listOf("GET /a HTTP/1.1\r\nHost: h\r\nX-", "M: a\r\n\r\n" + request("b")))

        assertEquals(listOf("a"), whole)
        assertEquals(whole, inRequestLine, "a split inside the request line does not move the cut")
        assertEquals(whole, inHeaders, "a split inside the header block does not move the cut")
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

        // Half the connections read on past the ending, half stop at it. Both
        // halves pin that the ending leaves nothing borrowed: the ones that
        // stop catch a replacement taken at the ending; the ones that read on
        // are fed a header block that never completes, so a decoder that
        // still decoded it would take a borrow nothing gives back.
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

            val (message, unfinished) = if (label == "response") {
                "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nX-M: a\r\n\r\n" to "HTTP/1.1 200 OK\r\nX-M: b\r\n"
            } else {
                "GET /x HTTP/1.1\r\nHost: h\r\n\r\n" to "GET /y HTTP/1.1\r\nHost: h\r\n"
            }
            transport.onRead?.invoke(bufOf(message, tracker))
            channel.close()
            // A read after the ending, which is what a drained connection
            // actually looks like -- and one that never completes its header
            // block, so that a decoder still decoding it would hold a borrow
            // with no second ending to give it back. The decoder has ended,
            // so the read is not decoded and nothing is borrowed.
            if (round % 2 == 0) transport.onRead?.invoke(bufOf(unfinished, tracker))
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
    fun `a request with no header fields is handed a pooled instance of its own`() {
        // A message with no fields writes nothing into the accumulator, so a
        // design that borrowed only on a header write handed the application
        // whatever the slot held -- and the borrow taken for the message
        // after it was the same instance. Measured that way: `[a, c, c]`,
        // the last two sharing one map. The hand-over itself takes a borrow
        // now. The close comes from the last head, so all three are decoded
        // before the ending.
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
                            if (heads.size == 3) channel.close()
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
    fun `a header line split across reads keeps its own fields`() {
        // A header line split across a read boundary, with the close coming
        // from the last head so the line is decoded before the ending. It goes
        // through the fallback parser that handles straddling lines, a path no
        // other case takes -- that, not where the split falls, is what
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
                            if (seen.size == 3) {
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
    fun `a connection closed from a head decodes nothing more and leaves another connection's head alone`() {
        // The cross-connection shape with the close raised from a head: the
        // ending arrives with the slot already empty -- the head took the
        // accumulator away at `transfer` -- a second connection on the same
        // pool then borrows and emits, and only then does the first read
        // again. Before the decoder ended, the first parsed on and, with the
        // field still pointing at what the pool had handed on, connection two's
        // request carried `X-Inj` from connection one. Now the first decodes
        // nothing after its ending and the second's head is exactly its own.
        primeThePool()
        val tracker = TrackingAllocator(DefaultAllocator)
        val first = openConnection(tracker)
        val firstHeads = first.second

        first.first.onRead?.invoke(bufOf("GET /a HTTP/1.1\r\nHost: h\r\n\r\n", tracker))

        val second = openConnection(tracker)
        second.first.onRead?.invoke(bufOf("GET /z HTTP/1.1\r\nHost: z\r\n\r\n", tracker))

        first.first.onRead?.invoke(
            bufOf("GET /b HTTP/1.1\r\nX-Inj: from-first\r\nHost: h\r\n\r\n", tracker),
        )

        assertEquals(listOf("/z"), second.second.map { it.uri }, "the second connection decoded its own request")
        assertNull(second.second[0].headers["X-Inj"], "and was not handed a header the first connection parsed")
        assertEquals(listOf("/a"), firstHeads.map { it.uri }, "the first connection decoded nothing after its ending")
        for (head in firstHeads + second.second) head.headers.release()
        assertEquals(0, tracker.outstandingCount, "every recv buffer is back")
        assertEquals(PRIMED_POOL_SIZE, HttpHeadersPool.size(), "and every borrow came back")
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
    fun `a header block straddling a read keeps its framing`() {
        // The accumulator carries a part-parsed header block across the read
        // boundary. A shape that gave the borrow back between reads discarded
        // the fields already parsed, and the decoder framed what followed
        // against what was left: measured that way, `Content-Length` was
        // lost, the body was framed away, and its bytes were delivered as a
        // request of their own (`HELLOGET /c`). The close comes from the last
        // head, so the straddled block is decoded before the ending; the
        // framing is the assertion.
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
                            if (heads.size == 3) channel.close()
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

        // `/b`'s header block is cut in two: `Content-Length` lands in the
        // first read, the rest in the second.
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
    fun `a rejected request's borrow that another connection takes is not written into`() {
        // The cross-connection shape without an ending. A parse error gives
        // the accumulator back through the same recycle the ending uses, but
        // the decoder keeps decoding: a second connection on the same pool
        // borrows what was given back and emits a head from it, and then the
        // first reads on. A field left pointing at the released instance
        // would write the first connection's next request into the second's
        // already-emitted head. Green since the borrow got an owner; what it
        // pins is that owner's record -- the mutation that gives the borrow
        // back without forgetting it is killed here and nowhere else at
        // decoder level, now that nothing is decoded after an ending.
        val tracker = TrackingAllocator(DefaultAllocator)
        val (first, firstHeads, firstErrors) = openRecordingConnection(tracker)
        // `Host` is checked as the head is built, after the fields are in, so
        // the rejection finds the accumulator held.
        first.onRead?.invoke(bufOf("GET /nohost HTTP/1.1\r\nX-Part: one\r\n\r\n", tracker))
        assertEquals(listOf("HttpParseException"), firstErrors)

        val (second, secondHeads, _) = openRecordingConnection(tracker)
        second.onRead?.invoke(bufOf("GET /z HTTP/1.1\r\nHost: z\r\n\r\n", tracker))

        first.onRead?.invoke(bufOf("GET /b HTTP/1.1\r\nX-Inj: from-first\r\nHost: h\r\n\r\n", tracker))

        assertEquals(listOf("/z"), secondHeads.map { it.uri })
        assertNull(secondHeads[0].headers["X-Inj"], "the second connection's head is not written into by the first")
        assertEquals(listOf("/b"), firstHeads.map { it.uri }, "and the first decoded its next request")
        for (head in firstHeads + secondHeads) head.headers.release()
        assertEquals(0, tracker.outstandingCount, "every recv buffer is back")
    }

    @Test
    fun `a rejected response's borrow that another connection takes is not written into`() {
        // The case above on the client side; an invalid `Content-Length` is
        // rejected as the head is built, with the accumulator held.
        val tracker = TrackingAllocator(DefaultAllocator)
        val (first, firstHeads, firstErrors) = openRecordingResponseConnection(tracker)
        first.onRead?.invoke(bufOf("HTTP/1.1 200 OK\r\nX-Part: one\r\nContent-Length: abc\r\n\r\n", tracker))
        assertEquals(listOf("HttpParseException"), firstErrors)

        val (second, secondHeads, _) = openRecordingResponseConnection(tracker)
        second.onRead?.invoke(bufOf("HTTP/1.1 201 Created\r\nContent-Length: 0\r\n\r\n", tracker))

        first.onRead?.invoke(bufOf("HTTP/1.1 204 No Content\r\nX-Inj: from-first\r\n\r\n", tracker))

        assertEquals(listOf(201), secondHeads.map { it.status.code })
        assertEquals(0L, secondHeads[0].headers.contentLength, "its framing header survives")
        assertNull(secondHeads[0].headers["X-Inj"], "and it gains nothing from the first connection")
        assertEquals(listOf(204), firstHeads.map { it.status.code }, "and the first decoded its next response")
        for (head in firstHeads + secondHeads) head.headers.release()
        assertEquals(0, tracker.outstandingCount, "every recv buffer is back")
    }

    /** A request-decoding channel that records heads and errors and never closes itself. */
    private fun openRecordingConnection(
        tracker: TrackingAllocator,
    ): Triple<TestIoTransport, MutableList<HttpRequestHead>, MutableList<String>> {
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
        return Triple(transport, heads, errors)
    }

    /** The response-decoding twin of [openRecordingConnection]. */
    private fun openRecordingResponseConnection(
        tracker: TrackingAllocator,
    ): Triple<TestIoTransport, MutableList<HttpResponseHead>, MutableList<String>> {
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
        return Triple(transport, heads, errors)
    }

    @Test
    fun `a response connection that reads after its ending decodes nothing and leaves another's head alone`() {
        // The held-at-ending shape on the client side: the read stops inside a
        // header block and the channel closes there, a second connection then
        // borrows what the ending gave back and emits a head from it, and only
        // then does the first read again. Measured on `main`, the second
        // connection's already-emitted head lost its `Content-Length` -- the
        // first's reset wiped the instance that head owns. Now the first
        // decodes nothing after its ending: no reset, no borrow, no head.
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
        assertEquals(
            emptyList(),
            firstHeads.map { it.status.code },
            "the first connection decoded nothing after its ending",
        )
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

    private fun primeThePool() {
        HttpHeadersPool.clear()
        val primed = List(PRIMED_POOL_SIZE) { HttpHeaders.borrow() }
        for (instance in primed) instance.release()
    }

    private companion object {
        /** Instances put in the pool before a run, and expected back after it. */
        const val PRIMED_POOL_SIZE: Int = 4
    }
}
