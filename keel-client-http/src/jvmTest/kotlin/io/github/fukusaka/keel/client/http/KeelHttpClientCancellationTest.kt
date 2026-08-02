package io.github.fukusaka.keel.client.http

import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.client.http.dsl.keelHttpClient
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.server.http.KeelHttpServer
import io.github.fukusaka.keel.server.http.dsl.keelHttpServer
import io.github.fukusaka.keel.testing.engine.InMemoryEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

/**
 * Guards the client's cancellation-teardown invariant: cancelling a request
 * that is parked waiting for a response must not leak a pooled buffer. Uses
 * `runBlocking` (real wall-clock time, deterministic) rather than `runTest`
 * (virtual time) so the `withTimeout` deadline and the hanging handler
 * interleave as they would in production.
 *
 * A route that never responds keeps `request()` suspended in the bridge
 * receive; the caller's `withTimeout` then cancels it, and the `notifyInactive`
 * + `close` teardown must free every pooled buffer the request touched (the
 * request bytes the server received, plus any codec-held state) — asserted with
 * `TrackingAllocator.outstandingCount == 0`.
 *
 * Scope note: because the server sends nothing, no response is ever buffered on
 * the bridge, so this does NOT exercise the `releaseUndelivered` hook or the
 * release-in-`finally` around materialisation — those cover a response that
 * arrives and is then stranded by cancellation, a race the synchronous
 * in-memory loopback cannot reproduce deterministically.
 */
class KeelHttpClientCancellationTest {

    private fun urlFor(server: KeelHttpServer, path: String): String {
        val addr = server.localAddress as InetSocketAddress
        return "http://${addr.hostString}:${addr.port}$path"
    }

    @Test
    fun `a cancelled request releases pooled buffers and closes the connection`() = runBlocking {
        val tracking = TrackingAllocator()
        val engine = InMemoryEngine(IoEngineConfig(allocator = tracking))
        // Never completed until teardown, so the handler suspends and the
        // client's request stays parked in the bridge receive.
        val gate = CompletableDeferred<Unit>()
        val server = keelHttpServer(engine) {
            connector {
                host = "127.0.0.1"
                port = 0
            }
            get("/hang") { call ->
                gate.await()
                call.respondText("unreachable")
            }
        }
        server.start()
        try {
            val client = keelHttpClient(engine)
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(1.seconds) { client.get(urlFor(server, "/hang")) }
            }
        } finally {
            gate.complete(Unit)
            server.stop()
            engine.close()
        }
        assertEquals(0, tracking.outstandingCount, "cancelled request leaked pooled buffers")
    }
}
