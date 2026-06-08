package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.codec.http.HttpRequestDecoder
import io.github.fukusaka.keel.codec.http.HttpResponse
import io.github.fukusaka.keel.codec.http.HttpResponseEncoder
import io.github.fukusaka.keel.codec.http.RoutingHandler
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.native.posix.PosixRawClient
import io.github.fukusaka.keel.native.posix.ReadResult
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.close
import platform.posix.usleep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for the read-side idle (no-progress) timeout — the time-axis
 * defence against silent / stalled peers ([IoEngineConfig.idleTimeoutMillis]).
 *
 * Real sockets and real wall-clock timing, so each test is wrapped in a
 * `withTimeout` envelope (15 s) per the testing standard. The idle timeout itself
 * is small ([IDLE_MS]) so the closing case resolves well inside the envelope.
 */
@OptIn(ExperimentalForeignApi::class)
class KqueueEngineIdleTimeoutTest {

    @Test
    fun `a silent client is closed after the idle timeout`() = runBlocking {
        withTimeout(15.seconds) {
            val engine = KqueueEngine(IoEngineConfig(threads = 1, idleTimeoutMillis = IDLE_MS))
            val server = engine.bindPipeline("127.0.0.1", SILENT_PORT) { channel ->
                // Decoder enables reads; the client never sends, so nothing is decoded.
                channel.pipeline.addLast("decoder", HttpRequestDecoder())
            }
            usleep(SERVER_START_US)
            val clientFd = connectRawClient(SILENT_PORT)
            // Send nothing. The server must idle-close within ~IDLE_MS, so a single
            // read observes EOF rather than blocking until the 5 s SO_RCVTIMEO.
            val result = PosixRawClient.rawReadOnce(clientFd, 64, 5.seconds)
            assertEquals(ReadResult.Eof, result, "server should idle-close a silent client; got $result")
            close(clientFd)
            server.close()
            engine.close()
        }
    }

    @Test
    fun `a client active within the idle window is not closed`() = runBlocking {
        withTimeout(15.seconds) {
            val response = HttpResponse.ok("Hi", contentType = "text/plain")
            response.headers.size // warm the flat-entries cache
            val engine = KqueueEngine(IoEngineConfig(threads = 1, idleTimeoutMillis = IDLE_MS))
            val server = engine.bindPipeline("127.0.0.1", ACTIVE_PORT) { channel ->
                channel.pipeline.addLast("encoder", HttpResponseEncoder())
                channel.pipeline.addLast("decoder", HttpRequestDecoder())
                channel.pipeline.addLast("routing", RoutingHandler(mapOf("/hello" to { response })))
            }
            usleep(SERVER_START_US)
            val clientFd = connectRawClient(ACTIVE_PORT)
            // Send the request in two pieces separated by a gap shorter than IDLE_MS.
            // Each piece refreshes the deadline, so the connection survives the gap
            // and still produces a response (the timeout never fires on a progressing
            // connection).
            rawWrite(clientFd, "GET /hello HTTP/1.1\r\n")
            usleep(GAP_US) // < IDLE_MS
            rawWrite(clientFd, "Host: localhost\r\n\r\n")
            val result = PosixRawClient.rawReadUpTo(clientFd, 4096)
            assertTrue(result.startsWith("HTTP/1.1 200 OK"), "active client should get a response; got: $result")
            close(clientFd)
            server.close()
            engine.close()
        }
    }

    private companion object {
        const val IDLE_MS = 500L
        const val SERVER_START_US: UInt = 100_000u
        const val GAP_US: UInt = 300_000u // 300 ms < IDLE_MS (500 ms)
        const val SILENT_PORT = 19891
        const val ACTIVE_PORT = 19892
    }
}
