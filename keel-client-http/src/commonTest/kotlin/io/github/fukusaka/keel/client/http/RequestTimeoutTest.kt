package io.github.fukusaka.keel.client.http

import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.codec.http.HttpHeaders
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The built-in request timeout: it must fail the request with
 * [HttpRequestTimeoutException] (not a `CancellationException`, which the
 * caller's scope would read as structured cancellation), and it must leave
 * nothing behind — the timed-out connection is closed rather than returned to
 * the pool, and no pooled buffer leaks.
 */
class RequestTimeoutTest {

    private val budget = 10.seconds
    private val timeoutMillis = 200L

    /** Accepts the request and never answers, so the client waits forever. */
    private class SilentHandler : InboundHandler {
        override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
            if (msg !is HttpRequest) ctx.propagateRead(msg)
            // A well-formed request arrives and is deliberately left unanswered.
        }
    }

    private class RespondingHandler : InboundHandler {
        override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
            if (msg is HttpRequest) {
                ctx.propagateWriteAndFlush(HttpResponse.of(HttpStatus.OK, "ok"))
            } else {
                ctx.propagateRead(msg)
            }
        }
    }

    private fun startServer(engine: InMemoryEngine, silent: Boolean): PipelinedStreamServer =
        engine.bindPipeline(InetSocketAddress("127.0.0.1", 0), BindConfig()) { channel ->
            channel.addHttp1ServerCodec()
            channel.pipeline.addLast("handler", if (silent) SilentHandler() else RespondingHandler())
        }

    private fun urlOf(server: PipelinedStreamServer): String {
        val addr = server.localAddress as InetSocketAddress
        return "http://${addr.hostString}:${addr.port}/"
    }

    private fun routeOf(server: PipelinedStreamServer): RouteKey {
        val addr = server.localAddress as InetSocketAddress
        return RouteKey(addr.hostString, addr.port)
    }

    @Test
    fun `a request that outlives the budget fails with HttpRequestTimeoutException`() =
        runTest(timeout = budget) {
            val engine = InMemoryEngine()
            val server = startServer(engine, silent = true)
            val client = KeelHttpClient(ConnectionPool(engine, PoolConfig()), HttpHeaders.EMPTY, timeoutMillis)
            try {
                // Widened to Throwable so the guard is a real runtime check: it must
                // keep failing if the exception's supertype ever changes.
                val thrown: Throwable = assertFailsWith<HttpRequestTimeoutException> { client.get(urlOf(server)) }
                assertFalse(
                    thrown is CancellationException,
                    "a timeout must not surface as cancellation — the caller's scope is not cancelled",
                )
                assertTrue(thrown.message!!.contains("$timeoutMillis"), "the message names the budget")
            } finally {
                client.close()
                server.close()
                engine.close()
            }
        }

    @Test
    fun `a timed-out connection is closed rather than returned to the pool`() = runTest(timeout = budget) {
        val engine = InMemoryEngine()
        val server = startServer(engine, silent = true)
        val pool = ConnectionPool(engine, PoolConfig())
        val client = KeelHttpClient(pool, HttpHeaders.EMPTY, timeoutMillis)
        try {
            assertFailsWith<HttpRequestTimeoutException> { client.get(urlOf(server)) }
            // Nothing was pooled: the next lease has to open a fresh connection.
            val lease = pool.lease(routeOf(server))
            assertFalse(lease.reused, "a timed-out connection must not be reused by a later request")
            pool.release(lease.connection, reusable = true)
        } finally {
            client.close()
            server.close()
            engine.close()
        }
    }

    @Test
    fun `a timed-out request leaks no pooled buffers`() = runTest(timeout = budget) {
        val tracking = TrackingAllocator()
        val engine = InMemoryEngine(IoEngineConfig(allocator = tracking))
        val server = startServer(engine, silent = true)
        val client = KeelHttpClient(ConnectionPool(engine, PoolConfig()), HttpHeaders.EMPTY, timeoutMillis)
        try {
            assertFailsWith<HttpRequestTimeoutException> { client.get(urlOf(server)) }
        } finally {
            client.close()
            server.close()
            engine.close()
        }
        assertEquals(0, tracking.outstandingCount, "the timed-out exchange leaked pooled buffers")
    }

    @Test
    fun `the timeout is disabled by default`() = runTest(timeout = budget) {
        val engine = InMemoryEngine()
        val server = startServer(engine, silent = false)
        // No requestTimeoutMillis configured: the request completes normally.
        val client = KeelHttpClient(ConnectionPool(engine, PoolConfig()))
        try {
            assertEquals(HttpStatus.OK, client.get(urlOf(server)).status)
        } finally {
            client.close()
            server.close()
            engine.close()
        }
    }
}
