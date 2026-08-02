package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.engine.kqueue.FakeKqueueSyscallOps.Companion.currentThreadId
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import io.github.fukusaka.keel.native.posix.FdReadyListener
import io.github.fukusaka.keel.native.posix.Interest
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Seam-level test for the **I/O ownership invariant funnel**: every I/O
 * syscall runs on the EventLoop thread that owns the fd.
 *
 * `KqueueEventLoop.register` / `registerCallback` route the actual
 * `kevent(EV_ADD)` submission through:
 *
 * ```
 * if (inEventLoop()) submitInline()
 * else dispatch { submitOnEventLoopThread() }
 * ```
 *
 * The prior seam tests (`KqueueEventLoopSeamTest`) drive `loop()` on the
 * test thread and only pin the *inline* branch implicitly. This file
 * pins the **cross-thread funnel branch directly**: a `registerCallback`
 * issued from a non-EventLoop thread must execute its `addFilter` syscall
 * on the EventLoop thread, not on the caller's.
 *
 * **Determinism.** Instead of probabilistic stress, this drives the real
 * `loop()` on a `start()`-spawned EventLoop pthread with
 * [FakeKqueueSyscallOps] in `liveMode` (the fake's `waitEvents`
 * poll-sleeps so the loop drains dispatched tasks each iteration). The
 * fake captures the thread id (raw `pthread_self()` pointer as a Long) on
 * which `addFilter` runs ([FakeKqueueSyscallOps.lastAddFilterThreadId]);
 * the test compares it with the caller thread id. A dispatched barrier
 * task gates the cross-thread call, so the loop is demonstrably running by
 * the time it happens — what this pins is the funnel under contention, not
 * the pre-start case (the first test in this file covers that one).
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)
class KqueueEventLoopFunnelSeamTest {

    private val logger = NoopLoggerFactory.logger("KqueueEventLoopFunnelSeamTest")

    private val noopListener = object : FdReadyListener {
        override fun onReady(interest: Interest) { /* no-op */ }
    }

    /**
     * A registration issued before the loop starts must be queued, not run on
     * the caller's thread.
     *
     * The funnel used to carry an escape hatch — `eventLoopThread == null ||
     * inEventLoop()` — that sent pre-start registrations down the inline path,
     * on the reasoning that nothing but single-threaded construction could be
     * there. `accept()` disproved it: it builds a transport, and registers its
     * fd, on whatever thread the caller is on. Between `pthread_create`
     * returning and `loop()` assigning the handle, that read saw null, took the
     * inline branch, and then `assertInEventLoop` re-read a handle the loop had
     * meanwhile assigned — two reads, one decision each, disagreeing. It
     * surfaced as roughly one failed run in twenty-five of the full suite; with
     * the assert absent it would instead have been a `kevent` issued off-loop,
     * silently.
     *
     * So this pins the property the escape hatch broke: off-loop registration
     * funnels, whether or not the loop has started yet.
     */
    @Test
    fun `a registration made before the loop starts is queued rather than run on the caller`() = runBlocking {
        withTimeout(FUNNEL_BUDGET) {
            val fake = FakeKqueueSyscallOps().apply {
                liveMode = true
                watchedFd = FD_UNDER_TEST
            }
            val el = KqueueEventLoop(logger, syscallOps = fake)
            try {
                val beforeCalls = fake.addFilterCalls.size

                // Registering before start(): the loop's thread handle is unset,
                // which is exactly the window the escape hatch used to treat as
                // "safe to run inline".
                el.registerCallback(FD_UNDER_TEST, Interest.READ, noopListener)
                assertEquals(
                    beforeCalls,
                    fake.addFilterCalls.size,
                    "pre-start registration must not reach kevent on the caller thread — it belongs on the loop",
                )

                // Starting the loop drains what was queued, on the loop's own
                // thread, which is where the syscall belonged all along.
                el.start()
                // Gated on lastAddFilterThreadId, the fake's only @Volatile
                // member: addFilterCalls is a plain list the loop appends to,
                // so polling it from here would be an unsynchronised read.
                while (fake.lastAddFilterThreadId == 0L) delay(POLL_MS)
                assertNotEquals(
                    currentThreadId(),
                    fake.lastAddFilterThreadId,
                    "the queued registration must fire on the EventLoop thread, not the caller's",
                )
            } finally {
                el.close()
            }
        }
    }

    /**
     * A `registerCallback` issued from a non-EventLoop thread must funnel
     * its `addFilter` syscall onto the EventLoop thread.
     */
    @Test
    fun `cross-thread registerCallback funnels addFilter to the EventLoop thread`() = runBlocking {
        withTimeout(FUNNEL_BUDGET) {
            val fake = FakeKqueueSyscallOps().apply {
                liveMode = true
                watchedFd = FD_UNDER_TEST
            }
            val el = KqueueEventLoop(logger, syscallOps = fake)
            val callerThreadId = currentThreadId()
            try {
                el.start()
                awaitEventLoopUp(el)

                // Cross-thread: this runs on the test (caller) thread, NOT
                // the EventLoop thread.
                el.registerCallback(FD_UNDER_TEST, Interest.READ, noopListener)

                val execThreadId = awaitAddFilterThreadId(fake)
                assertNotEquals(
                    callerThreadId,
                    execThreadId,
                    "addFilter must run on the EventLoop thread, not the cross-thread caller — " +
                        "funnel (dispatch) branch was not taken",
                )
            } finally {
                el.close()
            }
        }
    }

    /**
     * Control: a `registerCallback` issued from *within* the EventLoop
     * thread (via a dispatched task) must run `addFilter` inline on that
     * same thread — the fast path, no extra dispatch hop.
     */
    @Test
    fun `in-EventLoop registerCallback runs addFilter inline on the EventLoop thread`() = runBlocking {
        withTimeout(FUNNEL_BUDGET) {
            val fake = FakeKqueueSyscallOps().apply {
                liveMode = true
                watchedFd = FD_UNDER_TEST
            }
            val el = KqueueEventLoop(logger, syscallOps = fake)
            val taskThreadId = AtomicLong(0L)
            try {
                el.start()
                awaitEventLoopUp(el)

                // Run registerCallback on the EventLoop thread itself.
                el.dispatch(EmptyCoroutineContext) {
                    taskThreadId.store(currentThreadId())
                    el.registerCallback(FD_UNDER_TEST, Interest.READ, noopListener)
                }

                val execThreadId = awaitAddFilterThreadId(fake)
                val taskTid = awaitNonZero { taskThreadId.load() }
                assertEquals(
                    taskTid,
                    execThreadId,
                    "addFilter must run inline on the EventLoop thread when registerCallback " +
                        "is invoked from within the EventLoop",
                )
            } finally {
                el.close()
            }
        }
    }

    // --- helpers ---

    /**
     * Blocks until the spawned EventLoop thread has assigned
     * `eventLoopThread` (i.e. `loop()` has run at least one iteration).
     * Detected indirectly: a dispatched barrier task only runs once
     * `drainTasks()` executes, which is after `eventLoopThread =
     * pthread_self()` at the top of `loop()`.
     */
    private suspend fun awaitEventLoopUp(el: KqueueEventLoop) {
        val barrier = AtomicLong(0L)
        el.dispatch(EmptyCoroutineContext) { barrier.store(1L) }
        while (barrier.load() == 0L) delay(POLL_MS)
    }

    private suspend fun awaitAddFilterThreadId(fake: FakeKqueueSyscallOps): Long {
        while (fake.lastAddFilterThreadId == 0L) delay(POLL_MS)
        return fake.lastAddFilterThreadId
    }

    private suspend fun awaitNonZero(read: () -> Long): Long {
        var v = read()
        while (v == 0L) {
            delay(POLL_MS)
            v = read()
        }
        return v
    }

    private companion object {
        val FUNNEL_BUDGET = 15.seconds
        const val POLL_MS = 2L
        const val FD_UNDER_TEST = 5000
    }
}
