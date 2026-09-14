package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import io.github.fukusaka.keel.pipeline.InboundHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.pipeline.TypedInboundHandler
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A decoded HTTP message owns the pooled headers it carries, and releasing
 * the message is how they go back.
 *
 * Two things follow. A message the pipeline releases on a handler's behalf —
 * dropped at the tail, released after a typed handler's callback, released
 * after a handler threw — gives back its headers and the receive buffer they
 * view. And a release can never give back headers another message is using:
 * the pool lends the same instance again, so a release is honoured only for
 * the borrow the message was built on.
 */
class HttpMessageReleaseTest {

    private val logger = PrintLogger("HttpMessageReleaseTest")

    @AfterTest
    fun emptyThePool() {
        HttpHeadersPool.clear()
    }

    // --- What the pipeline releases on a handler's behalf ---

    @Test
    fun `a request head nobody consumes gives its receive buffer back`() {
        val tracker = TrackingAllocator(DefaultAllocator)
        val channel = channelOn(tracker, "decoder" to HttpRequestDecoder())

        read(channel, tracker, REQUEST)

        assertEquals(0, tracker.outstandingCount, "the tail released the head and the buffer its headers viewed")
    }

    @Test
    fun `a typed handler that takes the head releases it when its callback returns`() {
        val tracker = TrackingAllocator(DefaultAllocator)
        var seen: String? = null
        // Typed on the decoder's own output type, which the pipeline checks at
        // construction; the head is the message whose release is under test.
        val typed = object : TypedInboundHandler<HttpMessage>(HttpMessage::class) {
            override fun onReadTyped(ctx: PipelineHandlerContext, msg: HttpMessage) {
                if (msg is HttpRequestHead) seen = msg.headers.getString("X-Owner")
            }
        }
        val channel = channelOn(tracker, "decoder" to HttpRequestDecoder(), "typed" to typed)

        read(channel, tracker, REQUEST)

        assertEquals("first", seen, "the handler read the headers inside its callback")
        assertEquals(0, tracker.outstandingCount, "and the head was released after it")
    }

    @Test
    fun `a handler that throws on the head has the head released for it`() {
        val tracker = TrackingAllocator(DefaultAllocator)
        val thrower = object : InboundHandler {
            override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
                if (msg is HttpRequestHead) error("rejected")
                ctx.propagateRead(msg)
            }

            override fun onError(ctx: PipelineHandlerContext, cause: Throwable) {}
        }
        val channel = channelOn(
            tracker,
            "decoder" to HttpRequestDecoder(),
            "thrower" to thrower,
            "rest" to BodyDropper(),
        )

        read(channel, tracker, REQUEST)

        assertEquals(0, tracker.outstandingCount)
    }

    @Test
    fun `an aggregated request nobody consumes gives its receive buffer back`() {
        val tracker = TrackingAllocator(DefaultAllocator)
        val channel = channelOn(tracker, "decoder" to HttpRequestDecoder(), "aggregator" to HttpBodyAggregator())

        read(channel, tracker, REQUEST)

        assertEquals(0, tracker.outstandingCount)
    }

    @Test
    fun `an aggregated response nobody consumes gives its receive buffer back`() {
        val tracker = TrackingAllocator(DefaultAllocator)
        val channel = channelOn(
            tracker,
            "decoder" to HttpResponseDecoder(),
            "aggregator" to HttpResponseBodyAggregator(),
        )

        read(channel, tracker, RESPONSE)

        assertEquals(0, tracker.outstandingCount)
    }

    @Test
    fun `a typed handler that passes on a copy leaves its headers to the handler that holds it`() {
        val tracker = TrackingAllocator(DefaultAllocator)
        val rewrite = object : TypedInboundHandler<HttpMessage>(HttpMessage::class) {
            override fun onReadTyped(ctx: PipelineHandlerContext, msg: HttpMessage) {
                if (msg is HttpRequestHead) ctx.propagateRead(msg.copy(uri = "/rewritten")) else ctx.propagateRead(msg)
            }
        }
        val holder = Holder()
        val connection = channelOn(
            tracker,
            "decoder" to HttpRequestDecoder(),
            "rewrite" to rewrite,
            "aggregator" to HttpBodyAggregator(),
            "holder" to holder,
        )

        read(connection, tracker, POST)

        val request = holder.received.single() as HttpRequest
        assertEquals("/rewritten", request.uri)
        assertEquals("first", request.headers.getString("X-Owner"), "the aggregator held the copy across the body")
        assertTrue(request.release(), "and the holder is the one that releases the headers")
        assertEquals(0, tracker.outstandingCount)
    }

    @Test
    fun `a typed handler that passes on a request built from the head's headers leaves them to the receiver`() {
        val tracker = TrackingAllocator(DefaultAllocator)
        val build = object : TypedInboundHandler<HttpMessage>(HttpMessage::class) {
            override fun onReadTyped(ctx: PipelineHandlerContext, msg: HttpMessage) {
                if (msg is HttpRequestHead) {
                    ctx.propagateRead(HttpRequest(msg.method, msg.uri, msg.version, msg.headers))
                }
            }
        }
        val holder = Holder()
        val connection = channelOn(tracker, "decoder" to HttpRequestDecoder(), "build" to build, "holder" to holder)

        read(connection, tracker, REQUEST)

        val request = holder.received.single() as HttpRequest
        assertEquals("first", request.headers.getString("X-Owner"))
        assertTrue(request.release())
        assertEquals(0, tracker.outstandingCount)
    }

    @Test
    fun `a typed handler that passes on a copy with headers of its own still releases the original`() {
        val tracker = TrackingAllocator(DefaultAllocator)
        val rebuild = object : TypedInboundHandler<HttpMessage>(HttpMessage::class) {
            override fun onReadTyped(ctx: PipelineHandlerContext, msg: HttpMessage) {
                if (msg is HttpRequestHead) {
                    ctx.propagateRead(msg.copy(headers = HttpHeaders().add("X-Owner", "rebuilt")))
                }
            }
        }
        val holder = Holder()
        val connection = channelOn(tracker, "decoder" to HttpRequestDecoder(), "rebuild" to rebuild, "holder" to holder)

        read(connection, tracker, REQUEST)

        assertEquals("rebuilt", (holder.received.single() as HttpRequestHead).headers.getString("X-Owner"))
        assertEquals(0, tracker.outstandingCount, "the decoded head was released when the callback returned")
    }

    // --- A release only ever gives back the borrow it was built on ---

    @Test
    fun `release answers true once and false after`() {
        val head = pooledHead("first")

        assertTrue(head.release())
        assertFalse(head.release())
    }

    @Test
    fun `a message whose headers were built directly has nothing to release`() {
        val head = HttpRequestHead(HttpMethod.GET, "/", headers = HttpHeaders().add("X-Owner", "built"))

        assertFalse(head.release())
        assertEquals("built", head.headers.getString("X-Owner"), "and its headers are untouched")
    }

    @Test
    fun `a release kept from before the pool lent the headers again does nothing`() {
        val stack = ArrayDeque<HttpHeaders>()
        val first = pooledHead("first", stack)
        assertTrue(first.release())

        val second = pooledHead("second", stack)
        assertSame(first.headers, second.headers, "premise: the pool lent the same instance again")

        assertFalse(first.release(), "the stale release is refused")
        assertEquals("second", second.headers.getString("X-Owner"), "and the second message keeps its headers")
        assertEquals(0, stack.size, "nor were they returned to the pool")
        assertTrue(second.release())
    }

    @Test
    fun `a copy shares one release with the message it was copied from`() {
        val head = pooledHead("first")
        val copy = head.copy(uri = "/elsewhere")

        assertTrue(copy.release())
        assertFalse(head.release())
    }

    @Test
    fun `an aggregated message shares one release with the head it was built from`() {
        val head = pooledHead("first")
        val request = HttpRequest(head.method, head.uri, head.version, head.headers)

        assertTrue(request.release())
        assertFalse(head.release())
    }

    @Test
    fun `a copy and a message built from the same headers share ownership and nothing else does`() {
        val stack = ArrayDeque<HttpHeaders>()
        val head = pooledHead("first", stack)

        assertTrue(head.sharesOwnershipWith(head.copy(uri = "/elsewhere")))
        assertTrue(head.sharesOwnershipWith(HttpRequest(head.method, head.uri, head.version, head.headers)))
        assertFalse(head.sharesOwnershipWith(pooledHead("other")), "a different borrow")

        head.release()
        val relent = pooledHead("second", stack)
        assertSame(head.headers, relent.headers, "premise: the pool lent the same instance again")
        assertFalse(head.sharesOwnershipWith(relent), "the same instance on a later borrow")
        relent.release()
    }

    @Test
    fun `messages whose headers own nothing share no ownership`() {
        val a = HttpRequestHead(HttpMethod.GET, "/a", headers = HttpHeaders.EMPTY)
        val b = HttpRequestHead(HttpMethod.GET, "/b", headers = HttpHeaders.EMPTY)

        assertSame(a.headers, b.headers, "premise: both use the shared empty headers")
        assertFalse(a.sharesOwnershipWith(b))
        assertTrue(a.sharesOwnershipWith(a))
    }

    // --- Keeping a message beyond the call that delivered it ---

    @Test
    fun `a detached head keeps its headers after the pool lends the original again`() {
        val stack = ArrayDeque<HttpHeaders>()
        val head = pooledHead("first", stack)

        val detached = head.detach()
        assertFalse(head.release(), "detaching released the original")
        val reused = pooledHead("second", stack)
        assertSame(head.headers, reused.headers, "premise: the original headers were lent again")

        assertEquals("first", detached.headers.getString("X-Owner"))
        assertEquals(head.uri, detached.uri)
        assertFalse(detached.release(), "and the detached copy has nothing to release")
        reused.release()
    }

    @Test
    fun `a detached response head keeps its headers`() {
        val headers = HttpHeadersPool.borrowFrom(ArrayDeque()).add("X-Owner", "first")
        val head = HttpResponseHead(HttpStatus.OK, headers = headers)

        val detached = head.detach()

        assertFalse(head.release())
        assertEquals("first", detached.headers.getString("X-Owner"))
    }

    @Test
    fun `detaching a released message is refused`() {
        val head = pooledHead("first")
        head.release()

        assertFailsWith<IllegalStateException> { head.detach() }
    }

    @Test
    fun `detaching a message that owns nothing returns it`() {
        val head = HttpRequestHead(HttpMethod.GET, "/")

        assertSame(head, head.detach())
    }

    // --- Helpers ---

    private fun pooledHead(owner: String, stack: ArrayDeque<HttpHeaders> = ArrayDeque()): HttpRequestHead {
        val headers = HttpHeadersPool.borrowFrom(stack).add("X-Owner", owner)
        return HttpRequestHead(HttpMethod.GET, "/", headers = headers)
    }

    private class Connection(val transport: TestIoTransport, val channel: PipelinedChannel)

    private fun channelOn(tracker: TrackingAllocator, vararg handlers: Pair<String, InboundHandler>): Connection {
        val transport = TestIoTransport(tracker)
        val channel = object : AbstractPipelinedChannel(transport, logger) {}
        for ((name, handler) in handlers) channel.pipeline.addLast(name, handler)
        return Connection(transport, channel)
    }

    private fun read(connection: Connection, tracker: TrackingAllocator, text: String) {
        connection.transport.onRead?.invoke(bufOf(text, tracker))
    }

    /**
     * A buffer whose capacity is a power of two: the only capacity on which
     * the headers take their zero-copy range path, which retains the buffer.
     */
    private fun bufOf(text: String, allocator: TrackingAllocator): IoBuf {
        val bytes = text.encodeToByteArray()
        var capacity = 1
        while (capacity < bytes.size) capacity = capacity shl 1
        val buf = allocator.allocate(capacity)
        buf.writeByteArray(bytes, 0, bytes.size)
        return buf
    }

    /** Keeps every message it receives, without releasing any. */
    private class Holder : InboundHandler {
        val received = mutableListOf<Any>()
        override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
            if (msg is HttpBody) msg.release() else received.add(msg)
        }
    }

    /** Releases the body messages that follow a head, so only the head is under test. */
    private class BodyDropper : InboundHandler {
        override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
            if (msg is HttpBody) msg.release() else ctx.propagateRead(msg)
        }
    }

    private companion object {
        const val REQUEST = "GET /a HTTP/1.1\r\nHost: x\r\nX-Owner: first\r\n\r\n"
        const val POST = "POST /a HTTP/1.1\r\nHost: x\r\nX-Owner: first\r\nContent-Length: 2\r\n\r\nok"
        const val RESPONSE = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nX-Owner: first\r\n\r\nok"
    }
}
