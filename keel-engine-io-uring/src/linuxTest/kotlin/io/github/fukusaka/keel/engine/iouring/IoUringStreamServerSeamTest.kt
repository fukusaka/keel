package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import platform.posix.EBADF
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Seam-level unit tests for [IoUringStreamServer]'s multishot accept
 * error-path state machine — the listener-close / `-EBADF` recovery
 * that integration tests cannot reliably exercise (a loopback listener
 * is rarely externally revoked, and a coordinated close()-then-accept
 * race is hard to time).
 *
 * The audit that surfaced this gap (#A-audit-3) noted that the
 * `onCqe` callback in [IoUringStreamServer.armMultishotAccept] did not
 * branch on `res < 0`: an `-EBADF` CQE on a closed listener would slip
 * through to the rearm check, which re-submitted the same multishot
 * accept on the now-broken fd, looping on `-EBADF` indefinitely and
 * starving the EventLoop. Pending [acceptMultishot] waiters would
 * never resume.
 *
 * The fix (this PR) adds an explicit `res < 0` branch that
 *
 * 1. clears [multishotSlot] (the SQE is already gone — kernel set
 *    `hasMore = 0` on every error CQE),
 * 2. flips `_active` to `false` so subsequent [accept] calls fail fast,
 * 3. fails every queued [acceptMultishot] waiter with the errno, and
 * 4. does NOT rearm.
 *
 * The four tests below pin all four invariants. Red-Green verified:
 * with the original split-gate `onCqe` body, the "fails the pending
 * waiter" test hangs (the waiter is never resumed) and the
 * "no rearm" test observes a second `getSqe` call on the broken fd.
 *
 * Like the other seam tests in this module, the dispatched arm-block
 * runs on the test thread via [IoUringEventLoop.runIteration] —
 * `assertInEventLoop` no-ops pre-`start`.
 */
@OptIn(ExperimentalForeignApi::class)
class IoUringStreamServerSeamTest {

    private val logger = NoopLoggerFactory.logger("IoUringStreamServerSeamTest")

    /**
     * Builds an [IoUringStreamServer] backed by a fake boss
     * [IoUringEventLoop] (so all SQE / CQE traffic is scriptable) and a
     * minimal real worker [IoUringEventLoopGroup] (one EL — required by
     * the constructor signature, never actually touched because the
     * tests focus on the pre-accept error path). The worker group's
     * eventfd is freed at teardown so no fd leaks.
     */
    private suspend fun withServer(
        fake: FakeIoUringRing = FakeIoUringRing(),
        serverFd: Int = 999,
        block: suspend (FakeIoUringRing, IoUringEventLoop, IoUringStreamServer) -> Unit,
    ) {
        val bossLoop = IoUringEventLoop(logger, syscallOps = FakeIoUringSyscallOps(), ioUringRing = fake)
        val workerGroup = IoUringEventLoopGroup(
            size = 1,
            logger = logger,
            allocator = DefaultAllocator,
            capabilities = IoUringCapabilities(),
        )
        val server = IoUringStreamServer(
            serverFd = serverFd,
            bossLoop = bossLoop,
            workerGroup = workerGroup,
            localAddress = InetSocketAddress("127.0.0.1", 0),
            bindConfig = BindConfig(),
            capabilities = IoUringCapabilities(multishotAccept = true),
            logger = logger,
        )
        try {
            block(fake, bossLoop, server)
        } finally {
            workerGroup.close()
            bossLoop.close()
            fake.dispose()
        }
    }

    @Test
    fun `arm fires IORING_OP_ACCEPT and stores the multishot slot`() = runBlocking {
        withServer { fake, bossLoop, server ->
            val pending = async(start = CoroutineStart.UNDISPATCHED) { server.acceptMultishot() }
            // Drain the arm-block dispatched by acceptMultishot.
            assertTrue(bossLoop.runIteration(Cqe()))
            assertEquals(IORING_OP_ACCEPT, fake.lastSqeOp(), "arm prep wrote IORING_OP_ACCEPT")
            assertTrue(server.isActive, "no error CQE has been delivered yet")

            // Tear down by closing — pending waiter resumes with cancellation
            // via the close()-side cleanup path.
            server.close()
            assertTrue(bossLoop.runIteration(Cqe()))
            assertFailsWith<CancellationException> { pending.await() }
        }
    }

    @Test
    fun `EBADF on idle server marks it inactive without rearming`() = runBlocking {
        withServer { fake, bossLoop, server ->
            // Arm the multishot accept but leave no pending waiter — the
            // acceptMultishot caller is cancelled before the error CQE arrives.
            val pending = async(start = CoroutineStart.UNDISPATCHED) { server.acceptMultishot() }
            assertTrue(bossLoop.runIteration(Cqe()))
            val acceptUserData = fake.lastSqeUserData()
            val getSqeAfterArm = fake.getSqeCalls

            pending.cancel()
            assertTrue(bossLoop.runIteration(Cqe()))
            assertFailsWith<CancellationException> { pending.await() }

            // Now deliver an -EBADF CQE on the same slot — the listener fd
            // is gone. The fix must NOT rearm.
            fake.enqueueCqe(userData = acceptUserData, res = -EBADF, flags = 0u, hasMore = false)
            assertTrue(bossLoop.runIteration(Cqe()))

            assertFalse(server.isActive, "an -EBADF CQE must mark the server inactive")
            assertEquals(
                getSqeAfterArm,
                fake.getSqeCalls,
                "the -EBADF onCqe branch must NOT rearm (would loop on the same broken fd)",
            )
        }
    }

    @Test
    fun `EBADF fails the pending acceptMultishot waiter with CancellationException`() = runBlocking {
        withServer { fake, bossLoop, server ->
            val pending = async(start = CoroutineStart.UNDISPATCHED) { server.acceptMultishot() }
            assertTrue(bossLoop.runIteration(Cqe()))
            val acceptUserData = fake.lastSqeUserData()

            // Deliver -EBADF while the waiter is still suspended.
            fake.enqueueCqe(userData = acceptUserData, res = -EBADF, flags = 0u, hasMore = false)
            assertTrue(bossLoop.runIteration(Cqe()))

            // The fix must resume the waiter with CancellationException
            // carrying the errno; without the fix it hangs forever.
            val ex = assertFailsWith<CancellationException> { pending.await() }
            assertTrue(
                ex.message?.contains("errno=$EBADF") == true,
                "cancellation message must carry the errno, got: ${ex.message}",
            )
            assertFalse(server.isActive, "the server is unhealthy after the kernel-rejected accept")
        }
    }

    @Test
    fun `error CQE arriving after close is silently ignored`() = runBlocking {
        // Race the audit specifically called out: close() and -EBADF CQE
        // arriving in flight. The pre-existing `if (!_active)` short-circuit
        // already handled this; the new -EBADF branch must NOT cross-fire.
        withServer { fake, bossLoop, server ->
            val pending = async(start = CoroutineStart.UNDISPATCHED) { server.acceptMultishot() }
            assertTrue(bossLoop.runIteration(Cqe()))
            val acceptUserData = fake.lastSqeUserData()
            val getSqeAfterArm = fake.getSqeCalls

            // Close first — drains the cleanup dispatch + cancels the waiter.
            server.close()
            assertTrue(bossLoop.runIteration(Cqe()))
            assertFailsWith<CancellationException> { pending.await() }
            val getSqeAfterClose = fake.getSqeCalls

            // Then deliver -EBADF — the early-return for `!_active` must fire,
            // not the new error branch.
            fake.enqueueCqe(userData = acceptUserData, res = -EBADF, flags = 0u, hasMore = false)
            assertTrue(bossLoop.runIteration(Cqe()))
            assertEquals(
                getSqeAfterClose,
                fake.getSqeCalls,
                "post-close error CQE must not trigger any new SQE submission",
            )
            assertFalse(server.isActive)
            // Sanity: the only SQE allocations so far were the arm + the
            // close-side cancel SQE. The error branch did nothing.
            assertTrue(
                fake.getSqeCalls > getSqeAfterArm,
                "close() submits its own cancel SQE (counted to confirm the close path ran)",
            )
        }
    }

    private companion object {
        // io_uring opcode value from `enum io_uring_op` in <linux/io_uring.h>.
        // Same rationale as the other seam tests: stable kernel ABI.
        private const val IORING_OP_ACCEPT: UByte = 13u
    }
}
