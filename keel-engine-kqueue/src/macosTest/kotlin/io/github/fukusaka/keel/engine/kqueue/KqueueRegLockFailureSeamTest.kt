package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.logging.NoopLoggerFactory
import io.github.fukusaka.keel.native.posix.InternalPosixEventLoopApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.EBADF
import platform.posix.EINVAL
import kotlin.test.Test
import kotlin.test.assertEquals

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
 * The lock is a default `pthread_mutex_t` that is never destroyed, so
 * `pthread_mutex_lock` cannot return non-zero — `EDEADLK` needs an
 * error-checking attribute and `EINVAL` needs the slot invalidated. What a test
 * can do is call the reporting entry point the failing syscall would have
 * called, which is why that function is public under the opt-in. So the arrow
 * from "the syscall returned non-zero" to "report it" is a guard, and the arrow
 * from "it was reported" to "this loop stops" is what these cases hold.
 *
 * No timeout, matching the sibling cases that drive `loop()` on the test
 * thread: the fatal `waitEvents` result scripted below ends the loop even if
 * the check under test is gone, so a regression fails rather than hangs.
 */
@OptIn(ExperimentalForeignApi::class, InternalPosixEventLoopApi::class)
class KqueueRegLockFailureSeamTest {

    private val logger = NoopLoggerFactory.logger("KqueueRegLockFailureSeamTest")

    @Test
    fun `a loop whose lock acquire failed does not poll again`() {
        // Scripted fatal as a safety net rather than an expectation: it is what
        // the loop would hit on its *next* wait if the check were gone, so the
        // regression shows up as `waitCalls == 1` instead of as a hang.
        val fake = FakeKqueueSyscallOps().apply { scriptWaitFailure(EBADF) }
        val el = KqueueEventLoop(logger, syscallOps = fake)
        try {
            el.reportRegLockFailure("lock", EINVAL, stillHeld = false)

            el.loop()

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
        val fake = FakeKqueueSyscallOps().apply { scriptWaitFailure(EBADF) }
        val el = KqueueEventLoop(logger, syscallOps = fake)
        try {
            el.reportRegLockFailure("unlock", EINVAL, stillHeld = true)

            el.loop()

            assertEquals(0, fake.waitCalls, "a failed release stops the loop as surely as a failed acquire")
        } finally {
            el.close()
        }
    }
}
