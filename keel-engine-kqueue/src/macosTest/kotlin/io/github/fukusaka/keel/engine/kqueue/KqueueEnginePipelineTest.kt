package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.native.posix.PosixRawClient
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.close
import platform.posix.usleep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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
            response.headers.size

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
            response.headers.size

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

            // Distinguishable bodies, because that is what says which request a
            // response answers. With both answers identical, a server that
            // repeats the first one and never answers the second is
            // indistinguishable from one that answered both -- measured: it
            // passed in 419 ms, status line, response count and body all
            // satisfied by the stale copy.
            val first = io.github.fukusaka.keel.codec.http.HttpResponse.ok("Hi", contentType = "text/plain")
            val second = io.github.fukusaka.keel.codec.http.HttpResponse.ok("Encore", contentType = "text/plain")

            val server = engine.bindPipeline("127.0.0.1", 0) { channel ->
                channel.pipeline.addLast("encoder", io.github.fukusaka.keel.codec.http.HttpResponseEncoder())
                channel.pipeline.addLast("decoder", io.github.fukusaka.keel.codec.http.HttpRequestDecoder())
                channel.pipeline.addLast(
                    "routing",
                    io.github.fukusaka.keel.codec.http.RoutingHandler(
                        mapOf("/hello" to { first }, "/encore" to { second }),
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
            rawWrite(clientFd, "GET /encore HTTP/1.1\r\nHost: localhost\r\n\r\n")
            val result2 = PosixRawClient.rawReadUntil(clientFd, 4096) { it.endsWith("Encore") }
            assertTrue(
                result2.startsWith("HTTP/1.1 200 OK\r\n"),
                "the second response must carry a 200 status line, got: $result2",
            )
            assertEquals(1, result2.responseCount(), "one response, not a leftover queue: $result2")
            // "Encore", not "Hi": this is what a repeat of the first answer
            // cannot satisfy, whenever it arrives.
            assertTrue(
                result2.endsWith("Encore"),
                "the second request on the same connection must answer Encore, got: $result2",
            )

            close(clientFd)
            server.close()
            engine.close()
        }
    }
}

/** How many HTTP response heads the payload holds. One is the whole point. */
private fun String.responseCount(): Int = split("HTTP/1.1").size - 1
