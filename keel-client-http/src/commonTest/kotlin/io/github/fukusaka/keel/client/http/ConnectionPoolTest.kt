package io.github.fukusaka.keel.client.http

import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.codec.http.HttpStatus
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
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
 * lifecycle, driven against a live `keelHttpServer` on an [InMemoryEngine].
 */
class ConnectionPoolTest {

    private val budget = 5.seconds

    private fun routeOf(server: KeelHttpServer): RouteKey {
        val addr = server.localAddress as InetSocketAddress
        return RouteKey(addr.hostString, addr.port)
    }

    private fun server(engine: InMemoryEngine) = keelHttpServer(engine) {
        connector { host = "127.0.0.1"; port = 0 }
        get("/hello") { call -> call.respondText("hi") }
    }

    private fun urlFor(server: KeelHttpServer, path: String): String {
        val addr = server.localAddress as InetSocketAddress
        return "http://${addr.hostString}:${addr.port}$path"
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
            assertEquals(1, pool.idleCount(route))

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
            assertEquals(1, pool.idleCount(route))

            time += 1_500.milliseconds // past the 1s idle timeout
            val second = pool.lease(route)
            assertFalse(second.reused, "an expired idle connection must not be reused")
            assertNotSame(first.connection, second.connection)
            assertFalse(first.connection.isActive, "the expired connection was closed")
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
            assertEquals(1, pool.idleCount(route))
            pool.release(b.connection, reusable = true) // over the cap of 1 → closed
            assertEquals(1, pool.idleCount(route))
            assertFalse(b.connection.isActive, "the over-cap connection was closed, not pooled")
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
            assertEquals(0, pool.idleCount(route), "a non-reusable connection is not pooled")
            assertFalse(leased.connection.isActive, "it is closed instead")
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
        assertEquals(1, pool.idleCount(route))

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
            val srv = server(engine)
            srv.start()
            val route = routeOf(srv)
            val pool = ConnectionPool(engine, PoolConfig())
            val client = KeelHttpClient(pool)
            try {
                repeat(3) { assertEquals(HttpStatus.OK, client.get(urlFor(srv, "/hello")).status) }
                assertEquals(1, pool.idleCount(route), "three keep-alive requests reuse one connection")
            } finally {
                client.close()
                srv.stop()
                engine.close()
            }
            assertEquals(0, tracking.outstandingCount, "pooled client leaked buffers")
        }
}
