package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.logging.NoopLoggerFactory
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import platform.posix.ENOBUFS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Seam-level unit tests for [IoUringOwnedSource]'s multishot recv state
 * machine — specifically the `-ENOBUFS` (buffer-ring exhaustion) and the
 * `hasMore=0` re-arm paths that integration tests cover only as side
 * effects of real loopback echo.
 *
 * The audit that surfaced this gap noted that integration tests at
 * loopback cannot reliably exercise `-ENOBUFS` (kernel buffer-ring is
 * rarely exhausted at loopback speed) and the re-arm sequencing
 * (`needsRearm` flag, slot release, next-`readOwned` triggers rearm)
 * has no deterministic driver. The fake ring's scripted CQE delivery
 * (Phase A2 knob (a) [FakeIoUringRing.lastSqeOp]) lets us deliver an
 * exact `-ENOBUFS` CQE at the exact slot the multishot was armed on,
 * then assert that:
 *
 * 1. The suspended `readOwned()` continuation remains suspended (no
 *    spurious resume with a null/EOF marker).
 * 2. No new SQE is submitted on the `-ENOBUFS` path itself (rearm is
 *    deferred until the caller-driven `readOwned` re-entry).
 * 3. The next `readOwned()` call submits a fresh multishot recv SQE
 *    (the `needsRearm` branch in `readOwned`).
 *
 * Part of the io_uring seam audit follow-up (#A-audit-1). Phase C2
 * (zero-copy-to-codec) and Phase C4 (peer-FIN backpressure) build on
 * these invariants — if `-ENOBUFS` were to spuriously resume the read
 * with `null`, codec consumers would see a false EOF; if the slot were
 * not released, a later rearm would silently leak SQE slots.
 *
 * The test runs in [runBlocking] on the test thread without spawning
 * the EventLoop pthread. The dispatched arm-block is drained via
 * [IoUringEventLoop.runIteration], which is the same entry point the
 * EL pthread uses in production. `assertInEventLoop` no-ops pre-`start`
 * so this is the canonical seam pattern (mirrors `IoUringCqeSeamTest`).
 */
@OptIn(ExperimentalForeignApi::class)
class IoUringOwnedSourceSeamTest {

    private val logger = NoopLoggerFactory.logger("IoUringOwnedSourceSeamTest")

    /**
     * Builds an [IoUringEventLoop] + [ProvidedBufferRing] +
     * [IoUringOwnedSource] backed by [fake] / [bufRingFake], runs
     * [block], then tears everything down.
     */
    private suspend fun withSource(
        fake: FakeIoUringRing = FakeIoUringRing(),
        bufRingFake: FakeIoUringBufferRingOps = FakeIoUringBufferRingOps(),
        bufferCount: Int = 4,
        bufferSize: Int = 64,
        fd: Int = 7,
        block: suspend (FakeIoUringRing, IoUringEventLoop, ProvidedBufferRing, IoUringOwnedSource) -> Unit,
    ) {
        val el = IoUringEventLoop(logger, syscallOps = FakeIoUringSyscallOps(), ioUringRing = fake)
        val bufRing = ProvidedBufferRing(el, logger, bufferCount, bufferSize, bgid = 0, bufRingFake)
        bufRing.initOnEventLoop()
        val source = IoUringOwnedSource(fd, el, bufRing)
        try {
            block(fake, el, bufRing, source)
        } finally {
            source.close()
            bufRing.close()
            el.close()
        }
    }

    @Test
    fun `enobufs leaves the readOwned continuation suspended without rearming`() = runBlocking {
        withSource { fake, el, _, source ->
            // Issue readOwned. The body dispatches the arm-block to the EL task
            // queue; UNDISPATCHED runs the coroutine immediately on this thread,
            // so the dispatch reaches the queue before runIteration drains it.
            val firstRead = async(start = CoroutineStart.UNDISPATCHED) { source.readOwned() }
            // Drain the task queue → arm-block fires → submitMultishotRecv writes
            // IORING_OP_RECV to the scratch SQE.
            assertTrue(el.runIteration(Cqe()), "arm iteration must succeed")
            assertEquals(IORING_OP_RECV, fake.lastSqeOp(), "arm prep wrote IORING_OP_RECV")
            val multishotUserData = fake.lastSqeUserData()
            val getSqeAfterArm = fake.getSqeCalls

            // Deliver -ENOBUFS for the multishot SQE. F_MORE=0 — kernel
            // terminates the multishot on buffer exhaustion.
            fake.enqueueCqe(userData = multishotUserData, res = -ENOBUFS, flags = 0u, hasMore = false)
            assertTrue(el.runIteration(Cqe()), "drain iteration must succeed")

            // Invariant 1: the readOwned continuation must NOT have resumed —
            // -ENOBUFS is not an EOF, it is a buffer-exhaustion that callers
            // recover from by releasing previously-issued buffers.
            assertFalse(firstRead.isCompleted, "readOwned must remain suspended on -ENOBUFS")
            // Invariant 2: no rearm yet (the next readOwned drives rearm).
            assertEquals(
                getSqeAfterArm,
                fake.getSqeCalls,
                "the -ENOBUFS CQE path must NOT submit a new SQE on its own",
            )

            // Cancel the suspended read so the test can move on without
            // dangling a coroutine.
            firstRead.cancel()
            // Drain the invokeOnCancellation dispatch (clears pendingReadCont).
            assertTrue(el.runIteration(Cqe()))
            assertFailsWith<CancellationException> { firstRead.await() }
        }
    }

    @Test
    fun `next readOwned after enobufs rearms a fresh multishot recv SQE`() = runBlocking {
        withSource { fake, el, _, source ->
            // Phase 1: arm + deliver -ENOBUFS (same setup as the previous test).
            val firstRead = async(start = CoroutineStart.UNDISPATCHED) { source.readOwned() }
            assertTrue(el.runIteration(Cqe()))
            val firstUserData = fake.lastSqeUserData()
            val getSqeAfterArm = fake.getSqeCalls

            fake.enqueueCqe(userData = firstUserData, res = -ENOBUFS, flags = 0u, hasMore = false)
            assertTrue(el.runIteration(Cqe()))

            // Cancel the first read and drain the cancellation dispatch so the
            // source has no pendingReadCont when the second read arrives.
            firstRead.cancel()
            assertTrue(el.runIteration(Cqe()))
            assertFailsWith<CancellationException> { firstRead.await() }

            // Phase 2: second readOwned must arm a fresh multishot recv via
            // the needsRearm branch.
            val secondRead = async(start = CoroutineStart.UNDISPATCHED) { source.readOwned() }
            assertTrue(el.runIteration(Cqe()), "second-arm iteration must succeed")

            assertEquals(
                getSqeAfterArm + 1,
                fake.getSqeCalls,
                "the next readOwned must submit a fresh multishot recv SQE",
            )
            assertEquals(IORING_OP_RECV, fake.lastSqeOp(), "second arm prepped IORING_OP_RECV")
            // The freed slot from the terminated multishot is reusable, so
            // user_data may equal `firstUserData` on rearm — that is correct
            // (lowest-released slot is the canonical choice). We do not
            // assert on user_data identity here.

            secondRead.cancel()
            assertTrue(el.runIteration(Cqe()))
            assertFailsWith<CancellationException> { secondRead.await() }
        }
    }

    @Test
    fun `multishot terminated by hasMore zero with data still pending requires rearm`() = runBlocking {
        // Belt-and-braces: even when the kernel terminates the multishot on a
        // successful CQE (e.g. the kernel decided to stop the multishot), the
        // engine sets needsRearm so the next readOwned re-arms.
        withSource { fake, el, _, source ->
            val firstRead = async(start = CoroutineStart.UNDISPATCHED) { source.readOwned() }
            assertTrue(el.runIteration(Cqe()))
            val firstUserData = fake.lastSqeUserData()
            val getSqeAfterArm = fake.getSqeCalls

            // Deliver a successful data CQE (32 bytes) with hasMore=false —
            // the kernel says "I am done with this multishot, no auto-rearm".
            // bid 0 is a valid buffer slot from initOnEventLoop's bufferCount=4.
            val flagsWithBufId: UInt = (0u shl 16) or (1u shl 0) // F_BUFFER bit + bid 0
            fake.enqueueCqe(
                userData = firstUserData,
                res = 32,
                flags = flagsWithBufId,
                hasMore = false,
            )
            assertTrue(el.runIteration(Cqe()))

            // The data CQE resumes firstRead with the IoBuf (32-byte payload).
            // We don't inspect the IoBuf — the contract being pinned is that
            // the slot is released + needsRearm = true, observable as the
            // next readOwned submitting a fresh SQE.
            val firstBuf = firstRead.await()
            assertTrue(firstBuf != null, "successful CQE must deliver an IoBuf")
            firstBuf!!.release()

            val secondRead = async(start = CoroutineStart.UNDISPATCHED) { source.readOwned() }
            assertTrue(el.runIteration(Cqe()))
            assertEquals(
                getSqeAfterArm + 1,
                fake.getSqeCalls,
                "hasMore=0 must mark needsRearm so the next readOwned re-arms",
            )
            assertEquals(IORING_OP_RECV, fake.lastSqeOp())

            secondRead.cancel()
            assertTrue(el.runIteration(Cqe()))
            assertFailsWith<CancellationException> { secondRead.await() }
        }
    }

    private companion object {
        // io_uring opcode value from `enum io_uring_op` in <linux/io_uring.h>.
        // Same rationale as IoUringSqeSeamTest's companion: the kernel ABI
        // freezes these (append-only with stable indices).
        private const val IORING_OP_RECV: UByte = 27u

        // Wall-clock bound for the runBlocking body. None of these tests
        // wait on real I/O, but the dispatched CQE drains run through the
        // EL's task queue → cooperative suspension points. The budget is
        // generous (10×) over the expected microsecond-scale execution.
        @Suppress("unused")
        private val ASYNC_BUDGET = 5.seconds
    }
}
