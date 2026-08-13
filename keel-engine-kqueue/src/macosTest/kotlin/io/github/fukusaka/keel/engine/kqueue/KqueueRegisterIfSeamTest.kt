package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.core.IpAddress
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import io.github.fukusaka.keel.native.posix.PosixNativeSocketOps
import io.github.fukusaka.keel.native.readiness.Interest
import io.github.fukusaka.keel.native.readiness.InternalReadinessEngineApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import platform.posix.close
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Covers [KqueueEventLoop.registerIf]'s reason for existing: deciding whether a
 * waiter is still wanted, and appending it, must be one step.
 *
 * `StreamServer.close()` resumes every waiter registered for its listener via
 * `cancelAll`. A registration that lands after that cancellation is in nobody's
 * chain and is never resumed — the `accept()` that made it hangs for the life
 * of the process. The predicate runs under the same registration lock
 * `cancelAll` takes, which is what closes that window.
 *
 * This is a seam test because the window is a few instructions wide: driving it
 * through a real `accept()` cannot land in it reliably, and a test that cannot
 * fail is worse than no test.
 */
@OptIn(ExperimentalForeignApi::class, InternalReadinessEngineApi::class)
class KqueueRegisterIfSeamTest {

    private val probeOps = PosixNativeSocketOps(NoopLoggerFactory.logger("probe"))
    private val loopbackIp = IpAddress.parse("127.0.0.1")

    private lateinit var eventLoop: KqueueEventLoop
    private var fd: Int = -1

    @BeforeTest
    fun setUp() {
        eventLoop = KqueueEventLoop(NoopLoggerFactory.logger("KqueueRegisterIfSeamTest"))
        // A *listening* socket, not a bare one: an unconnected AF_INET socket
        // sits in TCP_CLOSE, which Linux reports as EPOLLHUP regardless of the
        // interest mask. That would let the loop resume the waiter before the
        // test's own cancelAll does (flake) and then spin on an event it cannot
        // mask. A listener with no pending connection is simply not ready —
        // and is what StreamServer actually registers.
        fd = probeOps.bindListener(loopbackIp, port = 0, backlog = 1)
    }

    @AfterTest
    fun tearDown() {
        eventLoop.close()
        close(fd)
    }

    @Test
    fun `registerIf appends the waiter when it is still wanted`() = runBlocking {
        withTimeout(TEST_TIMEOUT) {
            eventLoop.start()
            // A wanted registration is appended, and cancelAll finds it: the
            // waiter is resumed with the cancellation rather than stranded.
            val outcome = runCatching {
                suspendCancellableCoroutine<Unit> { cont ->
                    val reg = eventLoop.registerIf(fd, Interest.READ, cont) { true }
                    assertNotNull(reg, "a wanted registration must be appended")
                    eventLoop.cancelAll(
                        fd,
                        Interest.READ,
                        CancellationException(CANCEL_REASON),
                    )
                }
            }.exceptionOrNull()

            // Assert on the message, not just the type: withTimeout throws a
            // CancellationException subclass too, so a type-only check would
            // pass on "the waiter was never appended and we timed out".
            assertTrue(
                outcome is CancellationException && outcome.message == CANCEL_REASON,
                "an appended waiter must be resumed by cancelAll, got: $outcome",
            )
        }
    }

    @Test
    fun `registerIf refuses when the caller has already given up`() = runBlocking {
        withTimeout(TEST_TIMEOUT) {
            eventLoop.start()
            // The predicate is what StreamServer.close() flips: once it reads
            // false, the waiter must not be appended, because cancelAll has
            // already passed and would not see it.
            suspendCancellableCoroutine<Unit> { cont ->
                val reg = eventLoop.registerIf(fd, Interest.READ, cont) { false }
                assertNull(reg, "a registration refused by the predicate must not be appended")
                cont.resume(Unit) { _, _, _ -> }
            }

            // And nothing was left in the chain to resume: a stale entry would
            // be handed a second resumption here and blow up the continuation.
            eventLoop.cancelAll(fd, Interest.READ, CancellationException("nothing to cancel"))
        }
    }

    private companion object {
        val TEST_TIMEOUT = 15.seconds

        /** Distinguishes cancelAll's cancellation from withTimeout's. */
        const val CANCEL_REASON = "closed by the test"
    }
}
