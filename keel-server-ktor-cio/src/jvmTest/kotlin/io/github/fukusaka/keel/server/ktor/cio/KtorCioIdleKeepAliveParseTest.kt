package io.github.fukusaka.keel.server.ktor.cio

import io.github.fukusaka.keel.engine.nio.NioEngine
import io.github.fukusaka.keel.server.ktor.KeelApplicationEngine
import io.ktor.events.Events
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.engine.ApplicationEngineFactory
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Regression tests: a connection the server cannot finish reading a request
 * head from must not block other connections' request parsing.
 *
 * `HeaderParseMutex` serialises ktor-http-cio's `HeadersDataPool` borrow ↔
 * recycle cycle, and its Native actual is a process-wide
 * [kotlinx.coroutines.sync.Mutex] shared by every connection. `parseRequest`
 * suspends until a full request head arrives, so holding the mutex across it
 * let a single connection stall every other connection's parse. Three peers
 * trigger it, each reproduced here with two connections:
 *
 * - one idling between keep-alive requests (the steady state of any pooled
 *   HTTP client),
 * - one sending only the leading `CRLF` that RFC 9112 3.5 permits, which
 *   `parseRequest` skips before waiting for the request line,
 * - one stopping mid-request-line.
 *
 * The fix buffers the whole head **outside** the mutex, so the locked
 * `parseRequest` only consumes bytes that already arrived.
 *
 * The JVM actual of [HeaderParseMutex] is a no-op pass-through, so these tests
 * inject a real-mutex subclass through the existing constructor seam to
 * exercise the Native semantics on the JVM.
 */
class KtorCioIdleKeepAliveParseTest {

    /** Mirrors the Native actual of [HeaderParseMutex] (process-wide real mutex). */
    private class RealMutexHeaderParseMutex : HeaderParseMutex() {
        private val mutex = Mutex()

        override suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
    }

    private class SpyKeelCioFactory(
        private val parserMutex: HeaderParseMutex,
    ) : ApplicationEngineFactory<KeelApplicationEngine, KeelApplicationEngine.Configuration> {
        override fun configuration(
            configure: KeelApplicationEngine.Configuration.() -> Unit,
        ): KeelApplicationEngine.Configuration =
            KeelApplicationEngine.Configuration().apply(configure)

        override fun create(
            environment: ApplicationEnvironment,
            monitor: Events,
            developmentMode: Boolean,
            configuration: KeelApplicationEngine.Configuration,
            applicationProvider: () -> Application,
        ): KeelApplicationEngine = KeelApplicationEngine(
            environment = environment,
            monitor = monitor,
            developmentMode = developmentMode,
            configuration = configuration,
            applicationProvider = applicationProvider,
            connectionHandler = KtorCioConnectionHandler(parserMutex = parserMutex),
        )
    }

    @Test
    fun `idle keep-alive connection does not block another connection's request`() {
        withStalledConnection { idle ->
            // One request, response consumed, then held open and silent — the
            // keep-alive idle state of any pooled HTTP client.
            idle.getOutputStream().write(KEEP_ALIVE_REQUEST.toByteArray())
            idle.getOutputStream().flush()
            val idleReader = BufferedReader(InputStreamReader(idle.getInputStream()))
            assertEquals("HTTP/1.1 200 OK", idleReader.readLine()?.trim(), "connection A must be served")
            drainHeadersAndBody(idleReader)
        }
    }

    @Test
    fun `connection sending only a leading CRLF does not block another connection's request`() {
        withStalledConnection { stalled ->
            // RFC 9112 3.5 lets a client send CRLFs before the request line, and
            // `parseRequest` skips them and waits for the real line. Waiting for
            // the first byte is not enough to keep the mutex free — the whole
            // head must arrive first.
            stalled.getOutputStream().write("\r\n".toByteArray())
            stalled.getOutputStream().flush()
        }
    }

    @Test
    fun `connection stopping mid-request-line does not block another connection's request`() {
        withStalledConnection { stalled ->
            // A truncated request line leaves `parseRequest` suspended inside
            // `readLineStrictTo`, holding the mutex if it was taken first.
            stalled.getOutputStream().write("GET /partial".toByteArray())
            stalled.getOutputStream().flush()
        }
    }

    /**
     * Runs [stall] on one connection, then asserts a second connection is still
     * served. [stall] must leave its socket open with the server unable to
     * complete a request head on it.
     */
    private fun withStalledConnection(stall: (Socket) -> Unit) {
        val server = embeddedServer(SpyKeelCioFactory(RealMutexHeaderParseMutex()), port = 0) {
            routing { get("/") { call.respondText("ok") } }
        }
        server.engine.configuration.engine = NioEngine()
        server.engine.configuration.keepAlive = true
        server.start(wait = false)
        try {
            val port = runBlocking {
                withTimeout(STARTUP_TIMEOUT) { server.engine.resolvedConnectors().first().port }
            }

            Socket("127.0.0.1", port).use { stalled ->
                stalled.soTimeout = REQUEST_TIMEOUT_MS
                stall(stalled)

                // The probe must be served while the first connection stalls.
                // Pre-fix its request never reaches `parseRequest`, because the
                // stalled connection's keep-alive loop parked inside the mutex.
                Socket("127.0.0.1", port).use { probe ->
                    probe.soTimeout = REQUEST_TIMEOUT_MS
                    probe.getOutputStream().write(CLOSE_REQUEST.toByteArray())
                    probe.getOutputStream().flush()
                    val probeReader = BufferedReader(InputStreamReader(probe.getInputStream()))
                    val statusLine = try {
                        probeReader.readLine()
                    } catch (_: SocketTimeoutException) {
                        null
                    }
                    assertEquals(
                        "HTTP/1.1 200 OK",
                        statusLine?.trim(),
                        "the probe connection must be served while the other connection stalls; " +
                            "a null/timeout status means the parse mutex was held across the wait",
                    )
                }
            }
        } finally {
            server.stop(SHUTDOWN_GRACE_MS, SHUTDOWN_TIMEOUT_MS)
        }
    }

    /** Reads the remaining response headers plus the fixed-size body. */
    private fun drainHeadersAndBody(reader: BufferedReader) {
        var contentLength = 0
        while (true) {
            val line = reader.readLine() ?: return
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = line.substringAfter(':').trim().toInt()
            }
        }
        repeat(contentLength) { reader.read() }
    }

    private companion object {
        const val KEEP_ALIVE_REQUEST =
            "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\n\r\n"
        const val CLOSE_REQUEST =
            "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"

        /** Healthy responses land in milliseconds; 5s of silence means the stall. */
        const val REQUEST_TIMEOUT_MS: Int = 5_000
        val STARTUP_TIMEOUT = 15.seconds
        const val SHUTDOWN_GRACE_MS: Long = 250
        const val SHUTDOWN_TIMEOUT_MS: Long = 1_000
    }
}
