package io.github.fukusaka.keel.client.http

import io.github.fukusaka.keel.buf.TrackingAllocator
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
import io.github.fukusaka.keel.server.http.KeelHttpServer
import io.github.fukusaka.keel.server.http.dsl.keelHttpServer
import io.github.fukusaka.keel.testing.engine.InMemoryEngine
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * Tests for [ConnectionPool] keep-alive reuse, idle eviction, cap, and
 * lifecycle, driven against a live server on an [InMemoryEngine] — a
 * `keelHttpServer` for the pool-level cases, and a raw keep-alive pipeline
 * server (with a per-connection accept counter) for the end-to-end reuse case.
 *
 * The pool's idle set has no public accessor: every assertion observes the
 * pool's behaviour instead — whether a lease is [Lease.reused], whether a
 * connection is [ClientConnection.isActive] (pooled connections are kept
 * alive; connections closed on release or by the pool are not), and, for
 * end-to-end reuse, how many connections the server accepted.
 *
 * The stale-connection retry in `KeelHttpClient.request` (a reused connection
 * failing an idempotent request → one retry on a fresh connection) is covered
 * separately by `StaleConnectionRetryTest`, which injects the failure
 * server-side (drop the connection on its second request) so the in-memory
 * loopback reproduces "live at lease, fails during the exchange"
 * deterministically.
 */
class ConnectionPoolTest {

    private val budget = 5.seconds

    private fun routeOf(server: KeelHttpServer): RouteKey {
        val addr = server.localAddress as InetSocketAddress
        return RouteKey(addr.hostString, addr.port)
    }

    private fun server(engine: InMemoryEngine) = keelHttpServer(engine) {
        connector {
            host = "127.0.0.1"
            port = 0
        }
        get("/hello") { call -> call.respondText("hi") }
    }

    /** Counts accepted connections: the per-connection pipeline initializer runs once per connection. */
    private class ConnectionCounter {
        var total = 0
    }

    /** Answers every request with a keep-alive, `Content-Length`-framed response, so the client pools the connection. */
    private class HelloHandler : InboundHandler {
        override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
            if (msg is HttpRequest) {
                ctx.propagateWriteAndFlush(HttpResponse.of(HttpStatus.OK, "hi"))
            } else {
                ctx.propagateRead(msg)
            }
        }
    }

    private fun startHelloServer(engine: InMemoryEngine, connections: ConnectionCounter): PipelinedStreamServer =
        engine.bindPipeline(InetSocketAddress("127.0.0.1", 0), BindConfig()) { channel ->
            connections.total++
            channel.addHttp1ServerCodec()
            channel.pipeline.addLast("hello", HelloHandler())
        }

    @Test
    fun `release then lease reuses the same connection`() = runTest(timeout = budget) {
        val engine = InMemoryEngine()
        val srv = server(engine)
        srv.start()
        val route = routeOf(srv)
        val pool = ConnectionPool(engine, PoolConfig())
        try {
            val first = pool.lease(route)
            assertFalse(first.reused, "the first lease opens a fresh connection")
            pool.release(first.connection, reusable = true)

            // The release pooled it: the next lease reuses that very connection.
            val second = pool.lease(route)
            assertTrue(second.reused, "the second lease reuses the pooled connection")
            assertSame(first.connection, second.connection)
            pool.release(second.connection, reusable = true)
        } finally {
            pool.close()
            srv.stop()
            engine.close()
        }
    }

    @Test
    fun `an idle connection past the timeout is not reused`() = runTest(timeout = budget) {
        val engine = InMemoryEngine()
        val srv = server(engine)
        srv.start()
        val route = routeOf(srv)
        val time = TestTimeSource()
        val pool = ConnectionPool(engine, PoolConfig(idleTimeoutMillis = 1_000), time)
        try {
            val first = pool.lease(route)
            pool.release(first.connection, reusable = true)
            assertTrue(first.connection.isActive, "a reusable release keeps the connection pooled and alive")

            time += 1_500.milliseconds // past the 1s idle timeout
            val second = pool.lease(route)
            assertFalse(second.reused, "an expired idle connection must not be reused")
            assertNotSame(first.connection, second.connection)
            assertFalse(first.connection.isActive, "the expired connection was closed by the lease sweep")
            pool.release(second.connection, reusable = true)
        } finally {
            pool.close()
            srv.stop()
            engine.close()
        }
    }

    @Test
    fun `a released connection over the idle cap is closed`() = runTest(timeout = budget) {
        val engine = InMemoryEngine()
        val srv = server(engine)
        srv.start()
        val route = routeOf(srv)
        val pool = ConnectionPool(engine, PoolConfig(maxIdleConnectionsPerRoute = 1))
        try {
            val a = pool.lease(route)
            val b = pool.lease(route) // idle empty → also fresh
            assertFalse(a.reused)
            assertFalse(b.reused)

            pool.release(a.connection, reusable = true)
            assertTrue(a.connection.isActive, "a is pooled and kept alive")
            pool.release(b.connection, reusable = true) // over the cap of 1 → closed
            assertFalse(b.connection.isActive, "the over-cap connection was closed, not pooled")
            assertTrue(a.connection.isActive, "a stayed pooled under the cap")

            // The cap kept exactly a: the next lease reuses a, not the closed b.
            val reused = pool.lease(route)
            assertTrue(reused.reused, "the connection kept under the cap is reusable")
            assertSame(a.connection, reused.connection, "the cap kept the first release (a), not the over-cap b")
            pool.release(reused.connection, reusable = true)
        } finally {
            pool.close()
            srv.stop()
            engine.close()
        }
    }

    @Test
    fun `a non-reusable connection is closed on release`() = runTest(timeout = budget) {
        val engine = InMemoryEngine()
        val srv = server(engine)
        srv.start()
        val route = routeOf(srv)
        val pool = ConnectionPool(engine, PoolConfig())
        try {
            val leased = pool.lease(route)
            pool.release(leased.connection, reusable = false)
            assertFalse(leased.connection.isActive, "a non-reusable connection is closed, not pooled")

            // Nothing was pooled: the next lease must open a fresh connection.
            val next = pool.lease(route)
            assertFalse(next.reused, "a non-reusable release leaves nothing to reuse")
            pool.release(next.connection, reusable = true)
        } finally {
            pool.close()
            srv.stop()
            engine.close()
        }
    }

    @Test
    fun `close closes pooled connections and rejects further leases`() = runTest(timeout = budget) {
        val tracking = TrackingAllocator()
        val engine = InMemoryEngine(IoEngineConfig(allocator = tracking))
        val srv = server(engine)
        srv.start()
        val route = routeOf(srv)
        val pool = ConnectionPool(engine, PoolConfig())
        val leased = pool.lease(route)
        pool.release(leased.connection, reusable = true)
        assertTrue(leased.connection.isActive, "a reusable release pools the connection, kept alive")

        pool.close()
        assertFalse(leased.connection.isActive, "pool.close closes pooled connections")
        assertFailsWith<IllegalStateException> { pool.lease(route) }

        srv.stop()
        engine.close()
        assertEquals(0, tracking.outstandingCount, "pool.close leaked pooled buffers")
    }

    @Test
    fun `the client reuses one connection across sequential requests and leaks nothing`() =
        runTest(timeout = budget) {
            val tracking = TrackingAllocator()
            val engine = InMemoryEngine(IoEngineConfig(allocator = tracking))
            val connections = ConnectionCounter()
            val srv = startHelloServer(engine, connections)
            val addr = srv.localAddress as InetSocketAddress
            val url = "http://${addr.hostString}:${addr.port}/hello"
            val pool = ConnectionPool(engine, PoolConfig())
            val client = KeelHttpClient(pool)
            try {
                repeat(3) { assertEquals(HttpStatus.OK, client.get(url).status) }
                // Three sequential keep-alive requests must ride a single connection:
                // the server accepts exactly one (its per-connection initializer runs once).
                assertEquals(1, connections.total, "three keep-alive requests reused a single connection")
            } finally {
                client.close()
                srv.close()
                engine.close()
            }
            assertEquals(0, tracking.outstandingCount, "pooled client leaked buffers")
        }
}
