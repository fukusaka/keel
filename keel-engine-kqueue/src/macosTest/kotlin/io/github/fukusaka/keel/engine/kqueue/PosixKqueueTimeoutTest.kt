package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.logging.NoopLoggerFactory
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.close
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Regression for the `kevent` wait-timeout units bug in [PosixKqueueSyscallOps.waitEvents].
 *
 * The timeout argument is milliseconds (the unit produced by the
 * [io.github.fukusaka.keel.pipeline.DeadlineScheduler] and `KqueueEventLoop.computeWaitTimeout`,
 * matching the epoll engine). The earlier implementation split the value with `/ 1_000_000_000`
 * + `% 1_000_000_000`, i.e. it treated the millisecond value as nanoseconds — so a 100 ms wait
 * elapsed in ~100 ns and returned immediately. With a connection deadline (idle / read / write
 * timeout) scheduled, the EventLoop then busy-polled `kevent` until the real deadline passed.
 *
 * With no fds registered, `waitEvents` must block for ~the requested duration and return 0.
 * Red-Green: pre-fix this returns in well under a millisecond; post-fix it blocks ~[WAIT_MILLIS].
 */
@OptIn(ExperimentalForeignApi::class)
class PosixKqueueTimeoutTest {

    private val syscallOps = PosixKqueueSyscallOps(NoopLoggerFactory.logger("PosixKqueueTimeoutTest"))

    @Test
    fun `waitEvents blocks for the requested millisecond timeout`() {
        val kqFd = syscallOps.kqueueCreate()
        check(kqFd >= 0) { "kqueueCreate() failed: $kqFd" }
        try {
            val events = Array(EVENT_CAP) { KqEvent() }
            val start = TimeSource.Monotonic.markNow()
            val n = syscallOps.waitEvents(kqFd, events, WAIT_MILLIS)
            val elapsed = start.elapsedNow()
            // No fds are registered, so the wait times out with no events.
            assertEquals(0, n, "expected 0 events on a no-fd timeout, got $n")
            // The wait must actually elapse ~WAIT_MILLIS. A generous lower bound avoids
            // flakiness from scheduler jitter while still failing decisively on the
            // 1e6x-too-short (ns-misread) regression, which returned near-instantly.
            assertTrue(
                elapsed >= LOWER_BOUND,
                "waitEvents($WAIT_MILLIS ms) returned after $elapsed — timeout treated as too short (units bug)",
            )
        } finally {
            close(kqFd)
        }
    }

    private companion object {
        const val EVENT_CAP = 8
        const val WAIT_MILLIS = 200L
        val LOWER_BOUND = 100.milliseconds
    }
}
