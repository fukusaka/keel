package io.github.fukusaka.keel.server

import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.core.StreamServer
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.testing.InjectedFault
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Asserts the runtime behaviour of [acceptLoopWithBackoff] on a fake
 * [StreamServer]: success / failure sequencing, backoff progression, reset
 * on success, max-cap clamp, cancellation, and `isActive=false` termination.
 *
 * Uses `runTest`'s virtual time scheduler so the loop's `delay(...)` calls
 * are observable and deterministic without real wall-clock waits. Each test
 * is wrapped in `withTimeout(5.seconds)` per the project rule that any test
 * exercising dispatch / suspend completion must carry a wall-clock upper
 * bound, even on a virtual-time scheduler. 5 s comes from the testing.md
 * "loopback dispatch / event loop hop: 1–5 s" envelope — these tests are
 * pure seam-level with no real I/O so they complete in ≪ 100 ms; the bound
 * exists only to fail closed if a future refactor introduces a real-time
 * suspension that the virtual scheduler can't advance through.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AcceptLoopTest {

    private val noopLogger = NoopLoggerFactory.logger("accept-loop-test")

    /** A non-functional [PipelinedChannel] sufficient for identity tracking. */
    private fun fakeChannel(): PipelinedChannel =
        object : AbstractPipelinedChannel(TestIoTransport(), noopLogger) {}

    /**
     * A fake [StreamServer] driven by a scripted list of outcomes — each
     * outcome is either a channel to return, or an exception to throw. The
     * loop's actual `delay` calls are routed through the test scheduler
     * (`runTest`) so timing is virtual but accurate.
     */
    private class ScriptedServer(
        private val outcomes: ArrayDeque<Result<PipelinedChannel>>,
        active: Boolean = true,
    ) : StreamServer {
        private var activeFlag: Boolean = active
        var attempt: Int = 0
            private set

        override val localAddress: SocketAddress get() = error("not used")
        override val isActive: Boolean get() = activeFlag
        override fun close() { activeFlag = false }

        override suspend fun accept(): PipelinedChannel {
            attempt++
            if (outcomes.isEmpty()) {
                // Stop the loop deterministically when the script is empty.
                activeFlag = false
                throw IllegalStateException("script exhausted")
            }
            val next = outcomes.removeFirst()
            return next.getOrThrow()
        }
    }

    private fun runLoopTest(block: suspend TestScope.() -> Unit): TestResult = runTest {
        withTimeout(5.seconds) { block() }
    }

    @Test
    fun `delivers every accepted channel via onAccept`() = runLoopTest {
        val ch1 = fakeChannel()
        val ch2 = fakeChannel()
        val server = ScriptedServer(
            ArrayDeque(listOf(Result.success(ch1), Result.success(ch2))),
        )
        val delivered = mutableListOf<PipelinedChannel>()

        server.acceptLoopWithBackoff(logger = noopLogger) { delivered += it as PipelinedChannel }

        assertEquals(listOf(ch1, ch2), delivered)
    }

    @Test
    fun `Fixed backoff sleeps the constant delay after every failure`() = runLoopTest {
        val ch = fakeChannel()
        val outcomes = ArrayDeque(
            listOf<Result<PipelinedChannel>>(
                Result.failure(InjectedFault("boom-1")),
                Result.failure(InjectedFault("boom-2")),
                Result.success(ch),
            ),
        )
        val server = ScriptedServer(outcomes)

        val start = currentTime
        server.acceptLoopWithBackoff(
            backoff = AcceptBackoff.Fixed(delayMs = 200L),
            logger = noopLogger,
        ) { /* ignore */ }
        val elapsed = currentTime - start

        // Two failures → 2 × 200ms virtual delay.
        assertEquals(400L, elapsed)
    }

    @Test
    fun `Exponential backoff doubles delay across consecutive failures`() = runLoopTest {
        val ch = fakeChannel()
        val outcomes = ArrayDeque(
            listOf<Result<PipelinedChannel>>(
                Result.failure(InjectedFault("boom-1")),
                Result.failure(InjectedFault("boom-2")),
                Result.failure(InjectedFault("boom-3")),
                Result.success(ch),
            ),
        )
        val server = ScriptedServer(outcomes)

        val start = currentTime
        server.acceptLoopWithBackoff(
            backoff = AcceptBackoff.Exponential(initialMs = 100L, maxMs = 10_000L),
            logger = noopLogger,
        ) { /* ignore */ }
        val elapsed = currentTime - start

        // Failures: 100ms → 200ms → 400ms = 700ms cumulative virtual delay.
        assertEquals(700L, elapsed)
    }

    @Test
    fun `Exponential backoff clamps to maxMs once it would exceed the cap`() = runLoopTest {
        val ch = fakeChannel()
        val outcomes = ArrayDeque(
            listOf<Result<PipelinedChannel>>(
                Result.failure(InjectedFault("f1")),
                Result.failure(InjectedFault("f2")),
                Result.failure(InjectedFault("f3")),
                Result.failure(InjectedFault("f4")),
                Result.success(ch),
            ),
        )
        val server = ScriptedServer(outcomes)

        val start = currentTime
        server.acceptLoopWithBackoff(
            backoff = AcceptBackoff.Exponential(initialMs = 100L, maxMs = 300L),
            logger = noopLogger,
        ) { /* ignore */ }
        val elapsed = currentTime - start

        // 100 → 200 → 300 (would be 400, clamped) → 300. Cumulative = 900.
        assertEquals(900L, elapsed)
    }

    @Test
    fun `Exponential backoff resets to initialMs after a successful accept`() = runLoopTest {
        val outcomes = ArrayDeque(
            listOf<Result<PipelinedChannel>>(
                Result.failure(InjectedFault("f1")),
                Result.failure(InjectedFault("f2")),
                Result.success(fakeChannel()),
                Result.failure(InjectedFault("f3")),
                Result.success(fakeChannel()),
            ),
        )
        val server = ScriptedServer(outcomes)

        val start = currentTime
        server.acceptLoopWithBackoff(
            backoff = AcceptBackoff.Exponential(initialMs = 100L, maxMs = 10_000L),
            logger = noopLogger,
        ) { /* ignore */ }
        val elapsed = currentTime - start

        // Failures contribute virtual delay: 100ms, 200ms, then reset → 100ms.
        // Total = 100 + 200 + 100 = 400ms.
        assertEquals(400L, elapsed)
    }

    @Test
    fun `loop terminates immediately when server is already inactive`() = runLoopTest {
        val server = ScriptedServer(ArrayDeque(), active = false)
        var calls = 0

        server.acceptLoopWithBackoff(logger = noopLogger) { calls++ }

        assertEquals(0, calls)
        assertEquals(0, server.attempt)
    }

    @Test
    fun `loop exits without retry or delay when accept fails and server is closed`() = runLoopTest {
        // Custom server whose accept() throws AND closes itself in the same call,
        // exercising the `catch { if (!isActive) break }` early-exit path.
        val server = object : StreamServer {
            var activeFlag = true
            var attempt = 0
            override val localAddress: SocketAddress get() = error("not used")
            override val isActive: Boolean get() = activeFlag
            override fun close() { activeFlag = false }
            override suspend fun accept(): PipelinedChannel {
                attempt++
                activeFlag = false
                throw InjectedFault("server shutting down")
            }
        }

        val start = currentTime
        server.acceptLoopWithBackoff(logger = noopLogger) { /* ignore */ }
        val elapsed = currentTime - start

        assertEquals(1, server.attempt)
        assertEquals(0L, elapsed, "shutdown-during-accept should bypass backoff delay")
    }

    @Test
    fun `CancellationException from accept is rethrown without backoff`() = runLoopTest {
        val server = ScriptedServer(
            ArrayDeque(listOf<Result<PipelinedChannel>>(Result.failure(CancellationException("cancel")))),
        )

        val start = currentTime
        val deferred = async {
            runCatching {
                server.acceptLoopWithBackoff(logger = noopLogger) { /* ignore */ }
            }
        }
        val result = deferred.await()
        val elapsed = currentTime - start

        assertTrue(result.isFailure, "expected CancellationException to propagate")
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals(0L, elapsed, "no backoff delay on CancellationException")
    }

    @Test
    fun `onAccept lambda invoked exactly once per accepted channel`() = runLoopTest {
        // Single-accept boundary case — ensures the loop neither double-delivers
        // nor swallows the first channel before exiting.
        val ch = fakeChannel()
        val server = ScriptedServer(ArrayDeque(listOf(Result.success(ch))))
        var calls = 0
        var captured: PipelinedChannel? = null

        server.acceptLoopWithBackoff(logger = noopLogger) {
            calls++
            captured = it as PipelinedChannel
        }

        assertEquals(1, calls)
        assertEquals(ch, captured)
    }
}
