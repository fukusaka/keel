package io.github.fukusaka.keel.server

import io.github.fukusaka.keel.core.IoEngine
import io.github.fukusaka.keel.core.IoEngineConfig
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Exercises the two-phase shutdown contract of [gracefulShutdown]:
 *
 * - happy path: stopRequest completes, serverJob joins, engine children
 *   drain naturally within the grace period, then [IoEngine.close] runs;
 * - force phase: grace times out, [Job.cancel] is invoked on serverJob,
 *   the helper waits up to the remaining budget, [IoEngine.close] still
 *   runs in `finally`;
 * - engine close always runs even if serverJob throws.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GracefulShutdownTest {

    /**
     * Test [IoEngine] whose [coroutineContext] carries a [SupervisorJob] —
     * so per-connection handler coroutines launched on this scope are
     * exposed as `engine.coroutineContext.job.children` to the helper, and
     * records [close] invocations for assertions.
     */
    private class FakeIoEngine(parent: CoroutineContext) : IoEngine {
        override val coroutineContext: CoroutineContext = parent + SupervisorJob()
        override val config: IoEngineConfig = IoEngineConfig()
        var closeCount: Int = 0
            private set

        override suspend fun close() {
            closeCount++
        }
    }

    private fun runShutdownTest(block: suspend TestScope.() -> Unit): TestResult = runTest {
        withTimeout(15.seconds) { block() }
    }

    @Test
    fun `completes stopRequest signal during grace phase`() = runShutdownTest {
        val engine = FakeIoEngine(coroutineContext)
        val stopRequest: CompletableJob = Job()
        val serverJob: Job = Job().also { it.complete() }

        assertFalse(stopRequest.isCompleted)
        gracefulShutdown(serverJob, stopRequest, engine, gracePeriodMillis = 1_000L, timeoutMillis = 2_000L)

        assertTrue(stopRequest.isCompleted, "stopRequest must be completed for the accept coordinator to wake up")
        assertEquals(1, engine.closeCount)
    }

    @Test
    fun `joins serverJob and engine children within the grace period`() = runShutdownTest {
        val engine = FakeIoEngine(coroutineContext)
        val handlerDone = Job()
        // Per-connection handler launched on the engine scope (engine job's child).
        engine.launch {
            try {
                delay(200L)
            } finally {
                handlerDone.complete()
            }
        }
        val serverJob = engine.launch {
            // Server job finishes immediately so the helper proceeds to draining handlers.
            delay(10L)
        }
        val stopRequest: CompletableJob = Job()

        val start = currentTime
        gracefulShutdown(serverJob, stopRequest, engine, gracePeriodMillis = 1_000L, timeoutMillis = 2_000L)
        val elapsed = currentTime - start

        assertTrue(handlerDone.isCompleted, "engine child must have drained naturally")
        assertTrue(serverJob.isCompleted)
        assertTrue(elapsed in 200L..1_000L, "grace phase elapsed=$elapsed should reflect natural drain")
        assertEquals(1, engine.closeCount)
    }

    @Test
    fun `forces cancellation when serverJob does not finish within the grace period`() = runShutdownTest {
        val engine = FakeIoEngine(coroutineContext)
        val serverJob = engine.launch {
            // Never completes naturally — must be cancelled by the helper.
            delay(60_000L)
        }
        val stopRequest: CompletableJob = Job()

        val start = currentTime
        gracefulShutdown(serverJob, stopRequest, engine, gracePeriodMillis = 100L, timeoutMillis = 500L)
        val elapsed = currentTime - start

        assertTrue(serverJob.isCancelled, "serverJob must be cancelled when grace phase times out")
        assertTrue(elapsed >= 100L, "elapsed=$elapsed should be at least gracePeriodMillis")
        assertTrue(elapsed <= 500L, "elapsed=$elapsed must not exceed total timeoutMillis")
        assertEquals(1, engine.closeCount, "engine.close runs in finally even on the force path")
    }

    @Test
    fun `engine close runs in finally even when serverJob already completed`() = runShutdownTest {
        val engine = FakeIoEngine(coroutineContext)
        val serverJob: Job = Job().also { it.complete() }
        val stopRequest: CompletableJob = Job()

        gracefulShutdown(serverJob, stopRequest, engine, gracePeriodMillis = 50L, timeoutMillis = 100L)

        assertEquals(1, engine.closeCount)
    }

    @Test
    fun `engine close runs in finally when force phase budget is exhausted`() = runShutdownTest {
        val engine = FakeIoEngine(coroutineContext)
        val serverJob = engine.launch { delay(60_000L) }
        val stopRequest: CompletableJob = Job()

        // grace=100, total=150 → force budget = 50. serverJob still won't finish
        // naturally, but cancel() propagates through delay so it returns quickly.
        gracefulShutdown(serverJob, stopRequest, engine, gracePeriodMillis = 100L, timeoutMillis = 150L)

        assertEquals(1, engine.closeCount)
        assertTrue(serverJob.isCancelled || serverJob.isCompleted)
    }

    @Test
    fun `engine close still runs when stopRequest was completed externally`() = runShutdownTest {
        val engine = FakeIoEngine(coroutineContext)
        val serverJob: Job = Job().also { it.complete() }
        val stopRequest: CompletableJob = Job().also { it.complete() }

        // Idempotency: completing an already-completed stopRequest must not throw,
        // and engine.close must still run exactly once.
        gracefulShutdown(serverJob, stopRequest, engine, gracePeriodMillis = 100L, timeoutMillis = 200L)

        assertEquals(1, engine.closeCount)
        assertTrue(engine.coroutineContext.job.isActive || engine.coroutineContext.job.isCompleted)
    }
}
