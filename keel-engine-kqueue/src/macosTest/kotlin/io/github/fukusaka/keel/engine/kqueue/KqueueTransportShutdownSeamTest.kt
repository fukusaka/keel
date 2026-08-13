@file:OptIn(InternalReadinessEngineApi::class)

package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.logging.LogLevel
import io.github.fukusaka.keel.native.posix.FakeNativeSocket
import io.github.fukusaka.keel.native.readiness.Interest
import io.github.fukusaka.keel.native.readiness.InternalReadinessEngineApi
import io.github.fukusaka.keel.native.readiness.PosixIoTransport
import io.github.fukusaka.keel.native.posix.ShutdownResult
import io.github.fukusaka.keel.native.posix.WriteResult
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.EPIPE
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Seam tests for [PosixIoTransport]'s half-close: the `shutdownOutput`
 * errno branches, and the ordering that holds the FIN back until the
 * buffered writes have drained.
 */
@OptIn(ExperimentalForeignApi::class)
internal class KqueueTransportShutdownSeamTest : KqueueTransportSeamFixture() {

    // --- shutdownOutput ---

    @Test
    fun `shutdownOutput with Ok response invokes nativeSocket once`() = runBlocking {
        // shutdown(2) runs on the EventLoop like every other op on this fd, so
        // the loop has to be running and the assertion has to wait for it.
        eventLoop.start()
        val fake = FakeNativeSocket().apply {
            enqueueShutdown(fd, ShutdownResult.Ok)
        }
        val transport = PosixIoTransport(fd, eventLoop, DefaultAllocator, fake)

        transport.shutdownOutput()

        awaitLoopDrained()
        assertEquals(1, fake.shutdownCalls)
        fake.assertAllConsumed()
    }

    @Test
    fun `shutdownOutput is idempotent`() = runBlocking {
        eventLoop.start()
        val fake = FakeNativeSocket().apply {
            enqueueShutdown(fd, ShutdownResult.Ok)
        }
        val transport = PosixIoTransport(fd, eventLoop, DefaultAllocator, fake)

        transport.shutdownOutput()
        transport.shutdownOutput()
        transport.shutdownOutput()

        // All three dispatches have run by now (FIFO marker), so a lost
        // short-circuit shows up as 2 or 3 rather than passing on timing.
        awaitLoopDrained()
        assertEquals(1, fake.shutdownCalls)
    }

    @Test
    fun `shutdownOutput with Failed EPIPE does not throw`() = runBlocking {
        // The body now runs inside a dispatched task, and drainTasks catches
        // whatever a task throws. So "does not throw" can no longer be observed
        // by the call returning — assert on the loop's own guard log instead:
        // a throw would surface as "dispatched task threw", and the failure has
        // to surface as the transport's own shutdown warning.
        val warns = RecordingLogger(LogLevel.WARN)
        val loop = KqueueEventLoop(warns, flushCoalescing = false)
        loop.start()
        try {
            val fake = FakeNativeSocket().apply {
                enqueueShutdown(fd, ShutdownResult.Failed(EPIPE))
            }
            val transport = PosixIoTransport(fd, loop, DefaultAllocator, fake)

            transport.shutdownOutput()

            val marker = CompletableDeferred<Unit>()
            loop.dispatch(EmptyCoroutineContext, Runnable { marker.complete(Unit) })
            withTimeout(SEAM_TIMEOUT_MS) { marker.await() }

            assertEquals(1, fake.shutdownCalls)
            assertTrue(
                warns.messages.any { "shutdown(SHUT_WR) failed" in it },
                "the EPIPE must be reported by the transport, got: ${warns.messages}",
            )
            assertTrue(
                warns.messages.none { "dispatched task threw" in it },
                "shutdownOutput must not throw out of the dispatched task, got: ${warns.messages}",
            )
        } finally {
            loop.close()
        }
    }

    // --- Half-close ordering (deferred FIN) ---

    @Test
    fun `shutdownOutput holds the FIN back while the flush is stalled`() = runBlocking {
        // A half-close issued on top of buffered output must not overtake it:
        // the FIN goes out only once the bytes have been written. The two
        // WouldBlock results keep the socket stalled across both the caller's
        // flush and the retry the half-close itself drives.
        eventLoop.start()
        val fake = FakeNativeSocket().apply {
            enqueueWrite(fd, WriteResult.WouldBlock, WriteResult.WouldBlock, WriteResult.Written(SEAM_PAYLOAD_BYTES))
            enqueueShutdown(fd, ShutdownResult.Ok)
        }
        val transport = PosixIoTransport(fd, eventLoop, DefaultAllocator, fake)

        // Observed inside the same loop task as the half-close. The fixture fd is
        // an unconnected socket, so arming it for write readiness can make the
        // loop report it writable straight away — reading the counter from a
        // later task would race that retry rather than test the deferral.
        val finCallsAtHalfClose = CompletableDeferred<Int>()
        eventLoop.dispatch(
            EmptyCoroutineContext,
            Runnable {
                val buf = DefaultAllocator.allocate(SEAM_PAYLOAD_BYTES)
                buf.writerIndex = SEAM_PAYLOAD_BYTES
                transport.write(buf)
                transport.flush()
                transport.shutdownOutput()
                finCallsAtHalfClose.complete(fake.shutdownCalls)
            },
        )
        assertEquals(
            0,
            withTimeout(SEAM_TIMEOUT_MS) { finCallsAtHalfClose.await() },
            "FIN must wait for the stalled write",
        )

        // Socket becomes writable — the retry drains the queue and releases the FIN.
        // Harmless if the loop already delivered write readiness on its own: the
        // second pass finds the queue empty and the FIN already sent.
        eventLoop.dispatch(EmptyCoroutineContext, Runnable { transport.onReady(Interest.WRITE) })
        awaitLoopDrained()
        assertEquals(1, fake.shutdownCalls, "FIN must follow the completed write")
        fake.assertAllConsumed()
    }

    @Test
    fun `write after shutdownOutput is discarded rather than queued`() = runBlocking {
        // The caller declared it had nothing more to send, so a later write
        // must not slip in behind the FIN. The buffer's ownership was still
        // transferred, so it has to be released rather than leaked.
        eventLoop.start()
        val fake = FakeNativeSocket().apply {
            enqueueShutdown(fd, ShutdownResult.Ok)
        }
        val transport = PosixIoTransport(fd, eventLoop, DefaultAllocator, fake)
        val tracker = TrackingAllocator()

        eventLoop.dispatch(
            EmptyCoroutineContext,
            Runnable {
                transport.shutdownOutput()
                val buf = tracker.allocate(SEAM_PAYLOAD_BYTES)
                buf.writerIndex = SEAM_PAYLOAD_BYTES
                transport.write(buf)
                transport.flush()
            },
        )
        awaitLoopDrained()

        assertEquals(1, fake.shutdownCalls)
        assertEquals(0, fake.writeCalls, "nothing may be sent after the FIN")
        assertEquals(0, tracker.outstandingCount, "the discarded write must still be released")
        fake.assertAllConsumed()
    }
}
