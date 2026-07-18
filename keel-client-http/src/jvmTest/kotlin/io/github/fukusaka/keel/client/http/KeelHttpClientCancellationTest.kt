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
 * Regression test for the client's cancellation teardown. Uses `runBlocking`
 * (real wall-clock time, deterministic) rather than `runTest` (virtual time)
 * so the `withTimeout` deadline and the hanging handler interleave as they
 * would in production.
 *
 * A route that never responds keeps `request()` suspended in the bridge
 * receive; the caller's `withTimeout` then cancels it. The teardown
 * (`notifyInactive` + `close`, plus the bridge's `releaseUndelivered` hook and
 * the release-in-`finally` around materialisation) must free every pooled
 * buffer — asserted with `TrackingAllocator.outstandingCount == 0`.
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
            connector { host = "127.0.0.1"; port = 0 }
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
