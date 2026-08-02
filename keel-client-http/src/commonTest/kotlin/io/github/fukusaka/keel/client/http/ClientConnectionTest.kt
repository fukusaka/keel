package io.github.fukusaka.keel.client.http

import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.codec.http.HttpHeaderName
import io.github.fukusaka.keel.codec.http.HttpHeaders
import io.github.fukusaka.keel.codec.http.HttpMethod
import io.github.fukusaka.keel.codec.http.HttpRequest
import io.github.fukusaka.keel.codec.http.HttpResponse
import io.github.fukusaka.keel.codec.http.HttpStatus
import io.github.fukusaka.keel.codec.http.HttpVersion
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.server.http.KeelHttpServer
import io.github.fukusaka.keel.server.http.dsl.keelHttpServer
import io.github.fukusaka.keel.testing.engine.InMemoryEngine
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
            connector {
                host = "127.0.0.1"
                port = 0
            }
            get("/a") { call -> call.respondText("first") }
            get("/b") { call -> call.respondText("second") }
        }
        server.start()
        val route = routeOf(server)
        val connection = ClientConnection.open(engine, route)
        try {
            val e1 = connection.exchange(get("/a", route))
            assertEquals(HttpStatus.OK, e1.response.status)
            assertEquals("first", e1.response.bodyText())
            // respondText sends a Content-Length body over HTTP/1.1 → reusable.
            assertTrue(e1.reusable, "framed keep-alive response should be reusable")

            // Second exchange on the SAME connection — the decoder reset after e1.
            val e2 = connection.exchange(get("/b", route))
            assertEquals(HttpStatus.OK, e2.response.status)
            assertEquals("second", e2.response.bodyText())

            assertTrue(connection.isActive, "connection should still be open after two keep-alive exchanges")
        } finally {
            connection.close()
            server.stop()
            engine.close()
        }
        assertEquals(0, tracking.outstandingCount, "two-exchange connection leaked pooled buffers")
    }

    private fun response(
        status: HttpStatus,
        version: HttpVersion = HttpVersion.HTTP_1_1,
        headers: HttpHeaders = HttpHeaders(),
    ) = HttpResponse(status, version, headers)

    @Test
    fun `isReusable requires keep-alive and a determinate body end`() {
        val cl = HttpHeaders().add(HttpHeaderName.CONTENT_LENGTH, "5")
        val chunked = HttpHeaders().add(HttpHeaderName.TRANSFER_ENCODING, "chunked")
        val close = HttpHeaders().add(HttpHeaderName.CONNECTION, "close")
        val keepAlive = HttpHeaders().add(HttpHeaderName.CONNECTION, "keep-alive")

        // Framed + keep-alive → reusable.
        assertTrue(ClientConnection.isReusable(response(HttpStatus.OK, headers = cl)))
        assertTrue(ClientConnection.isReusable(response(HttpStatus.OK, headers = chunked)))

        // Bodyless-by-status (204 / 304) is reusable even with no framing header —
        // the connection sits at the next response start (regression: was wrongly
        // classified not-reusable because it has no Content-Length).
        assertTrue(ClientConnection.isReusable(response(HttpStatus.NO_CONTENT)))
        assertTrue(ClientConnection.isReusable(response(HttpStatus.NOT_MODIFIED)))

        // No framing and a body-bearing status → read-until-close → not reusable.
        assertFalse(ClientConnection.isReusable(response(HttpStatus.OK)))

        // Connection: close is never reusable, even framed.
        assertFalse(
            ClientConnection.isReusable(
                response(HttpStatus.OK, headers = close.add(HttpHeaderName.CONTENT_LENGTH, "5")),
            ),
        )

        // HTTP/1.0 defaults to close; only an explicit keep-alive (with framing) reuses.
        assertFalse(ClientConnection.isReusable(response(HttpStatus.OK, HttpVersion.HTTP_1_0, cl)))
        assertTrue(
            ClientConnection.isReusable(
                response(HttpStatus.OK, HttpVersion.HTTP_1_0, keepAlive.add(HttpHeaderName.CONTENT_LENGTH, "5")),
            ),
        )
    }
}
