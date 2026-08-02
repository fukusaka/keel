package io.github.fukusaka.keel.client.http

import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.client.http.dsl.keelHttpClient
import io.github.fukusaka.keel.codec.http.HttpHeaderName
import io.github.fukusaka.keel.codec.http.HttpHeaders
import io.github.fukusaka.keel.codec.http.HttpStatus
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.server.http.KeelHttpServer
import io.github.fukusaka.keel.server.http.dsl.keelHttpServer
import io.github.fukusaka.keel.testing.engine.InMemoryEngine
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end tests for [KeelHttpClient] driving a live `keelHttpServer`
 * over an [InMemoryEngine] — no real socket. The client parses a real URL,
 * connects, runs the production client codec, and materialises the
 * response.
 *
 * Each test uses `runTest`'s own wall-clock `timeout` (not an inner
 * `withTimeout`, which `runTest`'s virtual clock would auto-fire) as a
 * guard against an accidental hang in the suspend `read` path.
 */
class KeelHttpClientTest {

    /** Async budget for an in-memory HTTP round-trip. */
    private val asyncBudget = 5.seconds

    private fun urlFor(server: KeelHttpServer, path: String): String {
        val addr = server.localAddress as InetSocketAddress
        val host = if (addr.hostString.contains(':')) "[${addr.hostString}]" else addr.hostString
        return "http://$host:${addr.port}$path"
    }

    @Test
    fun `GET returns the status body and headers`() = runTest(timeout = asyncBudget) {
        val engine = InMemoryEngine()
        val server = keelHttpServer(engine) {
            connector {
                host = "127.0.0.1"
                port = 0
            }
            get("/hello") { call -> call.respondText("Hello, keel") }
        }
        server.start()
        try {
            val client = keelHttpClient(engine)
            val res = client.get(urlFor(server, "/hello"))
            assertEquals(HttpStatus.OK, res.status)
            assertEquals("Hello, keel", res.bodyText())
            // The zero-copy pooled response headers were materialised.
            assertNotNull(res[HttpHeaderName.CONTENT_TYPE], "Content-Type header missing")
        } finally {
            server.stop()
            engine.close()
        }
    }

    @Test
    fun `POST sends the body and Content-Length`() = runTest(timeout = asyncBudget) {
        val engine = InMemoryEngine()
        val server = keelHttpServer(engine) {
            connector {
                host = "127.0.0.1"
                port = 0
            }
            post("/echo") { call -> call.respondText(call.receiveBytes().decodeToString()) }
        }
        server.start()
        try {
            val client = keelHttpClient(engine)
            val res = client.post(urlFor(server, "/echo"), body = "ping-pong".encodeToByteArray())
            assertEquals(HttpStatus.OK, res.status)
            assertEquals("ping-pong", res.bodyText())
        } finally {
            server.stop()
            engine.close()
        }
    }

    @Test
    fun `a query string reaches the server`() = runTest(timeout = asyncBudget) {
        val engine = InMemoryEngine()
        val server = keelHttpServer(engine) {
            connector {
                host = "127.0.0.1"
                port = 0
            }
            get("/q") { call -> call.respondText(call.queryParameters["name"] ?: "none") }
        }
        server.start()
        try {
            val client = keelHttpClient(engine)
            val res = client.get(urlFor(server, "/q?name=keel"))
            assertEquals("keel", res.bodyText())
        } finally {
            server.stop()
            engine.close()
        }
    }

    @Test
    fun `an unmatched route returns 404`() = runTest(timeout = asyncBudget) {
        val engine = InMemoryEngine()
        val server = keelHttpServer(engine) {
            connector {
                host = "127.0.0.1"
                port = 0
            }
            get("/hello") { call -> call.respondText("hi") }
        }
        server.start()
        try {
            val client = keelHttpClient(engine)
            assertEquals(HttpStatus(404), client.get(urlFor(server, "/nope")).status)
        } finally {
            server.stop()
            engine.close()
        }
    }

    @Test
    fun `a caller-supplied Host header is preserved`() = runTest(timeout = asyncBudget) {
        val engine = InMemoryEngine()
        val server = keelHttpServer(engine) {
            connector {
                host = "127.0.0.1"
                port = 0
            }
            get("/host") { call -> call.respondText(call.headers[HttpHeaderName.HOST]?.toString() ?: "none") }
        }
        server.start()
        try {
            val client = keelHttpClient(engine)
            val headers = HttpHeaders().add(HttpHeaderName.HOST, "example.test")
            assertEquals("example.test", client.get(urlFor(server, "/host"), headers).bodyText())
        } finally {
            server.stop()
            engine.close()
        }
    }

    @Test
    fun `a fresh-connect request leaks no pooled buffers`() = runTest(timeout = asyncBudget) {
        val tracking = TrackingAllocator()
        val engine = InMemoryEngine(IoEngineConfig(allocator = tracking))
        val server = keelHttpServer(engine) {
            connector {
                host = "127.0.0.1"
                port = 0
            }
            get("/hello") { call -> call.respondText("Hello") }
        }
        server.start()
        try {
            val client = keelHttpClient(engine)
            repeat(3) { assertEquals(HttpStatus.OK, client.get(urlFor(server, "/hello")).status) }
        } finally {
            server.stop()
            engine.close()
        }
        assertEquals(0, tracking.outstandingCount, "pooled buffers leaked after teardown")
    }
}
