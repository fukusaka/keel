package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.ECANCELED
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Seam-level unit tests for the allocator-buffer recv fallback — the read
 * mode [IoUringIoTransport] selects when the kernel has no provided
 * buffer ring (< 5.19, `bufferRing == null`): a plain single-shot
 * `IORING_OP_RECV` into an allocator-owned [IoBuf], re-armed per CQE.
 *
 * Contracts under test, and how they differ from the ring modes:
 *
 * - the arm shape carries neither `IORING_RECV_MULTISHOT` (ioprio) nor
 *   `IOSQE_BUFFER_SELECT` (flags) — the destination buffer is fixed at
 *   submit time;
 * - the in-flight buffer is transport-owned ([IoUringIoTransport]'s
 *   `pendingRecvBuf`) until its CQE: data hands ownership to `onRead`,
 *   while EOF / error / `-ECANCELED` release it inside the callback
 *   (never at teardown time — an async cancel may still complete with
 *   data, so an early release would return pooled memory the kernel can
 *   still write into);
 * - backpressure is inherent, as in the buffer-select single-shot mode:
 *   `readEnabled = false` at delivery time stops the re-arm.
 *
 * Buffer-release assertions use the [io.github.fukusaka.keel.buf.AbstractIoBuf]
 * refcount guard: a second `release()` on a fully-released buffer throws
 * `IllegalStateException("Buffer already released")`, which makes "the
 * transport released it" deterministically observable without GC-based
 * leak detection. The probe reads only the wrapper's refcount field, not
 * the freed native memory.
 */
@OptIn(ExperimentalForeignApi::class)
class IoUringTransportAllocRecvSeamTest {

    private val logger = NoopLoggerFactory.logger("IoUringTransportAllocRecvSeamTest")

    /**
     * Delegating allocator that records every [allocate]d buffer so a
     * test can assert on the transport's internal allocations (the R3
     * recv buffers never surface through any API on non-data paths).
     */
    private class RecordingAllocator(
        private val delegate: BufferAllocator = DefaultAllocator,
    ) : BufferAllocator {
        val allocated = mutableListOf<IoBuf>()

        override fun allocate(capacity: Int): IoBuf =
            delegate.allocate(capacity).also { allocated.add(it) }

        override fun wrapBytes(bytes: ByteArray, offset: Int, length: Int): IoBuf? =
            delegate.wrapBytes(bytes, offset, length)

        override fun slice(source: IoBuf, offset: Int, length: Int): IoBuf =
            delegate.slice(source, offset, length)
    }

    /**
     * Builds an [IoUringIoTransport] with NO provided buffer ring (the
     * `< 5.19` shape) over a fake-backed [IoUringEventLoop]. The
     * capability matrix matches a real pre-ring kernel:
     * `providedBufferRing = false, multishotRecv = false`.
     */
    private fun withTransport(
        fake: FakeIoUringRing = FakeIoUringRing(),
        allocator: RecordingAllocator = RecordingAllocator(),
        fd: Int = 999,
        block: (FakeIoUringRing, IoUringEventLoop, RecordingAllocator, IoUringIoTransport) -> Unit,
    ) {
        val el = IoUringEventLoop(logger, syscallOps = FakeIoUringSyscallOps(), ioUringRing = fake)
        val transport = IoUringIoTransport(
            fd = fd,
            eventLoop = el,
            capabilities = IoUringCapabilities(providedBufferRing = false, multishotRecv = false),
            writeModeSelector = IoModeSelectors.FALLBACK_CQE,
            allocator = allocator,
            bufferRing = null,
            fixedFileRegistry = null,
            registeredBufferTable = DisabledRegisteredBufferRegistry,
            preAllocatedIndex = -1,
            readBufferSize = RECV_BUFFER_SIZE,
        )
        try {
            block(fake, el, allocator, transport)
        } finally {
            el.close()
            fake.dispose()
        }
    }

    /** Asserts [buf] was fully released by probing the refcount guard. */
    private fun assertReleased(buf: IoBuf, message: String) {
        assertFailsWith<IllegalStateException>(message) { buf.release() }
    }

    @Test
    fun `readEnabled true without a buffer ring arms a plain single-shot recv`() {
        withTransport { fake, _, allocator, transport ->
            assertEquals(0, fake.getSqeCalls, "no SQE submitted at construction time")
            transport.readEnabled = true
            assertEquals(IORING_OP_RECV, fake.lastSqeOp(), "the setter prepped IORING_OP_RECV")
            assertEquals(
                0u,
                fake.lastSqeIoprio().toUInt() and IORING_RECV_MULTISHOT,
                "the plain recv must NOT set IORING_RECV_MULTISHOT",
            )
            assertEquals(
                0u,
                fake.lastSqeFlags().toUInt() and IOSQE_BUFFER_SELECT,
                "the plain recv must NOT set IOSQE_BUFFER_SELECT (no kernel-side buffer selection)",
            )
            assertEquals(1, fake.getSqeCalls, "exactly one recv SQE was submitted")
            assertEquals(1, allocator.allocated.size, "one recv buffer allocated for the in-flight SQE")
        }
    }

    @Test
    fun `data CQE hands the buffer to onRead and re-arms with a fresh allocation`() {
        withTransport { fake, el, allocator, transport ->
            var delivered = -1
            transport.onRead = { buf: IoBuf ->
                delivered = buf.readableBytes
                buf.release()
            }
            transport.readEnabled = true
            val afterArm = fake.getSqeCalls
            val recvUserData = fake.lastSqeUserData()

            fake.enqueueCqe(userData = recvUserData, res = 13, flags = 0u, hasMore = false)
            assertTrue(el.runIteration(Cqe()))

            assertEquals(13, delivered, "onRead received the CQE's byte count")
            assertEquals(afterArm + 1, fake.getSqeCalls, "delivery re-armed exactly one fresh recv")
            assertEquals(2, allocator.allocated.size, "the re-arm allocated a fresh buffer")
            assertReleased(allocator.allocated[0], "the delivered buffer was released by the handler")
        }
    }

    @Test
    fun `readEnabled false inside onRead stops the re-arm until the next true flip`() {
        withTransport { fake, el, _, transport ->
            transport.onRead = { buf: IoBuf ->
                transport.readEnabled = false
                buf.release()
            }
            transport.readEnabled = true
            val afterArm = fake.getSqeCalls
            val recvUserData = fake.lastSqeUserData()

            fake.enqueueCqe(userData = recvUserData, res = 13, flags = 0u, hasMore = false)
            assertTrue(el.runIteration(Cqe()))
            assertEquals(afterArm, fake.getSqeCalls, "with readEnabled=false at delivery end, no re-arm")

            transport.readEnabled = true
            assertEquals(afterArm + 1, fake.getSqeCalls, "the next readEnabled=true flip re-arms once")
        }
    }

    @Test
    fun `EOF CQE releases the pending buffer fires onReadClosed once and does not re-arm`() {
        withTransport { fake, el, allocator, transport ->
            var readClosed = 0
            transport.onReadClosed = { readClosed++ }
            transport.readEnabled = true
            val afterArm = fake.getSqeCalls
            val recvUserData = fake.lastSqeUserData()

            fake.enqueueCqe(userData = recvUserData, res = 0, flags = 0u, hasMore = false)
            assertTrue(el.runIteration(Cqe()))

            assertEquals(1, readClosed, "EOF fires onReadClosed exactly once")
            assertEquals(afterArm, fake.getSqeCalls, "EOF must not re-arm the recv")
            assertReleased(
                allocator.allocated.single(),
                "the never-delivered recv buffer was released by the EOF branch",
            )
        }
    }

    @Test
    fun `cancellation CQE after close releases the pending buffer without re-arming`() {
        withTransport { fake, el, allocator, transport ->
            transport.onReadClosed = { }
            transport.readEnabled = true
            val recvUserData = fake.lastSqeUserData()

            // Teardown cancels the in-flight recv SQE; the kernel later
            // delivers its terminal CQE (-ECANCELED, or late data). The
            // buffer must survive until that CQE and be released by it —
            // never at close() time, when the kernel may still write into it.
            // close() dispatches the teardown onto the EL task queue; drain
            // it first (this submits the ASYNC_CANCEL SQE) so the snapshot
            // below isolates the re-arm assertion from the cancel itself.
            transport.close()
            el.runIteration(Cqe())
            val afterTeardown = fake.getSqeCalls

            fake.enqueueCqe(userData = recvUserData, res = -ECANCELED, flags = 0u, hasMore = false)
            assertTrue(el.runIteration(Cqe()))

            assertEquals(afterTeardown, fake.getSqeCalls, "a post-close CQE must not re-arm")
            assertReleased(
                allocator.allocated.single(),
                "the cancelled recv's buffer was released by its terminal CQE",
            )
        }
    }

    companion object {
        /** Per-recv allocation size for the harness (any pooled size works). */
        private const val RECV_BUFFER_SIZE = 64

        /** `IORING_OP_RECV` kernel ABI opcode (io_uring.h). */
        private const val IORING_OP_RECV: UByte = 27u

        /** `IORING_RECV_MULTISHOT` ioprio flag bit (io_uring.h: `1U << 1`). */
        private const val IORING_RECV_MULTISHOT: UInt = 2u

        /**
         * `IOSQE_BUFFER_SELECT` SQE flag (io_uring.h: `1U <<
         * IOSQE_BUFFER_SELECT_BIT` = `1U << 5`). Precomputed because
         * cinterop does not surface the enum-derived `#define`.
         */
        private const val IOSQE_BUFFER_SELECT: UInt = 0x20u
    }
}
