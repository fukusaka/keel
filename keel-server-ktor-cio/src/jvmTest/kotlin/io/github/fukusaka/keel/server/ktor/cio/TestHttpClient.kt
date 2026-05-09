package io.github.fukusaka.keel.server.ktor.cio

import java.net.http.HttpClient
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Test-only [HttpClient] wrapper that owns its executor and shuts both down on [close].
 *
 * Mirror of `keel-server-ktor`'s `TestHttpClient` — duplicated here because the two
 * modules have no dependency relationship and there is no `keel-test-fixtures` shared
 * source set yet. If a shared fixture module is introduced later, both copies should
 * collapse into it.
 *
 * Solves the `HttpClient.newHttpClient` resource-leak problem: a freshly-built JDK
 * `HttpClient` forks its own selector thread plus an executor pool, and the JDK 21
 * `HttpClient.close()` API must be called explicitly to release them. Without explicit
 * shutdown the selector + executor threads survived the test method, accumulating across
 * the suite and amplifying scheduler-contention slowdowns on resource-constrained CI
 * runners.
 *
 * Each consumer should wrap its `HttpClient` use in `newTestHttpClient().use { client ->
 * ... }`. The 4-thread pool size is the minimum that avoids deadlock if a
 * `WebSocket.Listener` callback on the executor needs to wait on another HttpClient
 * operation.
 */
internal class TestHttpClient(
    val http: HttpClient,
    private val executor: ExecutorService,
) : AutoCloseable {
    override fun close() {
        http.close()
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }
}

/** Build a [TestHttpClient] with a fixed-size daemon executor. */
internal fun newTestHttpClient(threadPoolSize: Int = 4): TestHttpClient {
    val executor = Executors.newFixedThreadPool(threadPoolSize) { runnable ->
        Thread(runnable, "test-http-client").apply { isDaemon = true }
    }
    val http = HttpClient.newBuilder().executor(executor).build()
    return TestHttpClient(http, executor)
}
