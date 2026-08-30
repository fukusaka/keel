@file:OptIn(InternalReadinessEngineApi::class)

package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.logging.NoopLoggerFactory
import io.github.fukusaka.keel.native.readiness.FdReadyListener
import io.github.fukusaka.keel.native.readiness.Interest
import io.github.fukusaka.keel.native.readiness.InternalReadinessEngineApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.EEXIST
import platform.posix.usleep
import kotlin.concurrent.AtomicInt
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * What the narrowed arm costs the kernel.
 *
 * A connection under back-pressure re-issues this arm on every wake it
 * declines, and the mask it asks for is the one the previous decline already
 * set — so whether the unchanged call is skipped is the difference between
 * nothing and two syscalls a turn on a connection being streamed at.
 *
 * Its own file rather than a case among the loop's other seam tests: that
 * class is already at detekt's size limit, and this is a separate question
 * from the ones it asks.
 */
@OptIn(ExperimentalForeignApi::class)
class EpollCloseOnlyArmSeamTest {

    private val logger = NoopLoggerFactory.logger("EpollCloseOnlyArmSeamTest")

    /** A listener that asks for the narrowed arm — the back-pressure shape. */
    private object CloseOnlyListener : FdReadyListener {
        override val armsCloseOnly: Boolean get() = true
        override fun onReady(interest: Interest) { /* no-op */ }
    }

    /** The same, as a second identity, so a re-arm is not the withdrawal of the first. */
    private object SecondCloseOnlyListener : FdReadyListener {
        override val armsCloseOnly: Boolean get() = true
        override fun onReady(interest: Interest) { /* no-op */ }
    }

    @Test
    fun `a repeated close-only arm asks the kernel once`() {
        val fake = FakeEpollSyscallOps().apply {
            scriptEpollCreateFd(fd = 1000)
            scriptEventfdCreateFd(fd = 1001)
            scriptAddResult(0) // init ADD for the wakeup fd
            scriptAddResult(EEXIST) // the first narrowing ADD
            scriptModResult(0) // and its MOD
        }
        fake.liveMode = true
        fake.watchedFd = WATCHED_FD
        val el = EpollEventLoop(logger, syscallOps = fake)
        try {
            el.registerCallback(fd = WATCHED_FD, interest = Interest.READ, listener = CloseOnlyListener)
            el.start()
            awaitCtlCalls(fake, expected = 3)
            assertEquals(
                FakeEpollSyscallOps.CtlOp.MOD,
                fake.ctlCalls[2].op,
                "the first narrowing sets the mask: ${fake.ctlCalls}",
            )

            // A different identity, so this is a fresh registration rather than
            // the withdrawal of the one above -- and the mask it asks for is
            // the one already in place.
            el.registerCallback(fd = WATCHED_FD, interest = Interest.READ, listener = SecondCloseOnlyListener)

            // The arm above is queued to the loop, so counting syscalls right
            // away would count them before it ran and pass on any behaviour at
            // all. This marker is queued behind it and the loop runs its tasks
            // in order, so its arrival means the arm is done.
            val armRan = AtomicInt(0)
            el.dispatch(EmptyCoroutineContext) { armRan.value = 1 }
            val deadline = TimeSource.Monotonic.markNow() + DRAIN_BUDGET
            while (armRan.value == 0) {
                check(deadline.hasNotPassedNow()) { "the loop never ran the marker queued behind the arm" }
                usleep(POLL_US)
            }

            assertEquals(
                3,
                fake.ctlCalls.size,
                "the second narrowing asks for a mask the fd already has, so it issues nothing: " +
                    "${fake.ctlCalls}",
            )
        } finally {
            el.close()
        }
    }

    /** Waits until the loop has recorded [expected] `epoll_ctl` calls, bounded by wall clock. */
    private fun awaitCtlCalls(fake: FakeEpollSyscallOps, expected: Int) {
        val deadline = TimeSource.Monotonic.markNow() + DRAIN_BUDGET
        while (fake.ctlCallCount < expected) {
            check(deadline.hasNotPassedNow()) {
                "the EventLoop recorded ${fake.ctlCallCount} of $expected epoll_ctl calls within $DRAIN_BUDGET"
            }
            usleep(POLL_US)
        }
    }

    private companion object {
        /** An fd number the fake answers for; nothing real is opened. */
        const val WATCHED_FD = 2000

        const val POLL_US = 2_000u
        val DRAIN_BUDGET = 15.seconds
    }
}
