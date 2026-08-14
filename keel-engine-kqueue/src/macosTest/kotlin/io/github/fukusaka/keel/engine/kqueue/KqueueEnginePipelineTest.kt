package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.native.posix.PosixRawClient
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.close
import platform.posix.usleep
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalForeignApi::class)
class KqueueEnginePipelineTest {

    // --- Pipeline ---

    @Test
    fun `bindPipeline echo via raw HTTP client`() = runBlocking {
        withTimeout(15.seconds) {
            val engine = KqueueEngine(IoEngineConfig(threads = 1))

            val response = io.github.fukusaka.keel.codec.http.HttpResponse.ok(
                "Pipeline!",
                contentType = "text/plain",
            )

            val server = engine.bindPipeline("127.0.0.1", 0) { channel ->
                channel.pipeline.addLast("encoder", io.github.fukusaka.keel.codec.http.HttpResponseEncoder())
                channel.pipeline.addLast("decoder", io.github.fukusaka.keel.codec.http.HttpRequestDecoder())
                channel.pipeline.addLast(
                    "routing",
                    io.github.fukusaka.keel.codec.http.RoutingHandler(
                        mapOf("/hello" to { response }),
                    ),
                )
            }

            // The kernel picks the port; reading it back is what makes the
            // test independent of what else holds a port on the host -- a
            // second copy of this suite included. The three numbers it
            // replaced were distinct, one per test, so they never collided
            // with each other; what a fixed number cannot survive is anything
            // outside the file already holding it.
            val port = (server.localAddress as InetSocketAddress).port

            // Allow server to start accepting.
            usleep(100_000u) // 100ms

            val clientFd = connectRawClient(port)
            rawWrite(clientFd, "GET /hello HTTP/1.1\r\nHost: localhost\r\n\r\n")
            // Until the body, not until the timer: the server keeps the
            // connection open, so a plain read-up-to would sit out the full
            // SO_RCVTIMEO on every call with the response already in hand.
            val result = PosixRawClient.rawReadUntil(clientFd, 4096) { it.endsWith("Pipeline!") }

            assertTrue(result.startsWith("HTTP/1.1 200 OK\r\n"), "status line: $result")
            assertTrue(result.endsWith("Pipeline!"), "body: $result")
            assertEquals(1, result.responseCount(), "one response, not a leftover queue: $result")
            // One request, one response, and nothing after it. Stopping at the
            // body means anything the server sends next is simply unread, and
            // this test has no later read to find it -- measured: a server
            // emitting a second, different response 300 ms later passed.
            assertEquals(
                "",
                PosixRawClient.rawReadUpTo(clientFd, 256, QUIET_AFTER_RESPONSE),
                "the server must send nothing after the response it was asked for",
            )

            close(clientFd)
            server.close()
            engine.close()
        }
    }

    @Test
    fun `bindPipeline returns 404 for unknown path`() = runBlocking {
        withTimeout(15.seconds) {
            val engine = KqueueEngine(IoEngineConfig(threads = 1))

            val response = io.github.fukusaka.keel.codec.http.HttpResponse.ok("ok")

            val server = engine.bindPipeline("127.0.0.1", 0) { channel ->
                channel.pipeline.addLast("encoder", io.github.fukusaka.keel.codec.http.HttpResponseEncoder())
                channel.pipeline.addLast("decoder", io.github.fukusaka.keel.codec.http.HttpRequestDecoder())
                channel.pipeline.addLast(
                    "routing",
                    io.github.fukusaka.keel.codec.http.RoutingHandler(
                        mapOf("/hello" to { response }),
                    ),
                )
            }

            val port = (server.localAddress as InetSocketAddress).port
            usleep(100_000u) // 100ms

            val clientFd = connectRawClient(port)
            rawWrite(clientFd, "GET /missing HTTP/1.1\r\nHost: localhost\r\n\r\n")
            // The status line is what this asserts, and the blank line ends
            // the head that carries it -- waiting past that is waiting on the
            // timer alone.
            val result = PosixRawClient.rawReadUntil(clientFd, 4096) { it.contains("\r\n\r\n") }

            assertTrue(result.startsWith("HTTP/1.1 404 Not Found\r\n"), "status: $result")

            close(clientFd)
            server.close()
            engine.close()
        }
    }

    @Test
    fun `bindPipeline handles multiple requests on same connection`() = runBlocking {
        withTimeout(15.seconds) {
            val engine = KqueueEngine(IoEngineConfig(threads = 1))

            // Request 2 carries a nonce and its answer echoes it, because that
            // is the only thing that says the answer was produced *by* request
            // 2. Distinguishing the two bodies is not enough: it rules out a
            // repeat of answer 1, and leaves a copy of answer 2 emitted before
            // request 2 was ever written -- measured, that passed in 424 ms with
            // status line, response count and body all satisfied. A server that
            // has not read the nonce cannot put it in a response.
            val nonce = Random.nextInt(1_000_000).toString()
            val expected2 = "Encore:$nonce"
            val first = io.github.fukusaka.keel.codec.http.HttpResponse.ok("Hi", contentType = "text/plain")

            val server = engine.bindPipeline("127.0.0.1", 0) { channel ->
                channel.pipeline.addLast("encoder", io.github.fukusaka.keel.codec.http.HttpResponseEncoder())
                channel.pipeline.addLast("decoder", io.github.fukusaka.keel.codec.http.HttpRequestDecoder())
                channel.pipeline.addLast(
                    "routing",
                    io.github.fukusaka.keel.codec.http.RoutingHandler(
                        mapOf(
                            "/hello" to { _: io.github.fukusaka.keel.codec.http.HttpRequestHead -> first },
                            "/encore" to { head: io.github.fukusaka.keel.codec.http.HttpRequestHead ->
                                io.github.fukusaka.keel.codec.http.HttpResponse.ok(
                                    "Encore:${head.headers["X-Nonce"] ?: "absent"}",
                                    contentType = "text/plain",
                                )
                            },
                        ),
                    ),
                )
            }

            val port = (server.localAddress as InetSocketAddress).port
            usleep(100_000u) // 100ms

            val clientFd = connectRawClient(port)

            // First request
            rawWrite(clientFd, "GET /hello HTTP/1.1\r\nHost: localhost\r\n\r\n")
            // The predicate stops the read at "Hi", so asserting the same
            // string back is a tautology on the success path. The status line
            // is what the read did not already establish -- it is what fails
            // when the response is wrong rather than absent.
            val result1 = PosixRawClient.rawReadUntil(clientFd, 4096) { it.endsWith("Hi") }
            assertTrue(
                result1.startsWith("HTTP/1.1 200 OK\r\n"),
                "the first response must carry a 200 status line, got: $result1",
            )
            // Exactly one, because the read no longer establishes it: the drain
            // this replaced emptied the socket, so nothing could be left over
            // for the next read to find. This catches a duplicate that arrives
            // together with its original; the differing bodies below catch one
            // that arrives later.
            assertEquals(1, result1.responseCount(), "one response, not a leftover queue: $result1")
            assertTrue(
                result1.endsWith("Hi"),
                "the first request on this connection must answer Hi, got: $result1",
            )

            // Second request on same connection (keep-alive), to the other path
            rawWrite(clientFd, "GET /encore HTTP/1.1\r\nHost: localhost\r\nX-Nonce: $nonce\r\n\r\n")
            val result2 = PosixRawClient.rawReadUntil(clientFd, 4096) { it.endsWith(expected2) }
            assertTrue(
                result2.startsWith("HTTP/1.1 200 OK\r\n"),
                "the second response must carry a 200 status line, got: $result2",
            )
            assertEquals(1, result2.responseCount(), "one response, not a leftover queue: $result2")
            // The nonce, not just a different body: this is what neither a
            // repeat of answer 1 nor an early copy of answer 2 can satisfy.
            assertTrue(
                result2.endsWith(expected2),
                "the second request must be answered with its own nonce ($expected2), got: $result2",
            )
            // Nothing after the last answer either -- read 2's nonce judges what
            // arrives before it, and nothing judges what arrives after.
            assertEquals(
                "",
                PosixRawClient.rawReadUpTo(clientFd, 256, QUIET_AFTER_RESPONSE),
                "the server must send nothing after the second response",
            )

            close(clientFd)
            server.close()
            engine.close()
        }
    }
}

/**
 * How many times the payload carries the version token — the response heads,
 * since none of the bodies these tests serve contain one. One is the point.
 */
private fun String.responseCount(): Int = split("HTTP/1.1").size - 1

/**
 * How long a test waits to be sure the server has stopped talking.
 *
 * A bound, not a proof — the same kind of thing the five-second drain was, an
 * order of magnitude cheaper. Anything the server emits after this is unread and
 * unjudged, so the number is chosen against the defect it is for: an extra
 * response written from off the event loop, which arrives late by however long
 * that path takes. 250 ms was measured to miss one at 300 ms.
 */
private val QUIET_AFTER_RESPONSE = 500.milliseconds
