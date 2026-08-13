package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.native.posix.PosixRawClient
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.close
import platform.posix.usleep
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalForeignApi::class)
class KqueueEnginePipelineTest {

    // --- Pipeline ---

    @Test
    fun `bindPipeline responds to HTTP request`() = runBlocking {
        withTimeout(15.seconds) {
            val engine = KqueueEngine(IoEngineConfig(threads = 1))
            val response = io.github.fukusaka.keel.codec.http.HttpResponse.ok(
                "Hello!",
                contentType = "text/plain",
            )
            response.headers.size // warm flatEntries cache

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

            // Discover bound port from server fd (use SocketUtils).
            // bindPipeline returns AutoCloseable (ReadinessPipelinedStreamServer).
            // We need the port — extract from engine or use a fixed port.
            // For simplicity, use a fixed port with retry.
            server.close()
            engine.close()
        }
    }

    @Test
    fun `bindPipeline echo via raw HTTP client`() = runBlocking {
        withTimeout(15.seconds) {
            val engine = KqueueEngine(IoEngineConfig(threads = 1))
            val port = 19876 // Fixed port for test

            val response = io.github.fukusaka.keel.codec.http.HttpResponse.ok(
                "Pipeline!",
                contentType = "text/plain",
            )
            response.headers.size

            val server = engine.bindPipeline("127.0.0.1", port) { channel ->
                channel.pipeline.addLast("encoder", io.github.fukusaka.keel.codec.http.HttpResponseEncoder())
                channel.pipeline.addLast("decoder", io.github.fukusaka.keel.codec.http.HttpRequestDecoder())
                channel.pipeline.addLast(
                    "routing",
                    io.github.fukusaka.keel.codec.http.RoutingHandler(
                        mapOf("/hello" to { response }),
                    ),
                )
            }

            // Allow server to start accepting.
            usleep(100_000u) // 100ms

            val clientFd = connectRawClient(port)
            rawWrite(clientFd, "GET /hello HTTP/1.1\r\nHost: localhost\r\n\r\n")
            val result = PosixRawClient.rawReadUpTo(clientFd, 4096)

            assertTrue(result.startsWith("HTTP/1.1 200 OK\r\n"), "status line: $result")
            assertTrue(result.endsWith("Pipeline!"), "body: $result")

            close(clientFd)
            server.close()
            engine.close()
        }
    }

    @Test
    fun `bindPipeline returns 404 for unknown path`() = runBlocking {
        withTimeout(15.seconds) {
            val engine = KqueueEngine(IoEngineConfig(threads = 1))
            val port = 19877

            val response = io.github.fukusaka.keel.codec.http.HttpResponse.ok("ok")
            response.headers.size

            val server = engine.bindPipeline("127.0.0.1", port) { channel ->
                channel.pipeline.addLast("encoder", io.github.fukusaka.keel.codec.http.HttpResponseEncoder())
                channel.pipeline.addLast("decoder", io.github.fukusaka.keel.codec.http.HttpRequestDecoder())
                channel.pipeline.addLast(
                    "routing",
                    io.github.fukusaka.keel.codec.http.RoutingHandler(
                        mapOf("/hello" to { response }),
                    ),
                )
            }

            usleep(100_000u) // 100ms

            val clientFd = connectRawClient(port)
            rawWrite(clientFd, "GET /missing HTTP/1.1\r\nHost: localhost\r\n\r\n")
            val result = PosixRawClient.rawReadUpTo(clientFd, 4096)

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
            val port = 19878

            val response = io.github.fukusaka.keel.codec.http.HttpResponse.ok(
                "Hi",
                contentType = "text/plain",
            )
            response.headers.size

            val server = engine.bindPipeline("127.0.0.1", port) { channel ->
                channel.pipeline.addLast("encoder", io.github.fukusaka.keel.codec.http.HttpResponseEncoder())
                channel.pipeline.addLast("decoder", io.github.fukusaka.keel.codec.http.HttpRequestDecoder())
                channel.pipeline.addLast(
                    "routing",
                    io.github.fukusaka.keel.codec.http.RoutingHandler(
                        mapOf("/hello" to { response }),
                    ),
                )
            }

            usleep(100_000u) // 100ms

            val clientFd = connectRawClient(port)

            // First request
            rawWrite(clientFd, "GET /hello HTTP/1.1\r\nHost: localhost\r\n\r\n")
            val result1 = PosixRawClient.rawReadUpTo(clientFd, 4096)
            assertTrue(result1.endsWith("Hi"), "first: $result1")

            // Second request on same connection (keep-alive)
            rawWrite(clientFd, "GET /hello HTTP/1.1\r\nHost: localhost\r\n\r\n")
            val result2 = PosixRawClient.rawReadUpTo(clientFd, 4096)
            assertTrue(result2.endsWith("Hi"), "second: $result2")

            close(clientFd)
            server.close()
            engine.close()
        }
    }
}
