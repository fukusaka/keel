package io.github.fukusaka.keel.testing.http

import java.net.http.HttpClient
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Test-only [HttpClient] wrapper that owns its executor and shuts both down on [close].
 *
 * Solves the `HttpClient.newHttpClient` resource-leak problem: a freshly-built JDK
 * `HttpClient` forks its own selector thread plus an executor pool, and the JDK 21
 * `HttpClient.close()` API must be called explicitly to release them. Without explicit
 * shutdown the selector + executor threads survived the test method, accumulating across
 * the suite and amplifying scheduler-contention slowdowns on resource-constrained CI
 * runners (the symptom that motivated this helper: `NettyPipelineWsEchoTest` on GHA macOS
 * Apple Silicon, where SIGKILL-flake-fixed tests still drifted from sub-second locally to
 * multi-minute on CI before the explicit-lifecycle pattern was introduced).
 *
 * Using a fixed-size daemon executor (instead of the implicit `ForkJoinPool.commonPool()`
 * that `newHttpClient()` falls back to) also keeps `WebSocket.Listener` callbacks off any
 * shared global pool, so callbacks from this test do not interleave with other test
 * classes that might use the common pool.
 *
 * Each consumer should wrap its `HttpClient` use in `newTestHttpClient().use { client ->
 * ... }`. The 4-thread pool size is the minimum that avoids deadlock if a
 * `WebSocket.Listener` callback on the executor needs to wait on another HttpClient
 * operation; tests with more concurrent connections than that should size-up explicitly
 * via [newTestHttpClient]'s [threadPoolSize] parameter.
 *
 * **History**: this helper was duplicated across four sites before consolidation —
 * `TestHttpClient` in `keel-server-ktor` / `keel-server-ktor-cio` jvmTest (PR #483 / #484)
 * and inline `TestWsClient` private classes inside `NettyPipelineWsEchoTest` /
 * `NettyPipelineWsStressTest` (PR #483 / #486). The four copies had identical shape; this
 * module collapses them into a single canonical definition.
 *
 * @property http the underlying [HttpClient], built with the test-owned [executor]
 *   so that `executor` shutdown also drains any pending [HttpClient] callbacks.
 */
public class TestHttpClient(
    public val http: HttpClient,
    private val executor: ExecutorService,
) : AutoCloseable {
    override fun close() {
        http.close()
        executor.shutdown()
        // Bounded; if a callback is genuinely stuck the test harness will surface that
        // separately. 5 s is generous for any in-flight callback to drain.
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }
}

/**
 * Build a [TestHttpClient] with a fixed-size daemon executor.
 *
 * @param threadPoolSize executor pool size. Default 4 is the minimum that avoids
 *   deadlock when a `WebSocket.Listener` callback on the executor must await another
 *   `HttpClient` operation. Tests opening more than ~4 concurrent WebSocket listeners
 *   should size up explicitly so the open burst does not throttle.
 */
public fun newTestHttpClient(threadPoolSize: Int = 4): TestHttpClient {
    val executor = Executors.newFixedThreadPool(threadPoolSize) { runnable ->
        Thread(runnable, "test-http-client").apply { isDaemon = true }
    }
    val http = HttpClient.newBuilder().executor(executor).build()
    return TestHttpClient(http, executor)
}
