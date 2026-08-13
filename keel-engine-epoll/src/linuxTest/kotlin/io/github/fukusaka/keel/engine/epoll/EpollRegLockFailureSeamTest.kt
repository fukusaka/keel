package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.logging.LogLevel
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.native.readiness.InternalReadinessEngineApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.EBADF
import platform.posix.EINVAL
import kotlin.test.Test
import kotlin.test.assertEquals
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
 * "report it" is a guard, and the arrow from "it was reported" to "this loop
 * stops" is what these cases hold.
 *
 * No timeout, matching the sibling cases that drive `loop()` on the test
 * thread: the fatal `waitEvents` result scripted below ends the loop even if
 * the check under test is gone, so a regression fails rather than hangs.
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
    }
}
