package io.github.fukusaka.keel.engine.nio

import io.github.fukusaka.keel.core.IoEngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Regression tests for [NioServer.close] thread safety.
 *
 * The close() method is documented as safe to call from any thread: the
 * boss-EventLoop-side accept callback, the coroutine that issued accept(),
 * and arbitrary dispatcher threads can all race.
 *
 * These tests stress the invariants:
 *  - close() is idempotent (concurrent callers — only the first mutates
 *    state, the rest observe `_active=false` and no-op).
 *  - Concurrent close() invocations do not raise exceptions, double-resume
 *    a pending accept continuation, or leave the server in a partially
 *    cleaned-up state.
 *  - close() from a non-EventLoop thread while accept() is suspended
 *    unwinds cleanly (CancellationException surfaces via accept()) and
 *    does not fire an uncaught exception into Dispatchers.Default.
 */
class NioServerCloseThreadSafetyTest {

    private val testTimeout = 10.seconds

    @Test
    fun `concurrent close from many threads is idempotent and exception-free`() = runBlocking {
        val engine = NioEngine(IoEngineConfig(threads = 1))
        val server = engine.bind("127.0.0.1", 0)
        val parallelism = 16
        val latch = CountDownLatch(parallelism)
        val executor = Executors.newFixedThreadPool(parallelism)
        val thrown = CopyOnWriteArrayList<Throwable>()

        try {
            repeat(parallelism) {
                executor.submit {
                    try {
                        latch.countDown()
                        latch.await()
                        // Hammer close() from a fresh thread; only the
                        // first caller should actually release resources.
                        server.close()
                    } catch (t: Throwable) {
                        thrown.add(t)
                    }
                }
            }
            executor.shutdown()
            assertTrue(
                executor.awaitTermination(5, TimeUnit.SECONDS),
                "concurrent close workers did not finish in 5s",
            )
        } finally {
            runCatching { engine.close() }
        }

        assertTrue(thrown.isEmpty(), "concurrent close raised: $thrown")
        assertFalse(server.isActive, "server should report inactive after close")

        // Second round of close() calls — must stay silent no-ops.
        repeat(4) { server.close() }
    }

    @Test
    fun `close while accept is suspended cancels accept without fatal exceptions`() = runBlocking {
        val engine = NioEngine(IoEngineConfig(threads = 1))
        val server = engine.bind("127.0.0.1", 0)
        val fatals = CopyOnWriteArrayList<Throwable>()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, t -> fatals.add(t) }

        try {
            withTimeout(testTimeout) {
                // Dispatchers.Default is intentional: the race we want
                // to exercise is close() on a thread that is *not* the
                // boss EventLoop. Using a different dispatcher would
                // defeat the regression test.
                @Suppress("InjectDispatcher")
                val acceptJob = async(Dispatchers.Default) {
                    runCatching { server.accept() }
                }
                // Give accept a moment to suspend on the boss EventLoop.
                kotlinx.coroutines.delay(100)

                // Close from a non-EventLoop thread with a handful of
                // concurrent callers to exercise the race.
                @Suppress("InjectDispatcher")
                val closers = (1..8).map {
                    async(Dispatchers.Default) { server.close() }
                }
                closers.awaitAll()

                val result = acceptJob.await()
                assertTrue(result.isFailure, "accept should have surfaced a cancellation")
            }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(prev)
            runCatching { engine.close() }
        }

        val fatalFromCoroutines = fatals.filter {
            it::class.qualifiedName?.contains("CoroutinesInternalError") == true ||
                it is ClassCastException
        }
        assertEquals(
            emptyList(),
            fatalFromCoroutines,
            "close() race triggered fatal coroutine exception(s): $fatalFromCoroutines",
        )
    }
}
