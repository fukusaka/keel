package io.github.fukusaka.keel.testing.server.http

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.codec.http.HttpHeaderName
import io.github.fukusaka.keel.codec.http.HttpHeaders
import io.github.fukusaka.keel.codec.http.HttpResponseHead
import io.github.fukusaka.keel.codec.http.HttpStatus
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for the [keelHttpTest] in-process HTTP test helper.
 *
 * Each test drives a `keelHttpServer` over an
 * [io.github.fukusaka.keel.testing.engine.InMemoryEngine] — no real
 * socket. The in-memory loopback completes synchronously, but every test
 * is bounded by [withTimeout] as a guard against an accidental hang in
 * the suspend `read` path.
 */
class KeelHttpTestTest {

    /** Async budget for an in-memory HTTP round-trip. */
    private val asyncBudget = 5.seconds

    @Test
    fun `a route with a path parameter returns the parameter in the body`() = runTest {
        withTimeout(asyncBudget) {
            keelHttpTest {
                server {
                    get("/users/:id") { call -> call.respondText("user ${call.pathParameters["id"]}") }
                }
                val res = client.get("/users/42")
                assertEquals(HttpStatus.OK, res.status)
                assertEquals("user 42", res.bodyText())
            }
        }
    }

    @Test
    fun `an unmatched route returns 404`() = runTest {
        withTimeout(asyncBudget) {
            keelHttpTest {
                server {
                    get("/hello") { call -> call.respondText("hi") }
                }
                val res = client.get("/nope")
                assertEquals(HttpStatus(404), res.status)
            }
        }
    }

    @Test
    fun `installed middleware observes every request`() = runTest {
        withTimeout(asyncBudget) {
            var seenPaths = 0
            keelHttpTest {
                server {
                    install { call, next ->
                        seenPaths++
                        next()
                    }
                    get("/a") { call -> call.respondText("a") }
                    get("/b") { call -> call.respondText("b") }
                }
                client.get("/a")
                client.get("/b")
            }
            assertEquals(2, seenPaths)
        }
    }

    @Test
    fun `a registered exception mapper turns a thrown exception into a response`() = runTest {
        withTimeout(asyncBudget) {
            keelHttpTest {
                server {
                    exception<IllegalArgumentException> { call, cause ->
                        call.respondText("bad: ${cause.message}", HttpStatus(400))
                    }
                    get("/boom") { _ -> throw IllegalArgumentException("nope") }
                }
                val res = client.get("/boom")
                assertEquals(HttpStatus(400), res.status)
                assertEquals("bad: nope", res.bodyText())
            }
        }
    }

    @Test
    fun `respondText sets status content type and body`() = runTest {
        withTimeout(asyncBudget) {
            keelHttpTest {
                server {
                    get("/text") { call -> call.respondText("plain body", HttpStatus(201)) }
                }
                val res = client.get("/text")
                assertEquals(HttpStatus(201), res.status)
                assertEquals("plain body", res.bodyText())
                val contentType = res.headers[HttpHeaderName.CONTENT_TYPE]
                assertTrue(
                    contentType != null && contentType.contains("text/plain"),
                    "expected text/plain content type, got $contentType",
                )
            }
        }
    }

    @Test
    fun `respondStream streams the body back to the client`() = runTest {
        withTimeout(asyncBudget) {
            keelHttpTest {
                server {
                    get("/stream") { call ->
                        val head = HttpResponseHead(
                            HttpStatus.OK,
                            headers = HttpHeaders.build {
                                add(HttpHeaderName.TRANSFER_ENCODING, "chunked")
                            },
                        )
                        call.respondStream(head) { sink ->
                            sink.write(bufOf("chunk-1"))
                            sink.write(bufOf("chunk-2"))
                        }
                    }
                }
                val res = client.get("/stream")
                assertEquals(HttpStatus.OK, res.status)
                assertEquals("chunk-1chunk-2", res.bodyText())
            }
        }
    }

    @Test
    fun `a POST request body reaches the route handler`() = runTest {
        withTimeout(asyncBudget) {
            keelHttpTest {
                server {
                    post("/echo") { call ->
                        val body = call.receiveBytes().decodeToString()
                        call.respondText("got: $body")
                    }
                }
                val res = client.post("/echo", body = "payload".encodeToByteArray())
                assertEquals(HttpStatus.OK, res.status)
                assertEquals("got: payload", res.bodyText())
            }
        }
    }

    /** Wraps [text] as an [io.github.fukusaka.keel.buf.IoBuf]. */
    private fun bufOf(text: String): io.github.fukusaka.keel.buf.IoBuf {
        val bytes = text.encodeToByteArray()
        val buf = DefaultAllocator.allocate(bytes.size)
        buf.writeByteArray(bytes, 0, bytes.size)
        return buf
    }

    @Test
    fun `a HEAD request returns headers without a body`() = runTest {
        withTimeout(asyncBudget) {
            keelHttpTest {
                server {
                    head("/page") { call -> call.respondText("the page body") }
                }
                val res = client.head("/page")
                assertEquals(HttpStatus.OK, res.status)
                assertEquals(0, res.bodyBytes().size)
            }
        }
    }
}
