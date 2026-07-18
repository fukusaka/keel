package io.github.fukusaka.keel.client.http

import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.codec.http.HttpHeaderName
import io.github.fukusaka.keel.codec.http.HttpHeaders
import io.github.fukusaka.keel.codec.http.HttpMethod
import io.github.fukusaka.keel.codec.http.HttpRequest
import io.github.fukusaka.keel.codec.http.HttpStatus
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.server.http.KeelHttpServer
import io.github.fukusaka.keel.server.http.dsl.keelHttpServer
import io.github.fukusaka.keel.testing.engine.InMemoryEngine
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for [ClientConnection] — the reuse-ready unit a connection pool will lease.
 *
 * The key property is that a single connection serves multiple
 * request/response cycles (HTTP/1.1 keep-alive): the response decoder resets
 * between responses, and the codec + bridge persist across exchanges.
 */
class ClientConnectionTest {

    private val asyncBudget = 5.seconds

    private fun routeOf(server: KeelHttpServer): RouteKey {
        val addr = server.localAddress as InetSocketAddress
        return RouteKey(addr.hostString, addr.port)
    }

    private fun get(path: String, route: RouteKey): HttpRequest =
        HttpRequest(
            HttpMethod.GET,
            path,
            headers = HttpHeaders().add(HttpHeaderName.HOST, "${route.host}:${route.port}"),
        )

    @Test
    fun `two exchanges on one connection reuse it and leak nothing`() = runTest(timeout = asyncBudget) {
        val tracking = TrackingAllocator()
        val engine = InMemoryEngine(IoEngineConfig(allocator = tracking))
        val server = keelHttpServer(engine) {
            connector { host = "127.0.0.1"; port = 0 }
            get("/a") { call -> call.respondText("first") }
            get("/b") { call -> call.respondText("second") }
        }
        server.start()
        val route = routeOf(server)
        val connection = ClientConnection.open(engine, route)
        try {
            val r1 = connection.exchange(get("/a", route))
            assertEquals(HttpStatus.OK, r1.status)
            assertEquals("first", r1.body?.decodeToString())
            // respondText sends a Content-Length body over HTTP/1.1 → reusable.
            assertTrue(connection.isReusable(r1), "framed keep-alive response should be reusable")
            r1.headers.release()

            // Second exchange on the SAME connection — the decoder reset after r1.
            val r2 = connection.exchange(get("/b", route))
            assertEquals(HttpStatus.OK, r2.status)
            assertEquals("second", r2.body?.decodeToString())
            r2.headers.release()

            assertTrue(connection.isActive, "connection should still be open after two keep-alive exchanges")
        } finally {
            connection.close()
            server.stop()
            engine.close()
        }
        assertEquals(0, tracking.outstandingCount, "two-exchange connection leaked pooled buffers")
    }
}
