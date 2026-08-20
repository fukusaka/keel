package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.logging.LogLevel
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.native.posix.errnoMessage
import io.github.fukusaka.keel.native.readiness.InternalReadinessEngineApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.EBADF
import platform.posix.EINVAL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * That a loop whose registration lock has failed stops instead of polling on.
 *
 * The lock is what makes the two ledgers exclusive. Once an acquire or a
 * release has failed, the next arm this loop issues could be for a key another
 * thread is mid-way through changing — so the loop ends the way a poll fatal
 * ends it, rather than keeping the connections it holds.
 *
 * Two halves of that wiring exist: the base reports the failure and records it,
 * and this loop reads the record and breaks. The first half is pinned in the
 * module the base lives in. **This pins the second**, which nothing held: with
 * the check deleted, the whole engine suite stayed green.
 *
 * **The syscall failure itself is out of reach**, and always will be from here.
 * The acquire takes a default `pthread_mutex_t` that is never destroyed, so it
 * cannot return non-zero — `EDEADLK` needs an error-checking attribute and
 * `EINVAL` needs the slot invalidated. Neither release is ever issued for a
 * mutex this thread does not hold — one runs only under a successful lock, the
 * other only under a successful `trylock` — which is the case a default mutex
 * defines, and it returns zero. What a test can do is call the reporting entry
 * point the failing syscall would have called, which is why that function is
 * public under the opt-in. So the arrow from "the syscall returned non-zero" to
 * "report it" is a guard, and the arrows from "it was reported" to "this loop
 * stops" and to "this is the reason its waiters are given" are what these
 * cases hold.
 *
 * No timeout, matching the sibling cases that drive `loop()` on the test
 * thread. The cases that report before running it script a fatal `waitEvents`,
 * which ends the loop even when the check under test is gone. The two that
 * stage a stop cannot use that net — a scripted fatal would end the loop for
 * the wrong reason and decide the assertion — and their own ending is what
 * the check under test does not affect, so what they bound is the other
 * hazard: a close that stops taking the running flag down, or a body that
 * stops reading it, would otherwise spin here rather than fail.
 */
@OptIn(ExperimentalForeignApi::class, InternalReadinessEngineApi::class)
class EpollRegLockFailureSeamTest {

    @Test
    fun `a loop whose lock acquire failed does not poll again`() {
        // Scripted fatal as a safety net rather than an expectation: it is what
        // the loop would hit on its *next* wait if the check were gone, so the
        // regression shows up as `waitCalls == 1` instead of as a hang.
        val fake = FakeEpollSyscallOps().apply { scriptWaitFailure(EBADF) }
        val errors = mutableListOf<String>()
        val el = EpollEventLoop(errorRecordingLogger(errors), syscallOps = fake)
        try {
            el.reportRegLockFailure("lock", EINVAL, stillHeld = false)

            el.loop()

            assertEntered(errors)
            assertEquals(
                0,
                fake.waitCalls,
                "the ledgers stopped being exclusive, so the loop must end before arming anything else",
            )
        } finally {
            el.close()
        }
    }

    @Test
    fun `a lock that stopped being exclusive is recorded as why the loop ended`() {
        // The loop stopping is half of it. The other half is what a caller
        // waiting on a flush this loop will never run is told: nobody asked
        // for this, so it must not read as the caller's own doing.
        //
        // The loop is run, not just told: what is being pinned is the reason
        // an ending carries, so there has to be an ending. The scripted fatal
        // is the same safety net the cases above use, and goes unused when the
        // check under test holds.
        val fake = FakeEpollSyscallOps().apply { scriptWaitFailure(EBADF) }
        val el = EpollEventLoop(errorRecordingLogger(mutableListOf()), syscallOps = fake)
        try {
            el.reportRegLockFailure("lock", EINVAL, stillHeld = false)

            el.loop()

            assertEquals(0, fake.waitCalls, "the loop must end without arming anything else")
            val fault = el.loopFailure()
            assertNotNull(fault, "the lock failure is why this loop ended")
            assertTrue(
                checkNotNull(fault.message).contains("pthread_mutex_lock()"),
                "the record must name the call that failed, got: ${fault.message}",
            )
            assertTrue(
                checkNotNull(fault.message).contains(errnoMessage(EINVAL)),
                "and the errno it failed with, got: ${fault.message}",
            )
        } finally {
            el.close()
        }
    }

    @Test
    fun `a lock that fails after the loop has finished is not why it ended`() {
        // The lock outlives the loop deliberately -- cancellations keep
        // arriving and taking it long after the sweep -- so a failure here can
        // land on a loop that stopped because somebody asked. Recording it
        // would tell that loop's late waiters they had suffered a fault.
        val fake = FakeEpollSyscallOps()
        val el = EpollEventLoop(errorRecordingLogger(mutableListOf()), syscallOps = fake)
        var waits = 0
        fake.onWait = {
            check(++waits <= MAX_WAITS) { "the loop did not end when it was asked to" }
            el.close()
        }
        el.loop()

        el.reportRegLockFailure("unlock", EINVAL, stillHeld = false)

        assertNull(el.loopFailure(), "the loop had already ended, and not for this")
        // No close in a `finally`: the one above already took the running flag
        // down, so a second is a no-op. The first was refused the release
        // because it ran from inside the wait, before quiescence -- the
        // sibling cases close after `loop()` returns and do get it, so the
        // claim `loop()` holds is not what decides this. What goes unreleased
        // is real: the arena behind the thread handle and the gather scratch's
        // two native arrays, leaked for the process. Accepted because the
        // alternative staging -- closing from another thread -- is not
        // something this seam has, and the descriptors are synthetic, so the
        // release this misses would hand fabricated numbers to `close(2)`.
    }

    @Test
    fun `a lock that fails while a stop is already under way is not why it ended`() {
        // The window between "asked to stop" and the loop noticing: the flag
        // the body reads is already down, and a lock somebody else was holding
        // fails in the meantime. The stop is what ends this loop, and its
        // waiters asked for that -- so the fault that arrives alongside must
        // not turn their cancellation into a report.
        //
        // A check on "has the loop published that it is finishing" does not
        // cover this: that comes later still. What covers it is where the
        // record is written -- the body's own check, which this ending never
        // reaches, because the condition above it goes false first.
        val fake = FakeEpollSyscallOps()
        val el = EpollEventLoop(errorRecordingLogger(mutableListOf()), syscallOps = fake)
        var waits = 0
        fake.onWait = {
            check(++waits <= MAX_WAITS) { "the loop did not end when it was asked to" }
            el.close()
            el.reportRegLockFailure("unlock", EINVAL, stillHeld = false)
        }

        el.loop()

        assertEquals(1, fake.waitCalls, "the body must have run and ended through its own condition")
        assertNull(el.loopFailure(), "a stop that was asked for is not a fault, whatever failed alongside it")
    }

    @Test
    fun `a loop whose lock release failed does not poll again`() {
        // The other way the lock breaks: the release failed, so this thread
        // still holds it. The loop's decision is the same one — what differs is
        // the teardown's, which is the base's to make and the base's to pin.
        val fake = FakeEpollSyscallOps().apply { scriptWaitFailure(EBADF) }
        val errors = mutableListOf<String>()
        val el = EpollEventLoop(errorRecordingLogger(errors), syscallOps = fake)
        try {
            el.reportRegLockFailure("unlock", EINVAL, stillHeld = true)

            el.loop()

            assertEntered(errors)
            assertEquals(0, fake.waitCalls, "a failed release stops the loop as surely as a failed acquire")
        } finally {
            el.close()
        }
    }

    /**
     * That `loop()` reached [EpollEventLoop.loopBody] at all.
     *
     * The count assertion each case ends with is an *absence*, and an absence
     * is satisfied by any short-circuit — including the one `loop()` takes when
     * the termination claim is already held, which logs and returns without
     * entering the body. That path would leave `waitCalls` at zero while the
     * guard under test was never reached, so the count alone cannot tell
     * "broke at the guard" from "never got there". This is what separates them.
     */
    private fun assertEntered(errors: List<String>) {
        assertTrue(
            errors.none { it.contains(ALREADY_CLAIMED) },
            "the loop returned before its body ran, so nothing here says anything about the guard: $errors",
        )
    }

    /**
     * Captures error-level messages, which is the level `loop()` reports a
     * refused entry at. Modelled on the sibling seam suite's recorder.
     */
    private fun errorRecordingLogger(sink: MutableList<String>): Logger = object : Logger {
        override fun isLoggable(level: LogLevel): Boolean = level == LogLevel.ERROR

        override fun rawLog(level: LogLevel, throwable: Throwable?, message: Any?) {
            if (level == LogLevel.ERROR) sink.add(message.toString())
        }
    }

    private companion object {
        /** Fragment of the message `loop()` logs when the claim is already taken. */
        const val ALREADY_CLAIMED = "found the loop already claimed"

        /**
         * How many waits a case that ends the loop by closing may take before
         * it is a hang. One is what those cases produce; the rest is slack
         * rather than a second path anything takes, because what this bounds
         * is a loop that never ends, not one that waits twice.
         */
        const val MAX_WAITS = 8
    }
}
