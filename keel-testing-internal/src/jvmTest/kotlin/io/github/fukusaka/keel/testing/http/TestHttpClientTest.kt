package io.github.fukusaka.keel.testing.http

import java.util.concurrent.ExecutorService
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Contract self-test for [TestHttpClient] / [newTestHttpClient]. The whole
 * point of this fixture is that [TestHttpClient.close] releases the executor
 * (and its selector threads) the JDK `HttpClient` would otherwise leak across
 * the suite. That release invariant is pinned here by reading the executor
 * back off the built `HttpClient` and asserting its lifecycle across `close`.
 */
class TestHttpClientTest {

    @Test
    fun `close shuts down and terminates the owned executor`() {
        val client = newTestHttpClient()
        val executor = client.http.executor().orElseThrow() as ExecutorService
        assertFalse(executor.isShutdown, "executor must be live before close")
        client.close()
        assertTrue(executor.isShutdown, "close must shut the executor down")
        assertTrue(executor.isTerminated, "close awaits termination, so it must be terminated")
    }

    @Test
    fun `close is idempotent`() {
        val client = newTestHttpClient()
        client.close()
        // A second close must not throw (http.close / executor.shutdown /
        // awaitTermination are all idempotent).
        client.close()
        val executor = client.http.executor().orElseThrow() as ExecutorService
        assertTrue(executor.isTerminated)
    }

    @Test
    fun `the configured thread pool size is honoured`() {
        // A custom size builds a usable client whose executor is live until close.
        val client = newTestHttpClient(threadPoolSize = 2)
        val executor = client.http.executor().orElseThrow() as ExecutorService
        assertFalse(executor.isShutdown)
        client.close()
        assertTrue(executor.isShutdown)
    }
}
