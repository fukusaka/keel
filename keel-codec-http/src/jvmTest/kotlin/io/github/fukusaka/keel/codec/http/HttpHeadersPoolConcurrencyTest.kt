package io.github.fukusaka.keel.codec.http

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression test for the [HttpHeadersPool] thread-safety bug.
 *
 * The pool is exercised concurrently from many threads, mirroring how
 * keel's multi-worker EventLoop engines (NIO / epoll / io_uring,
 * `workerGroup` of `availableProcessors()` threads by default) all call
 * `HttpHeaders.borrow()` / `release()` from their own worker thread as
 * connections are accepted and served.
 *
 * Before the thread-local fix, the global `ArrayDeque` backing the pool
 * corrupts under concurrent `removeLast` / `addLast`, throwing
 * `ArrayIndexOutOfBoundsException` (observed in production as
 * `Index -2 out of bounds for length 10` killing the worker threads).
 * This test reproduces that crash (Red) and verifies the fix (Green).
 */
class HttpHeadersPoolConcurrencyTest {

    @AfterTest
    fun cleanup() {
        HttpHeadersPool.clear()
    }

    @Test
    fun `concurrent borrow and release does not corrupt the pool`() {
        val threadCount = 16
        val iterationsPerThread = 200_000
        val errors = ConcurrentLinkedQueue<Throwable>()

        val workers = (0 until threadCount).map { t ->
            thread(name = "pool-concurrency-$t") {
                try {
                    repeat(iterationsPerThread) {
                        val h = HttpHeaders.borrow()
                        // Touch the borrowed instance the way the request
                        // decoder does, so reuse exercises the backing
                        // arrays and a corrupted/shared instance surfaces.
                        h.add("Connection", "keep-alive")
                        h.add("Content-Length", "13")
                        h.release()
                    }
                } catch (t: Throwable) {
                    errors.add(t)
                }
            }
        }
        workers.forEach { it.join() }

        assertTrue(
            errors.isEmpty(),
            "concurrent borrow/release corrupted the pool: " +
                "${errors.size} thread(s) threw, first = ${errors.firstOrNull()}",
        )
    }
}
