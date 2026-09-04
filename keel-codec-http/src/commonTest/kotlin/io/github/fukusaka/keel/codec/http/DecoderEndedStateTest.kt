package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import io.github.fukusaka.keel.pipeline.DuplexHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Once a connection has ended, neither HTTP decoder decodes another byte.
 *
 * The ending -- `onInactive` -- reaches a decoder on two paths: the read
 * side closed (EOF, error, reset), after which the transport promises no
 * further read; or the channel's own `close()`, which can be raised from
 * inside the decoder's own downstream dispatch while the parse frame is
 * still running. On either path nothing decoded afterwards can be
 * answered, so the decoder enters a terminal state and discards what
 * follows: the rest of the read that carried the ending, later reads, and
 * reads the pipeline's pre-attach journal replays. Nothing is emitted and
 * nothing is borrowed from [HttpHeadersPool].
 *
 * Two states are outside that: `READ_UNTIL_CLOSE`, where the ending is
 * the body's delimiter and completes the message first; and
 * `PASS_THROUGH`, where the decoder is not decoding at all but relaying
 * bytes to a switched protocol whose own handler decides what an ending
 * means for it.
 *
 * Every case runs on a [TrackingAllocator] and asserts the recv buffers
 * are all back, and the pool is emptied after each.
 */
class DecoderEndedStateTest {

    private val logger = PrintLogger("DecoderEndedStateTest")

    @AfterTest
    fun emptyThePool() {
        HttpHeadersPool.clear()
    }

    /**
     * A buffer whose capacity is a power of two, which is what the engines
     * hand a decoder and the only capacity on which `HttpHeaders` takes its
     * zero-copy range path -- the path that retains the recv buffer.
     */
    private fun bufOf(text: String, allocator: TrackingAllocator): IoBuf {
        val bytes = text.encodeToByteArray()
        var capacity = 1
        while (capacity < bytes.size) capacity = capacity shl 1
        val buf = allocator.allocate(capacity)
        for (b in bytes) buf.writeByte(b)
        return buf
    }

    /**
     * Records everything a decoder emits, as one line per message, and can
     * close the channel from inside the dispatch of the n-th head -- the
     * shape in which the ending arrives while the parse frame is running.
     */
    private inner class Sink(private val closeOnHead: Int?, private val closeOnBody: Int? = null) : DuplexHandler {
        lateinit var channel: PipelinedChannel
        val events = mutableListOf<String>()
        val requestHeads = mutableListOf<HttpRequestHead>()
        val responseHeads = mutableListOf<HttpResponseHead>()
        private var bodies = 0

        override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
            when (msg) {
                is HttpRequestHead -> {
                    requestHeads.add(msg)
                    events.add("head " + msg.uri)
                    if (requestHeads.size == closeOnHead) channel.close()
                }
                is HttpResponseHead -> {
                    responseHeads.add(msg)
                    events.add("head " + msg.status.code)
                    if (responseHeads.size == closeOnHead) channel.close()
                }
                is HttpBodyEnd -> {
                    events.add("end " + msg.content.readableBytes)
                    msg.content.release()
                }
                is HttpBody -> {
                    events.add("body " + msg.content.readableBytes)
                    msg.content.release()
                    if (++bodies == closeOnBody) channel.close()
                }
                is IoBuf -> {
                    val bytes = ByteArray(msg.readableBytes)
                    msg.readByteArray(bytes, 0, bytes.size)
                    events.add("raw " + bytes.decodeToString())
                    msg.release()
                }
            }
        }

        override fun onError(ctx: PipelineHandlerContext, cause: Throwable) {
            events.add("error " + (cause::class.simpleName ?: "?"))
        }

        fun releaseHeads() {
            for (head in requestHeads) head.headers.release()
            for (head in responseHeads) head.headers.release()
        }
    }

    private class Connection(
        val transport: TestIoTransport,
        val channel: PipelinedChannel,
        val sink: Sink,
    )

    private fun openRequestConnection(
        tracker: TrackingAllocator,
        closeOnHead: Int? = null,
        closeOnBody: Int? = null,
    ): Connection {
        val transport = TestIoTransport(tracker)
        val sink = Sink(closeOnHead, closeOnBody)
        val channel = object : AbstractPipelinedChannel(transport, logger) {}
        sink.channel = channel
        channel.pipeline.addLast("decoder", HttpRequestDecoder())
        channel.pipeline.addLast("sink", sink)
        return Connection(transport, channel, sink)
    }

    private fun openResponseConnection(
        tracker: TrackingAllocator,
        closeOnHead: Int? = null,
        closeOnBody: Int? = null,
    ): Connection {
        val transport = TestIoTransport(tracker)
        val sink = Sink(closeOnHead, closeOnBody)
        val channel = object : AbstractPipelinedChannel(transport, logger) {}
        sink.channel = channel
        channel.pipeline.addLast("decoder", HttpResponseDecoder())
        channel.pipeline.addLast("sink", sink)
        return Connection(transport, channel, sink)
    }

    private fun Connection.read(text: String, tracker: TrackingAllocator) {
        transport.onRead?.invoke(bufOf(text, tracker))
    }

    private fun primeThePool(size: Int) {
        HttpHeadersPool.clear()
        val borrowed = List(size) { HttpHeaders.borrow() }
        for (h in borrowed) h.release()
        assertEquals(size, HttpHeadersPool.size(), "the pool is primed")
    }

    // --- R1: nothing is emitted after the ending ---

    @Test
    fun `a request decoder emits nothing after its ending`() {
        val tracker = TrackingAllocator(DefaultAllocator)
        val c = openRequestConnection(tracker, closeOnHead = 1)
        c.read(REQ_A + REQ_B + REQ_C, tracker)
        // `/a`'s own empty body end is part of the message already being
        // emitted; it carries no bytes and is the last thing delivered.
        assertEquals(listOf("head /a", "end 0"), c.sink.events)
        c.sink.releaseHeads()
        assertEquals(0, tracker.outstandingCount, "every recv buffer is back")
    }

    @Test
    fun `a response decoder emits nothing after its ending`() {
        val tracker = TrackingAllocator(DefaultAllocator)
        val c = openResponseConnection(tracker, closeOnHead = 1)
        c.read(RESP_200 + RESP_201 + RESP_202, tracker)
        assertEquals(listOf("head 200", "end 0"), c.sink.events)
        c.sink.releaseHeads()
        assertEquals(0, tracker.outstandingCount, "every recv buffer is back")
    }

    @Test
    fun `a body that follows the ending head is not decoded`() {
        // The head that raises the ending announces a body. The body's bytes
        // are after the ending, so they are not framed into chunks -- the
        // message stays a bare head, which is what a client would have seen
        // had the connection dropped there.
        val tracker = TrackingAllocator(DefaultAllocator)
        val c = openRequestConnection(tracker, closeOnHead = 1)
        c.read("POST /p HTTP/1.1\r\nHost: h\r\nContent-Length: 5\r\n\r\nHELLO" + REQ_B, tracker)
        assertEquals(listOf("head /p"), c.sink.events)
        c.sink.releaseHeads()
        assertEquals(0, tracker.outstandingCount, "every recv buffer is back")
    }

    @Test
    fun `a chunked body that follows the ending head is not decoded`() {
        // The head that raises the ending announces a chunked body. The
        // request decoder moves to the chunk-size state after dispatching the
        // head -- a write the absorbing setter has to swallow, as it swallows
        // the fixed-length one. A setter that let this one state through
        // reopened the decoder: the chunk, the body end and the next request
        // were all delivered after the ending, and the header block begun
        // after it stranded a borrow and its recv buffer.
        primeThePool(PRIMED_POOL_SIZE)
        val tracker = TrackingAllocator(DefaultAllocator)
        val c = openRequestConnection(tracker, closeOnHead = 1)
        c.read(
            "POST /p HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nHELLO\r\n0\r\n\r\n" +
                REQ_B + "GET /after HTTP/1.1\r\nX-Part: one\r\n",
            tracker,
        )
        assertEquals(listOf("head /p"), c.sink.events)
        c.sink.releaseHeads()
        assertEquals(0, tracker.outstandingCount, "every recv buffer is back")
        assertEquals(PRIMED_POOL_SIZE, HttpHeadersPool.size(), "the pool is as it was primed")
    }

    @Test
    fun `a chunked response body that follows the ending head is reported once and not decoded`() {
        // The client side of the case above. The response decoder reports the
        // body the ending cut short -- its caller is waiting on it -- and then
        // decodes nothing: not the chunk, not the next response.
        primeThePool(PRIMED_POOL_SIZE)
        val tracker = TrackingAllocator(DefaultAllocator)
        val c = openResponseConnection(tracker, closeOnHead = 1)
        c.read(
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nHELLO\r\n0\r\n\r\n" + RESP_201,
            tracker,
        )
        assertEquals(listOf("head 200", "error HttpEofException"), c.sink.events)
        c.sink.releaseHeads()
        assertEquals(0, tracker.outstandingCount, "every recv buffer is back")
        assertEquals(PRIMED_POOL_SIZE, HttpHeadersPool.size(), "the pool is as it was primed")
    }

    @Test
    fun `an ending raised from inside a body chunk stops the chunked body`() {
        // The ending can come from any dispatch, not only a head's. After a
        // chunk is dispatched the request decoder moves to the chunk's
        // trailing CRLF state -- the one transition written after a body
        // dispatch -- and the setter has to swallow that one too. A setter
        // that let it through reopened the decoder from inside the body:
        // measured, the remaining chunk, the body end and the next request
        // were delivered after the ending, with a borrow and its recv buffer
        // stranded.
        primeThePool(PRIMED_POOL_SIZE)
        val tracker = TrackingAllocator(DefaultAllocator)
        val c = openRequestConnection(tracker, closeOnBody = 1)
        c.read(
            "POST /p HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nHELLO\r\n5\r\nWORLD\r\n0\r\n\r\n" +
                REQ_B + "GET /after HTTP/1.1\r\nX-Part: one\r\n",
            tracker,
        )
        assertEquals(listOf("head /p", "body 5"), c.sink.events)
        c.sink.releaseHeads()
        assertEquals(0, tracker.outstandingCount, "every recv buffer is back")
        assertEquals(PRIMED_POOL_SIZE, HttpHeadersPool.size(), "the pool is as it was primed")
    }

    @Test
    fun `a response ending raised from inside a body chunk stops the chunked body`() {
        // The client side: the truncation is reported once, then nothing.
        primeThePool(PRIMED_POOL_SIZE)
        val tracker = TrackingAllocator(DefaultAllocator)
        val c = openResponseConnection(tracker, closeOnBody = 1)
        c.read(
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nHELLO\r\n5\r\nWORLD\r\n0\r\n\r\n" + RESP_201,
            tracker,
        )
        assertEquals(listOf("head 200", "body 5", "error HttpEofException"), c.sink.events)
        c.sink.releaseHeads()
        assertEquals(0, tracker.outstandingCount, "every recv buffer is back")
        assertEquals(PRIMED_POOL_SIZE, HttpHeadersPool.size(), "the pool is as it was primed")
    }

    // --- R2: the cut falls at the ending wherever a read boundary falls ---

    @Test
    fun `the ending cuts the same bytes at the same place wherever a read boundary falls`() {
        // Before the terminal state, the remainder of the read that carried
        // the ending was still parsed while a later read was not, so the
        // number of messages depended on where the peer's segments fell:
        // measured, four from one read against three from two. Now the cut
        // is the ending itself. Every split point of the byte string is
        // tried: many take the fallback line path, and the one between the
        // CR and LF of the blank line that ends a header block dispatches the
        // head from it -- the path that used to leave the accumulator looking
        // non-empty while that head was being dispatched.
        val whole = REQ_A + REQ_B + REQ_C + REQ_D
        val expected = listOf("head /a", "end 0")
        for (cut in 1 until whole.length) {
            val tracker = TrackingAllocator(DefaultAllocator)
            val c = openRequestConnection(tracker, closeOnHead = 1)
            c.read(whole.substring(0, cut), tracker)
            c.read(whole.substring(cut), tracker)
            assertEquals(expected, c.sink.events, "split at byte $cut")
            c.sink.releaseHeads()
            assertEquals(0, tracker.outstandingCount, "every recv buffer is back (split at $cut)")
        }
    }

    @Test
    fun `a response ending cuts the same bytes at the same place wherever a read boundary falls`() {
        // The response decoder's ending reports a truncation when a status
        // line is in progress, and judges that by the fallback accumulator.
        // With the read boundary between the CR and LF of the blank line that
        // ends the header block, the accumulator held that CR while the
        // completed head was dispatched -- and the close from that head was
        // reported as a truncated status line, for a response that had
        // decoded whole.
        val whole = RESP_200 + RESP_201 + RESP_202
        val expected = listOf("head 200", "end 0")
        for (cut in 1 until whole.length) {
            val tracker = TrackingAllocator(DefaultAllocator)
            val c = openResponseConnection(tracker, closeOnHead = 1)
            c.read(whole.substring(0, cut), tracker)
            c.read(whole.substring(cut), tracker)
            assertEquals(expected, c.sink.events, "split at byte $cut")
            c.sink.releaseHeads()
            assertEquals(0, tracker.outstandingCount, "every recv buffer is back (split at $cut)")
        }
    }

    // --- R3 / R10: nothing is borrowed after the ending ---

    @Test
    fun `bytes after the ending borrow nothing and hold nothing`() {
        // A header block that starts after the ending and never completes
        // used to take a pooled accumulator and retain the recv buffer its
        // range entries pointed at, with no one left to give either back:
        // measured, twenty buffers over twenty such connections. Now the
        // bytes are not decoded, so nothing is borrowed and nothing is held.
        primeThePool(PRIMED_POOL_SIZE)
        val tracker = TrackingAllocator(DefaultAllocator)
        // One connection at a time, each giving its head back before the
        // next opens: a connection then borrows exactly once, for `/a`, and
        // the pool is whole again once that head is released. A borrow taken
        // for the bytes after the ending would never come back, and the
        // pool would be one short from then on.
        repeat(CONNECTIONS) { round ->
            val c = openRequestConnection(tracker, closeOnHead = 1)
            c.read(REQ_A + "POST /b HTTP/1.1\r\nX-Part: one\r\n", tracker)
            c.read("X-More: two\r\n", tracker)
            assertEquals(listOf("head /a", "end 0"), c.sink.events, "round $round")
            c.sink.releaseHeads()
            assertEquals(PRIMED_POOL_SIZE, HttpHeadersPool.size(), "the pool is whole after round $round")
        }
        assertEquals(0, tracker.outstandingCount, "no recv buffer is stranded")
    }

    // --- R4: bytes after the ending that would not parse raise nothing ---

    @Test
    fun `bytes after the ending that would not parse raise no error`() {
        val tracker = TrackingAllocator(DefaultAllocator)
        val c = openRequestConnection(tracker, closeOnHead = 1)
        c.read(REQ_A, tracker)
        // A request line no parser accepts, then a request with no Host.
        c.read("THIS IS NOT HTTP\r\n\r\n", tracker)
        c.read("GET /nohost HTTP/1.1\r\n\r\n", tracker)
        assertEquals(listOf("head /a", "end 0"), c.sink.events)
        c.sink.releaseHeads()
        assertEquals(0, tracker.outstandingCount, "every recv buffer is back")
    }

    // --- R5: a message cut by the ending is discarded ---

    @Test
    fun `a request cut by the ending is discarded without an error`() {
        // The read stops inside `/b`'s header block and the channel closes
        // there, so the ending finds a part-parsed request. It is dropped:
        // its fields are not kept for the rest of the block, and the rest of
        // the block is not decoded. The server side raises no error for it --
        // nobody is waiting on a request that can no longer be answered.
        val tracker = TrackingAllocator(DefaultAllocator)
        val c = openRequestConnection(tracker)
        c.read(REQ_A + "POST /b HTTP/1.1\r\nX-Part: one\r\n", tracker)
        c.channel.close()
        c.read("Host: h\r\n\r\n", tracker)
        assertEquals(listOf("head /a", "end 0"), c.sink.events)
        c.sink.releaseHeads()
        assertEquals(0, tracker.outstandingCount, "every recv buffer is back")
    }

    @Test
    fun `a response cut by the ending is reported once and then discarded`() {
        // The client side is waiting on this response, so the truncation is
        // reported -- as it always was -- and then the decoder is done: the
        // rest of the block is neither decoded nor reported again.
        val tracker = TrackingAllocator(DefaultAllocator)
        val c = openResponseConnection(tracker)
        c.read("HTTP/1.1 200 OK\r\nX-Part: one\r\n", tracker)
        c.channel.close()
        c.read("Content-Length: 0\r\n\r\n" + RESP_201, tracker)
        assertEquals(listOf("error HttpEofException"), c.sink.events)
        c.sink.releaseHeads()
        assertEquals(0, tracker.outstandingCount, "every recv buffer is back")
    }

    @Test
    fun `a status line cut by the ending is reported`() {
        // The other half of the accumulator invariant: when a line really is
        // pending at the ending, the truncation is reported. The status line
        // has no terminator yet, so it sits in the accumulator, and the
        // explicit close finds it there.
        val tracker = TrackingAllocator(DefaultAllocator)
        val c = openResponseConnection(tracker)
        c.read("HTTP/1.1 20", tracker)
        c.channel.close()
        c.read("0 OK\r\nContent-Length: 0\r\n\r\n", tracker)
        assertEquals(listOf("error HttpEofException"), c.sink.events)
        c.sink.releaseHeads()
        assertEquals(0, tracker.outstandingCount, "every recv buffer is back")
    }

    // --- R6: another connection is untouched ---

    @Test
    fun `a connection that reads after its ending writes into no other connection's head`() {
        // The ending finds a borrow held; a second connection, opened after
        // it, takes what was given back and emits a head from it; then the
        // first connection reads on. It decodes nothing, so the second
        // connection's head is exactly what it parsed.
        primeThePool(PRIMED_POOL_SIZE)
        val tracker = TrackingAllocator(DefaultAllocator)
        val first = openRequestConnection(tracker)
        first.read("POST /b HTTP/1.1\r\nX-Part: one\r\n", tracker)
        first.channel.close()
        val second = openRequestConnection(tracker)
        second.read("GET /z HTTP/1.1\r\nHost: z\r\n\r\n", tracker)
        first.read("X-Inj: from-first\r\nHost: h\r\n\r\n", tracker)
        assertEquals(emptyList(), first.sink.events)
        assertEquals(listOf("head /z", "end 0"), second.sink.events)
        assertNull(second.sink.requestHeads[0].headers["X-Inj"], "nothing crossed from the first connection")
        first.sink.releaseHeads()
        second.sink.releaseHeads()
        assertEquals(0, tracker.outstandingCount, "every recv buffer is back")
        assertEquals(PRIMED_POOL_SIZE, HttpHeadersPool.size(), "the pool is as it was primed")
    }

    // --- R7: READ_UNTIL_CLOSE completes on the ending, then stops ---

    @Test
    fun `an ending that delimits a body completes it and decodes nothing more`() {
        val tracker = TrackingAllocator(DefaultAllocator)
        val c = openResponseConnection(tracker)
        // No length and not chunked: the close is the delimiter.
        c.read("HTTP/1.1 200 OK\r\n\r\nhello", tracker)
        c.channel.close()
        c.read(RESP_201, tracker)
        assertEquals(listOf("head 200", "body 5", "end 0"), c.sink.events)
        c.sink.releaseHeads()
        assertEquals(0, tracker.outstandingCount, "every recv buffer is back")
    }

    // --- R8: PASS_THROUGH keeps relaying ---

    @Test
    fun `a switched protocol keeps receiving raw bytes after the ending`() {
        // After 101 the decoder is not decoding but relaying; what an ending
        // means for the switched protocol is its own handler's decision, so
        // the relay does not stop on it.
        val tracker = TrackingAllocator(DefaultAllocator)
        val c = openResponseConnection(tracker)
        c.read("HTTP/1.1 101 Switching Protocols\r\nUpgrade: x\r\n\r\nRAW1", tracker)
        c.channel.close()
        c.read("RAW2", tracker)
        assertEquals(listOf("head 101", "end 0", "raw RAW1", "raw RAW2"), c.sink.events)
        c.sink.releaseHeads()
        assertEquals(0, tracker.outstandingCount, "every recv buffer is back")
    }

    // --- R9: a replayed read after the ending is not decoded ---

    @Test
    fun `a journalled read replayed after the ending is not decoded`() {
        // Both reads arrive before any handler is attached, so the pipeline
        // journals them and the drain delivers them. The close is raised
        // from inside the first replayed read; the second is replayed by the
        // same drain, into a decoder that has already ended.
        val tracker = TrackingAllocator(DefaultAllocator)
        val transport = TestIoTransport(tracker)
        // A dispatcher that holds the drain until asked, so both handlers
        // are attached before the journal replays through the chain -- the
        // way a codec stack added back to back is replayed on an engine.
        val queue = QueueingDispatcher()
        transport.dispatcher = queue
        val sink = Sink(closeOnHead = 1)
        val channel = object : AbstractPipelinedChannel(transport, logger) {}
        sink.channel = channel
        transport.onRead?.invoke(bufOf(REQ_A, tracker))
        transport.onRead?.invoke(bufOf(REQ_B, tracker))
        channel.pipeline.addLast("decoder", HttpRequestDecoder())
        channel.pipeline.addLast("sink", sink)
        assertEquals(emptyList(), sink.events, "nothing is replayed before the drain runs")
        queue.runQueued()
        assertEquals(listOf("head /a", "end 0"), sink.events)
        sink.releaseHeads()
        assertEquals(0, tracker.outstandingCount, "every recv buffer is back")
    }

    /** Holds every dispatched block until [runQueued]. */
    private class QueueingDispatcher : CoroutineDispatcher() {
        private val queued = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queued.addLast(block)
        }

        fun runQueued() {
            while (queued.isNotEmpty()) queued.removeFirst().run()
        }
    }

    private companion object {
        const val PRIMED_POOL_SIZE: Int = 4
        const val CONNECTIONS: Int = 20
        const val REQ_A = "GET /a HTTP/1.1\r\nHost: h\r\nX-M: a\r\n\r\n"
        const val REQ_B = "GET /b HTTP/1.1\r\nHost: h\r\nX-M: b\r\n\r\n"
        const val REQ_C = "GET /c HTTP/1.1\r\nHost: h\r\nX-M: c\r\n\r\n"
        const val REQ_D = "GET /d HTTP/1.1\r\nHost: h\r\nX-M: d\r\n\r\n"
        const val RESP_200 = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"
        const val RESP_201 = "HTTP/1.1 201 Created\r\nContent-Length: 0\r\n\r\n"
        const val RESP_202 = "HTTP/1.1 202 Accepted\r\nContent-Length: 0\r\n\r\n"
    }
}
