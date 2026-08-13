package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.logging.NoopLoggerFactory
import io.github.fukusaka.keel.native.readiness.FdReadyListener
import io.github.fukusaka.keel.native.readiness.Interest
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.pthread_equal
import platform.posix.pthread_self
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Seam-level test for the **I/O ownership invariant funnel**: every I/O
 * syscall runs on the EventLoop thread that owns the fd.
 *
 * `EpollEventLoop.register` / `registerCallback` route the actual
 * `epoll_ctl(EPOLL_CTL_ADD/MOD)` submission through:
 *
 * ```
 * if (inEventLoop()) submitArmCallback()
 * else dispatch { submitArmCallback() }
 * ```
 *
 * The prior seam tests (`EpollEventLoopSeamTest`) drive `loop()` on the
 * test thread and only pin the *inline* branch implicitly. This file
 * pins the **cross-thread funnel branch directly**: a `registerCallback`
 * issued from a non-EventLoop thread must execute its `epoll_ctl` syscall
 * on the EventLoop thread, not on the caller's. Mirrors
 * `KqueueEventLoopFunnelSeamTest`.
 *
 * **Determinism.** Drives the real `loop()` on a `start()`-spawned
 * EventLoop pthread with [FakeEpollSyscallOps] in `liveMode` (the fake's
 * `waitEvents` poll-sleeps so the loop drains dispatched tasks each
 * iteration). The fake captures the pthread on which `epollAdd` /
 * `epollMod` runs ([FakeEpollSyscallOps.lastAddInterestThread]) for a
 * watched fd only (so the construction-time wakeup-eventfd `EPOLL_CTL_ADD`
 * does not pollute the capture); the test compares it via `pthread_equal`
 * — the same idiom `EpollEventLoop.inEventLoop` uses. A dispatched barrier
 * task gates the cross-thread call, so the loop is demonstrably running by
 * the time it happens — what this pins is the funnel under contention, not
 * the pre-start case (the first test in this file covers that one).
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)
class EpollEventLoopFunnelSeamTest {

    private val logger = NoopLoggerFactory.logger("EpollEventLoopFunnelSeamTest")

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
     * there. `accept()` disproves it: it builds a transport, and registers its
     * fd, on whatever thread the caller is on. Between `pthread_create`
     * returning and `loop()` assigning the handle, that read sees null, takes
     * the inline branch, and then `assertInEventLoop` re-reads a handle the
     * loop has meanwhile assigned — two reads, one decision each, disagreeing.
     * It surfaced on the kqueue engine as roughly one failed run in
     * twenty-five of its suite; this engine carried the identical shape and had
     * simply not been caught yet.
     *
     * So this pins the property the escape hatch broke: off-loop registration
     * funnels, whether or not the loop has started yet.
     */
    @Test
    fun `a registration made before the loop starts is queued rather than run on the caller`() = runBlocking {
        withTimeout(FUNNEL_BUDGET) {
            val fake = FakeEpollSyscallOps().apply {
                liveMode = true
                watchedFd = FD_UNDER_TEST
            }
            val el = EpollEventLoop(logger, syscallOps = fake)
            val callerThread = pthread_self()
            try {
                // Registering before start(): the loop's thread handle is unset,
                // which is exactly the window the escape hatch used to treat as
                // "safe to run inline".
                el.registerCallback(FD_UNDER_TEST, Interest.READ, noopListener)
                assertNull(
                    fake.lastAddInterestThread,
                    "pre-start registration must not reach epoll_ctl on the caller thread — it belongs on the loop",
                )

                // Starting the loop drains what was queued, on the loop's own
                // thread, which is where the syscall belonged all along.
                el.start()
                while (fake.lastAddInterestThread == null) delay(POLL_MS)
                val execThread = assertNotNull(fake.lastAddInterestThread)
                assertEquals(
                    0,
                    pthread_equal(execThread, callerThread),
                    "the queued registration must fire on the EventLoop thread, not the caller's",
                )
            } finally {
                el.close()
            }
        }
    }

    /**
     * A `registerCallback` issued from a non-EventLoop thread must funnel
     * its `epoll_ctl` syscall onto the EventLoop thread.
     */
    @Test
    fun `cross-thread registerCallback funnels epoll_ctl to the EventLoop thread`() = runBlocking {
        withTimeout(FUNNEL_BUDGET) {
            val fake = FakeEpollSyscallOps().apply {
                liveMode = true
                watchedFd = FD_UNDER_TEST
            }
            val el = EpollEventLoop(logger, syscallOps = fake)
            val callerThread = pthread_self()
            try {
                el.start()
                awaitEventLoopUp(el)

                // Cross-thread: this runs on the test (caller) thread, NOT
                // the EventLoop thread.
                el.registerCallback(FD_UNDER_TEST, Interest.READ, noopListener)

                // The captured pthread is written on the EventLoop thread
                // and read here (test thread) — the same cross-thread
                // pthread_t read EpollEventLoop.inEventLoop performs.
                while (fake.lastAddInterestThread == null) delay(POLL_MS)
                val execThread = assertNotNull(fake.lastAddInterestThread)
                assertTrue(
                    pthread_equal(execThread, callerThread) == 0,
                    "epoll_ctl must run on the EventLoop thread, not the cross-thread caller — " +
                        "funnel (dispatch) branch was not taken",
                )
            } finally {
                el.close()
            }
        }
    }

    /**
     * Control: a `registerCallback` issued from *within* the EventLoop
     * thread (via a dispatched task) must run `epoll_ctl` inline on that
     * same thread — the fast path, no extra dispatch hop. The thread
     * comparison is computed inside the dispatched task (both pthreads
     * obtained on the EventLoop thread) so only a boolean crosses threads.
     */
    @Test
    fun `in-EventLoop registerCallback runs epoll_ctl inline on the EventLoop thread`() = runBlocking {
        withTimeout(FUNNEL_BUDGET) {
            val fake = FakeEpollSyscallOps().apply {
                liveMode = true
                watchedFd = FD_UNDER_TEST
            }
            val el = EpollEventLoop(logger, syscallOps = fake)
            val inlineMatch = AtomicInt(-1)
            try {
                el.start()
                awaitEventLoopUp(el)

                el.dispatch(EmptyCoroutineContext) {
                    el.registerCallback(FD_UNDER_TEST, Interest.READ, noopListener)
                    val captured = fake.lastAddInterestThread
                    val match = captured != null && pthread_equal(pthread_self(), captured) != 0
                    inlineMatch.store(if (match) 1 else 0)
                }

                while (inlineMatch.load() == -1) delay(POLL_MS)
                assertEquals(
                    1,
                    inlineMatch.load(),
                    "epoll_ctl must run inline on the EventLoop thread when registerCallback " +
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
    private suspend fun awaitEventLoopUp(el: EpollEventLoop) {
        val barrier = AtomicLong(0L)
        el.dispatch(EmptyCoroutineContext) { barrier.store(1L) }
        while (barrier.load() == 0L) delay(POLL_MS)
    }

    private companion object {
        val FUNNEL_BUDGET = 15.seconds
        const val POLL_MS = 2L
        const val FD_UNDER_TEST = 5000
    }
}
