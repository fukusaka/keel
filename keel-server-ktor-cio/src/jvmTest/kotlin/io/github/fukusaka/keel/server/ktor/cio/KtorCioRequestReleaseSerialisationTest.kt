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
import kotlinx.coroutines.withTimeout
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Regression test for the borrow ↔ recycle race covered by PR #502.
 *
 * The fix wraps `request.release()` (the `HeadersDataPool.recycle` path)
 * in `parserMutex.withLock { ... }` alongside the existing `parseRequest`
 * (the `HeadersDataPool.borrow` path) so both ends of the pool's
 * borrow/recycle cycle serialise through the same process-wide mutex on
 * Kotlin/Native. The race is timing-dependent at the kernel scheduler
 * level (multi-worker `pthread_mutex` contention escalating from ktor-io
 * `SynchronizedObject`), so a deterministic unit test cannot reproduce
 * the collapse itself; what we can deterministically test is the
 * **implementation contract** — both code paths route through the
 * injected mutex.
 *
 * Test strategy:
 *
 * 1. Subclass [HeaderParseMutex] with a recording wrapper that
 *    increments a counter on every `withLock` invocation while
 *    delegating to the production `super.withLock(block)` (no-op on JVM
 *    here, exercised via a test-only [ApplicationEngineFactory] that
 *    injects the recording mutex into [KtorCioConnectionHandler]).
 * 2. Drive N HTTP requests through a real `embeddedServer(KeelCio-like)`
 *    + [NioEngine] stack. Each completed request walks the keep-alive
 *    loop's borrow path (parseRequest) once and the recycle path
 *    (`request.release()` in `finally`) once.
 * 3. Assert the recording counter has at least `2 * N` calls — proving
 *    both paths route through the mutex. A regression that drops the
 *    `release()` wrap would halve the counter and fail this test.
 *
 * The test runs on JVM where [HeaderParseMutex] is a no-op pass-through;
 * the recording subclass's increment is independent of the platform
 * actual implementation, so the test catches the regression on either
 * platform via the same code path.
 */
class KtorCioRequestReleaseSerialisationTest {

    /**
     * Recording wrapper around [HeaderParseMutex]. Counts every
     * `withLock` invocation while delegating to the production
     * implementation so test setup mirrors production serialisation
     * semantics on every platform actual.
     */
    private class RecordingHeaderParseMutex : HeaderParseMutex() {
        val callCount: AtomicInteger = AtomicInteger(0)

        override suspend fun <T> withLock(block: suspend () -> T): T {
            callCount.incrementAndGet()
            return super.withLock(block)
        }
    }

    /**
     * Test-only factory mirroring [KeelCio] but injecting a custom
     * [HeaderParseMutex] into [KtorCioConnectionHandler]. Otherwise
     * structurally identical to the production factory.
     */
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
    fun `parseRequest borrow and request_release recycle both route through parserMutex`() {
        val recording = RecordingHeaderParseMutex()
        val server = embeddedServer(SpyKeelCioFactory(recording), port = 0) {
            routing { get("/") { call.respondText("ok") } }
        }
        server.engine.configuration.engine = NioEngine()
        server.start(wait = false)
        val totalRequests = REQUESTS_TO_SEND
        try {
            val port = runBlocking {
                withTimeout(15.seconds) {
                    server.engine.resolvedConnectors().first().port
                }
            }
            // Sequential requests on separate connections (no keep-alive
            // shortcut) so each request exercises both borrow and
            // recycle paths fully.
            repeat(totalRequests) { sendOneRequest(port) }
        } finally {
            server.stop(SHUTDOWN_GRACE_MS, SHUTDOWN_TIMEOUT_MS)
        }

        // Each completed request executes exactly two `withLock` calls:
        // one on the borrow side (parseRequest) and one on the recycle
        // side (request.release in finally). A regression that drops the
        // recycle wrap would halve the count.
        val expectedAtLeast = totalRequests * EXPECTED_LOCKS_PER_REQUEST
        val observed = recording.callCount.get()
        assertTrue(
            observed >= expectedAtLeast,
            "expected at least $expectedAtLeast withLock calls (= $totalRequests * " +
                "$EXPECTED_LOCKS_PER_REQUEST per request) but observed $observed; " +
                "a missing wrap on either parseRequest or request.release() would " +
                "drop one call per request",
        )
    }

    private fun sendOneRequest(port: Int) {
        val conn = URI("http://127.0.0.1:$port/").toURL().openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = REQUEST_TIMEOUT_MS
            conn.readTimeout = REQUEST_TIMEOUT_MS
            // Force connection close per request so each request exercises
            // a full parseRequest borrow + request.release() recycle
            // cycle, matching the production keep-alive loop's
            // per-request pattern.
            conn.setRequestProperty("Connection", "close")
            check(conn.responseCode == 200) { "unexpected status ${conn.responseCode}" }
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val REQUESTS_TO_SEND: Int = 8
        const val EXPECTED_LOCKS_PER_REQUEST: Int = 2
        const val REQUEST_TIMEOUT_MS: Int = 5_000
        const val SHUTDOWN_GRACE_MS: Long = 250
        const val SHUTDOWN_TIMEOUT_MS: Long = 1_000
    }
}
