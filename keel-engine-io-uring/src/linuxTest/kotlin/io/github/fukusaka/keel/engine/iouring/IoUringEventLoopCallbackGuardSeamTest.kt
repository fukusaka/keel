package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.logging.NoopLoggerFactory
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.interpretCPointer
import kotlinx.cinterop.nativeNullPtr
import kotlinx.coroutines.Runnable
import platform.posix.MSG_NOSIGNAL
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Seam-level unit tests for the EventLoop's exception guards around raw
 * (non-coroutine) callback invocations.
 *
 * The multishot CQE callback path has had a catch-and-warn guard since
 * its introduction, but its siblings did not — the same
 * protect-one-path-not-its-siblings shape as several earlier fixes. An
 * unguarded throw from any of them killed the EventLoop pthread, taking
 * every other connection on that loop down with it (observed in practice
 * when a handler's buffer-release contract violation threw from a
 * `SEND_ZC` completion callback). Three guards are pinned here:
 *
 * - the `SEND_ZC` fire-and-forget completion callback,
 * - dispatched raw [Runnable]s in the task drain (which must also not
 *   skip the remaining tasks of the same batch), and
 * - the provided-buffer ring's deferred re-arm chain, where the ring is
 *   shared by every connection on the loop, so one transport's throwing
 *   re-arm must not leave the remaining starved transports starved
 *   forever (no later `returnBuffer` is obligated to come from their
 *   buffers).
 *
 * Coroutine continuations need no guard: resuming from the EventLoop
 * thread routes through the dispatcher's task queue, so the coroutine
 * body runs under the drain guard and its exceptions belong to its Job.
 */
@OptIn(ExperimentalForeignApi::class)
class IoUringEventLoopCallbackGuardSeamTest {

    private val logger = NoopLoggerFactory.logger("IoUringEventLoopCallbackGuardSeamTest")

    @Test
    fun `a throwing SEND_ZC completion callback does not kill the EventLoop`() {
        val fake = FakeIoUringRing()
        val el = IoUringEventLoop(logger, syscallOps = FakeIoUringSyscallOps(), ioUringRing = fake)
        try {
            el.submitSendZcCallback(FD, DUMMY_PTR, 5u, MSG_NOSIGNAL, fixedFile = false) {
                error("completion callback contract violation")
            }
            val zcUserData = fake.lastSqeUserData()

            // Single-CQE completion (no F_MORE): completeZcSlot fires the
            // callback immediately. The throw must be caught and warned,
            // not propagated out of the drain.
            fake.enqueueCqe(userData = zcUserData, res = 5, flags = 0u, hasMore = false)
            assertTrue(el.runIteration(Cqe()), "the drain must survive the throwing callback")

            // The loop is still functional afterwards: a dispatched task runs.
            var ran = false
            el.dispatch(EmptyCoroutineContext, Runnable { ran = true })
            el.runIteration(Cqe())
            assertTrue(ran, "the EventLoop must keep servicing work after the throw")
        } finally {
            el.close()
            fake.dispose()
        }
    }

    @Test
    fun `a throwing dispatched task does not kill the EventLoop or skip the rest of the batch`() {
        val fake = FakeIoUringRing()
        val el = IoUringEventLoop(logger, syscallOps = FakeIoUringSyscallOps(), ioUringRing = fake)
        try {
            var secondRan = false
            el.dispatch(EmptyCoroutineContext, Runnable { error("first task throws") })
            el.dispatch(EmptyCoroutineContext, Runnable { secondRan = true })

            // Both tasks are drained in one batch; the first throw must be
            // caught and the second task must still run.
            el.runIteration(Cqe())
            assertTrue(secondRan, "a throwing task must not skip the rest of the drain batch")
        } finally {
            el.close()
            fake.dispose()
        }
    }

    @Test
    fun `a throwing deferred re-arm does not skip the remaining starved re-arms`() {
        val fake = FakeIoUringRing()
        val el = IoUringEventLoop(logger, syscallOps = FakeIoUringSyscallOps(), ioUringRing = fake)
        val ring =
            ProvidedBufferRing(el, logger, bufferCount = 4, bufferSize = 64, bgid = 0, FakeIoUringBufferRingOps())
        try {
            ring.initOnEventLoop()
            var secondRearmed = 0
            ring.requestRearmOnAvailable { error("first re-arm throws (e.g. SQ ring full)") }
            ring.requestRearmOnAvailable { secondRearmed++ }

            // One buffer comes back: both registered re-arms must be
            // attempted even though the first throws.
            ring.returnBuffer(0)
            assertEquals(1, secondRearmed, "the second starved transport must still re-arm")
        } finally {
            ring.close()
            el.close()
            fake.dispose()
        }
    }

    companion object {
        /** Synthetic fd; the fake ring never issues real syscalls against it. */
        private const val FD = 999

        /**
         * Non-null scratch pointer for the SEND_ZC prep. The fake ring's
         * scratch SQE absorbs the prep write and the kernel is never
         * involved, so the pointee is never dereferenced.
         */
        private val DUMMY_PTR = interpretCPointer<ByteVar>(nativeNullPtr + 1L)!!
    }
}
