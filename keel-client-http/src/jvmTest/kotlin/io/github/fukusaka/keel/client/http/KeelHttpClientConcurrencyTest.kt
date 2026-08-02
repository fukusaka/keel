package io.github.fukusaka.keel.client.http

import io.github.fukusaka.keel.client.http.dsl.keelHttpClient
import io.github.fukusaka.keel.codec.http.HttpStatus
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.engine.nio.NioEngine
import io.github.fukusaka.keel.server.http.dsl.keelHttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Regression test for cross-thread corruption of the pooled response headers.
 *
 * The response decoder borrows `HttpHeaders` from a per-EventLoop-thread pool,
 * so the client must release them on that same EventLoop thread. Materialising
 * and releasing on the caller's coroutine thread instead (a different thread on
 * a real multi-worker engine) corrupts the thread-local pool — the symptoms are
 * `NullPointerException` in `HttpHeaders.markCheckedOut` and
 * `IllegalStateException: Buffer already released`.
 *
 * This drives many concurrent keep-alive requests on a real [NioEngine] (whose
 * EventLoop runs on worker threads distinct from `Dispatchers.Default`, where
 * the requests are launched) so the corruption surfaces if the release runs off
 * the EventLoop thread.
 */
class KeelHttpClientConcurrencyTest {

    @Test
    fun `concurrent keep-alive requests do not corrupt the header pool`() = runBlocking {
        withTimeout(60.seconds) {
            val engine = NioEngine()
            val server = keelHttpServer(engine) {
                connector {
                    host = "127.0.0.1"
                    port = 0
                }
                get("/hello") { call -> call.respondText("hello world") }
            }
            server.start()
            val port = (server.localAddress as InetSocketAddress).port
            val url = "http://127.0.0.1:$port/hello"
            val client = keelHttpClient(engine) { pool { maxIdleConnectionsPerRoute = WORKERS } }
            try {
                coroutineScope {
                    repeat(WORKERS) {
                        launch(Dispatchers.Default) {
                            repeat(REQUESTS_PER_WORKER) {
                                val res = client.get(url)
                                assertEquals(HttpStatus.OK, res.status)
                                assertEquals("hello world", res.bodyText())
                            }
                        }
                    }
                }
            } finally {
                client.close()
                server.stop()
                engine.close()
            }
        }
    }

    private companion object {
        private const val WORKERS = 48
        private const val REQUESTS_PER_WORKER = 400
    }
}
