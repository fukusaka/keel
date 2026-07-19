package io.github.fukusaka.keel.client.http

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.client.http.dsl.keelHttpClient
import io.github.fukusaka.keel.codec.http.HttpParseException
import io.github.fukusaka.keel.codec.http.HttpRequest
import io.github.fukusaka.keel.codec.http.HttpResponse
import io.github.fukusaka.keel.codec.http.HttpStatus
import io.github.fukusaka.keel.codec.http.addHttp1ServerCodec
import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.pipeline.InboundHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import io.github.fukusaka.keel.pipeline.PipelinedStreamServer
import io.github.fukusaka.keel.testing.engine.InMemoryEngine
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

/**
 * Tests the stale-connection retry in [KeelHttpClient.request]: a reused
 * (pooled) connection that fails an idempotent request is retried once on a
 * fresh connection, while a non-idempotent request is not.
 *
 * The retry path needs a pooled connection that is **live at lease but fails
 * during the exchange** — a peer that dropped the kept-alive connection right
 * as the client reused it. A client-side close would instead be noticed at
 * lease (the pool would skip it and open fresh, `reused = false`), so the
 * failure is injected **server-side**: [DropOnSecondRequest] answers a
 * connection's first request with a keep-alive response (so the client pools
 * it) and drops the connection on its second request. On the synchronous
 * in-memory loopback this is fully deterministic.
 */
class StaleConnectionRetryTest {

    private val budget = 5.seconds

    /** Counts every request the server received across all connections. */
    private class RequestCounter {
        var total = 0
    }

    /**
     * Server handler: respond keep-alive to a connection's first request, then
     * close the connection on its second. Instances are per-connection (added
     * by the pipeline initializer), so [requests] is per-connection; [counter]
     * is shared to count receptions across connections (retry evidence).
     */
    private class DropOnSecondRequest(private val counter: RequestCounter) : InboundHandler {
        private var requests = 0

        override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
            if (msg is HttpRequest) {
                requests++
                counter.total++
                if (requests == 1) {
                    ctx.propagateWriteAndFlush(HttpResponse.of(HttpStatus.OK, "ok"))
                } else {
                    ctx.channel.pipeline.requestClose()
                }
            } else {
                ctx.propagateRead(msg)
            }
        }
    }

    private fun startServer(engine: InMemoryEngine, counter: RequestCounter): PipelinedStreamServer =
        engine.bindPipeline(InetSocketAddress("127.0.0.1", 0), BindConfig()) { channel ->
            channel.addHttp1ServerCodec()
            channel.pipeline.addLast("drop-on-second", DropOnSecondRequest(counter))
        }

    /**
     * Server handler: respond keep-alive to a connection's first request, then
     * write an unparseable response on its second (raw bytes past the encoder,
     * which passes non-`HttpResponse` messages through). The reused connection
     * fails with a response-level error, not a stale connection.
     */
    private class MalformedOnSecondRequest(private val counter: RequestCounter) : InboundHandler {
        private var requests = 0

        override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
            if (msg is HttpRequest) {
                requests++
                counter.total++
                if (requests == 1) {
                    ctx.propagateWriteAndFlush(HttpResponse.of(HttpStatus.OK, "ok"))
                } else {
                    val garbage = "not-a-valid-http-response\r\n\r\n".encodeToByteArray()
                    val buf = DefaultAllocator.allocate(garbage.size)
                    buf.writeByteArray(garbage, 0, garbage.size)
                    ctx.propagateWriteAndFlush(buf)
                }
            } else {
                ctx.propagateRead(msg)
            }
        }
    }

    private fun startMalformedServer(engine: InMemoryEngine, counter: RequestCounter): PipelinedStreamServer =
        engine.bindPipeline(InetSocketAddress("127.0.0.1", 0), BindConfig()) { channel ->
            channel.addHttp1ServerCodec()
            channel.pipeline.addLast("malformed-on-second", MalformedOnSecondRequest(counter))
        }

    /**
     * Server handler: respond keep-alive to a connection's first request, then on
     * its second write a well-formed head promising a body but close before the
     * body completes. The client decoder hits EOF mid-response — a connection
     * failure (not a malformed response), so a fresh connection should recover.
     */
    private class TruncateOnSecondRequest(private val counter: RequestCounter) : InboundHandler {
        private var requests = 0

        override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
            if (msg is HttpRequest) {
                requests++
                counter.total++
                if (requests == 1) {
                    ctx.propagateWriteAndFlush(HttpResponse.of(HttpStatus.OK, "ok"))
                } else {
                    val partial = "HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\nxx".encodeToByteArray()
                    val buf = DefaultAllocator.allocate(partial.size)
                    buf.writeByteArray(partial, 0, partial.size)
                    ctx.propagateWriteAndFlush(buf)
                    ctx.channel.pipeline.requestClose()
                }
            } else {
                ctx.propagateRead(msg)
            }
        }
    }

    private fun startTruncatingServer(engine: InMemoryEngine, counter: RequestCounter): PipelinedStreamServer =
        engine.bindPipeline(InetSocketAddress("127.0.0.1", 0), BindConfig()) { channel ->
            channel.addHttp1ServerCodec()
            channel.pipeline.addLast("truncate-on-second", TruncateOnSecondRequest(counter))
        }

    @Test
    fun `a reused connection failing an idempotent request retries once on a fresh connection`() =
        runTest(timeout = budget) {
            val tracking = TrackingAllocator()
            val engine = InMemoryEngine(IoEngineConfig(allocator = tracking))
            val counter = RequestCounter()
            val server = startServer(engine, counter)
            val addr = server.localAddress as InetSocketAddress
            val url = "http://${addr.hostString}:${addr.port}/"
            val client = keelHttpClient(engine) { pool { maxIdleConnectionsPerRoute = 4 } }
            try {
                // Request 1 opens a connection, gets a keep-alive response, pools it.
                assertEquals(HttpStatus.OK, client.get(url).status)
                // Request 2 leases that pooled connection (live at lease); the server
                // drops it on this second request, so the exchange fails. GET is
                // idempotent, so the client retries once on a fresh connection and
                // succeeds rather than surfacing the stale-connection failure.
                val retried = client.get(url)
                assertEquals(HttpStatus.OK, retried.status)
                assertEquals("ok", retried.bodyText())
                // Proof the retry path ran (not a fresh connection from the start):
                // the server saw the pooled connection's first request, its dropped
                // second request, then the retry on a fresh connection = 3.
                assertEquals(3, counter.total, "expected reuse → drop → retry, i.e. 3 server-side requests")
            } finally {
                client.close()
                server.close()
                engine.close()
            }
            assertEquals(0, tracking.outstandingCount, "the reuse → drop → retry sequence leaked pooled buffers")
        }

    @Test
    fun `a reused connection failing a non-idempotent request is not retried`() =
        runTest(timeout = budget) {
            val tracking = TrackingAllocator()
            val engine = InMemoryEngine(IoEngineConfig(allocator = tracking))
            val counter = RequestCounter()
            val server = startServer(engine, counter)
            val addr = server.localAddress as InetSocketAddress
            val url = "http://${addr.hostString}:${addr.port}/"
            val client = keelHttpClient(engine) { pool { maxIdleConnectionsPerRoute = 4 } }
            try {
                // Request 1 pools a connection.
                assertEquals(HttpStatus.OK, client.get(url).status)
                // Request 2 is a POST (non-idempotent) on the reused connection the
                // server drops; retrying could double-apply it, so the client must
                // surface the failure instead of retrying.
                assertFailsWith<Exception> { client.post(url) }
                // Proof it hit the reused connection and did NOT retry: the server
                // saw the pooled connection's first request and its dropped second
                // request = 2, with no fresh-connection retry.
                assertEquals(2, counter.total, "expected reuse → drop with no retry, i.e. 2 server-side requests")
            } finally {
                client.close()
                server.close()
                engine.close()
            }
            assertEquals(0, tracking.outstandingCount, "the failed non-idempotent request leaked pooled buffers")
        }

    @Test
    fun `a reused connection returning a malformed response is not retried`() =
        runTest(timeout = budget) {
            val engine = InMemoryEngine()
            val counter = RequestCounter()
            val server = startMalformedServer(engine, counter)
            val addr = server.localAddress as InetSocketAddress
            val url = "http://${addr.hostString}:${addr.port}/"
            val client = keelHttpClient(engine) { pool { maxIdleConnectionsPerRoute = 4 } }
            try {
                // Request 1 pools a connection.
                assertEquals(HttpStatus.OK, client.get(url).status)
                // Request 2 (idempotent GET) reuses it, but the server replies with
                // an unparseable response. That is a response-level failure, not a
                // stale connection, so the client must NOT retry it — retrying would
                // just re-send and hit the same malformed reply. The parse error
                // surfaces instead.
                assertFailsWith<HttpParseException> { client.get(url) }
                // Proof there was no retry: the server saw the pooled connection's
                // first request and its malformed second = 2, with no fresh retry
                // (a retry would make it 3 and the request would have succeeded).
                assertEquals(2, counter.total, "a malformed response must not trigger a fresh-connection retry")
            } finally {
                client.close()
                server.close()
                engine.close()
            }
        }

    @Test
    fun `a reused connection dropped mid-response is retried`() =
        runTest(timeout = budget) {
            val engine = InMemoryEngine()
            val counter = RequestCounter()
            val server = startTruncatingServer(engine, counter)
            val addr = server.localAddress as InetSocketAddress
            val url = "http://${addr.hostString}:${addr.port}/"
            val client = keelHttpClient(engine) { pool { maxIdleConnectionsPerRoute = 4 } }
            try {
                // Request 1 pools a connection.
                assertEquals(HttpStatus.OK, client.get(url).status)
                // Request 2 (idempotent GET) reuses it; the server sends a head then
                // drops the connection mid-body. That is a connection failure (EOF
                // mid-response), not a malformed reply, so the client retries once
                // on a fresh connection and succeeds.
                val retried = client.get(url)
                assertEquals(HttpStatus.OK, retried.status)
                assertEquals("ok", retried.bodyText())
                // reuse → mid-response drop → retry on fresh = 3 server-side requests.
                assertEquals(3, counter.total, "a mid-response drop must retry on a fresh connection")
            } finally {
                client.close()
                server.close()
                engine.close()
            }
        }
}
