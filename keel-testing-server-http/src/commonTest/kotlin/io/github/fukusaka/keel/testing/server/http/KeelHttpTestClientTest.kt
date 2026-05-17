package io.github.fukusaka.keel.testing.server.http

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.codec.http.HttpHeaderName
import io.github.fukusaka.keel.codec.http.HttpHeaders
import io.github.fukusaka.keel.codec.http.HttpResponseHead
import io.github.fukusaka.keel.codec.http.HttpStatus
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [keelHttpTestClient] / [KeelHttpTestClient] — the in-process
 * HTTP test client. Each test drives a request through the real
 * keel-server-http pipeline over a fake transport and asserts on the
 * parsed [TestHttpResponse].
 *
 * Handlers are suspending and run on the coroutine the pipeline launches,
 * so the async tests are bounded by `runTest`'s own timeout.
 */
class KeelHttpTestClientTest {

    @Test
    fun `a GET to a registered route returns the handler's text response`() = runTest {
        val client = keelHttpTestClient {
            get("/hello") { call -> call.respondText("Hello") }
        }

        val res = client.get("/hello")

        assertEquals(HttpStatus.OK, res.status)
        assertEquals("Hello", res.bodyText())
        assertTrue(
            res.headers[HttpHeaderName.CONTENT_TYPE]?.contains("text/plain") == true,
            "content-type: ${res.headers[HttpHeaderName.CONTENT_TYPE]}",
        )
    }

    @Test
    fun `a path parameter is exposed to the handler`() = runTest {
        val client = keelHttpTestClient {
            get("/users/:id") { call -> call.respondText("user ${call.pathParameters["id"]}") }
        }

        val res = client.get("/users/42")

        assertEquals(HttpStatus.OK, res.status)
        assertEquals("user 42", res.bodyText())
    }

    @Test
    fun `a request with no matching route returns the built-in 404`() = runTest {
        val client = keelHttpTestClient {
            get("/known") { call -> call.respondText("ok") }
        }

        val res = client.get("/unknown")

        assertEquals(HttpStatus.NOT_FOUND, res.status)
    }

    @Test
    fun `a custom notFound handler replaces the built-in 404`() = runTest {
        val client = keelHttpTestClient {
            notFound { call -> call.respondText("nothing here", HttpStatus.NOT_FOUND) }
        }

        val res = client.get("/missing")

        assertEquals(HttpStatus.NOT_FOUND, res.status)
        assertEquals("nothing here", res.bodyText())
    }

    @Test
    fun `installed middleware observes the request before the handler`() = runTest {
        var middlewareRan = false
        val client = keelHttpTestClient {
            install { call, next ->
                middlewareRan = true
                next()
            }
            get("/ping") { call -> call.respondText("pong") }
        }

        val res = client.get("/ping")

        assertTrue(middlewareRan, "middleware must run")
        assertEquals("pong", res.bodyText())
    }

    @Test
    fun `a registered exception mapper turns a thrown exception into a response`() = runTest {
        val client = keelHttpTestClient {
            exception<IllegalStateException> { call, cause ->
                call.respondText("mapped: ${cause.message}", HttpStatus.BAD_REQUEST)
            }
            get("/boom") { error("kaboom") }
        }

        val res = client.get("/boom")

        assertEquals(HttpStatus.BAD_REQUEST, res.status)
        assertEquals("mapped: kaboom", res.bodyText())
    }

    @Test
    fun `an unmapped thrown exception falls back to the built-in 500`() = runTest {
        val client = keelHttpTestClient {
            get("/boom") { throw RuntimeException("unhandled") }
        }

        val res = client.get("/boom")

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, res.status)
    }

    @Test
    fun `respondStream delivers the streamed body`() = runTest {
        val client = keelHttpTestClient {
            get("/stream") { call ->
                val head = HttpResponseHead(
                    HttpStatus.OK,
                    headers = HttpHeaders.of(HttpHeaderName.TRANSFER_ENCODING to "chunked"),
                )
                call.respondStream(head) { sink ->
                    sink.write(bufOf("chunk-one"))
                    sink.write(bufOf("chunk-two"))
                }
            }
        }

        val res = client.get("/stream")

        assertEquals(HttpStatus.OK, res.status)
        assertEquals("chunk-onechunk-two", res.bodyText())
    }

    @Test
    fun `a POST body is delivered to the handler`() = runTest {
        val client = keelHttpTestClient {
            post("/echo") { call ->
                val received = call.receiveBytes().decodeToString()
                call.respondText("got: $received")
            }
        }

        val res = client.post("/echo", body = "payload".encodeToByteArray())

        assertEquals(HttpStatus.OK, res.status)
        assertEquals("got: payload", res.bodyText())
    }

    @Test
    fun `an empty POST body yields an empty handler-visible body`() = runTest {
        val client = keelHttpTestClient {
            post("/echo") { call ->
                val received = call.receiveBytes()
                call.respondText("len=${received.size}")
            }
        }

        val res = client.post("/echo", body = ByteArray(0))

        assertEquals("len=0", res.bodyText())
    }

    @Test
    fun `a HEAD response carries no body`() = runTest {
        val client = keelHttpTestClient {
            head("/page") { call -> call.respondText("body-text") }
        }

        val res = client.head("/page")

        assertEquals(HttpStatus.OK, res.status)
        assertEquals(0, res.bodyBytes().size)
    }

    @Test
    fun `each method shorthand routes to its own handler`() = runTest {
        val client = keelHttpTestClient {
            get("/r") { call -> call.respondText("GET") }
            post("/r") { call -> call.respondText("POST") }
            put("/r") { call -> call.respondText("PUT") }
            delete("/r") { call -> call.respondText("DELETE") }
            patch("/r") { call -> call.respondText("PATCH") }
            options("/r") { call -> call.respondText("OPTIONS") }
        }

        assertEquals("GET", client.get("/r").bodyText())
        assertEquals("POST", client.post("/r").bodyText())
        assertEquals("PUT", client.put("/r").bodyText())
        assertEquals("DELETE", client.delete("/r").bodyText())
        assertEquals("PATCH", client.patch("/r").bodyText())
        assertEquals("OPTIONS", client.options("/r").bodyText())
    }

    @Test
    fun `a custom request header reaches the handler`() = runTest {
        val client = keelHttpTestClient {
            get("/whoami") { call -> call.respondText("user=${call.headers["X-User"]}") }
        }

        val res = client.get("/whoami", headers = HttpHeaders.of("X-User" to "alice"))

        assertEquals("user=alice", res.bodyText())
    }

    @Test
    fun `requests on the same client are independent`() = runTest {
        val client = keelHttpTestClient {
            get("/a") { call -> call.respondText("A") }
            get("/b") { call -> call.respondText("B") }
        }

        assertEquals("A", client.get("/a").bodyText())
        assertEquals("B", client.get("/b").bodyText())
        // The fresh-pipeline-per-request guarantee: re-issuing works.
        assertEquals("A", client.get("/a").bodyText())
    }

    @Test
    fun `a status set by respondText is parsed back`() = runTest {
        val client = keelHttpTestClient {
            post("/create") { call -> call.respondText("created", HttpStatus.CREATED) }
        }

        val res = client.post("/create")

        assertEquals(HttpStatus.CREATED, res.status)
    }

    @Test
    fun `an absent response header reads as null`() = runTest {
        val client = keelHttpTestClient {
            get("/plain") { call -> call.respondText("x") }
        }

        val res = client.get("/plain")

        assertNull(res.headers["X-Nonexistent-Header"])
    }

    /** Allocates an [io.github.fukusaka.keel.buf.IoBuf] holding [text]. */
    private fun bufOf(text: String) =
        DefaultAllocator.allocate(text.encodeToByteArray().size).also { buf ->
            val bytes = text.encodeToByteArray()
            buf.writeByteArray(bytes, 0, bytes.size)
        }
}
