package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.native.posix.Interest
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

/**
 * A connection whose EventLoop stops is told, rather than going quiet.
 *
 * The loop's teardown sweep ends every suspend waiter it can no longer arm. The
 * pipeline-callback ledger beside it was never swept, so a transport registered
 * for readiness got no signal at all when the loop went away — and anything
 * waiting on that connection waited forever.
 *
 * This drives it through the public API: a live connection, then `engine.close()`,
 * which cancels the engine scope and closes the loops without closing live
 * transports first. The assertion before the close is what keeps the test honest —
 * without it, a `onReadClosed` fired by some earlier peer-close path would pass
 * this for the wrong reason.
 *
 * Two tests, because they need opposite wiring. The first replaces the channel's
 * `onReadClosed` and pins that **the transport hook fires** — deleting the
 * channel's wiring leaves it green, which is the point: it isolates the sweep.
 * The second leaves the production wiring alone and pins the **caller-visible**
 * effect, a reader parked in `read()` returning `-1` rather than never returning.
 * An earlier revision claimed the second was unobservable in coroutine mode and
 * deferred it; that was wrong, and measured to be wrong.
 */
class EpollLoopStopNotifiesTransportTest {

    private companion object {
        /** Long enough for the accept handshake to settle before the loop is stopped. */
        private const val SETTLE_MS = 50L

        /** The signal arrives on the loop's own teardown, so this is an upper bound, not a wait. */
        private const val LOOP_STOP_TIMEOUT_S = 5

        /** Wall-clock ceiling for the whole body, including `engine.close()`'s joins. */
        private const val BODY_TIMEOUT_S = 30

        /** Read buffer for the parked-reader test; size is irrelevant, it never fills. */
        private const val READ_BUF_BYTES = 1024

        /** Budget for the reader to reach its park; exceeded means fail, not hang. */
        private const val PARK_TIMEOUT_S = 5

        /** Poll granularity while waiting for the reader to publish its park. */
        private const val PARK_POLL_MS = 5L
    }

    @Test
    fun `a live connection is told when its EventLoop stops`() = runBlocking {
        // Bounds the suspending parts of the body, not just the trailing await:
        // Kotlin/Native kotlin.test has no per-test timeout and the build's
        // listener only warns. It does NOT bound engine.close() -- that joins the
        // loop threads through a native blocking call, which cooperative
        // cancellation cannot interrupt. Measured with 4s of user code in the
        // sweep and a 1s budget: the body ran 8s (two worker loops), then the
        // timeout surfaced and failed the test. So it reports an overrun after
        // the fact; it does not cut one short. A true hang there is caught by the
        // CI job timeout, not here.
        withTimeout(BODY_TIMEOUT_S.seconds) {
            val engine = EpollEngine()
            val server = engine.bind(LOOPBACK_HOST, 0)
            val port = (server.localAddress as InetSocketAddress).port

            val client = engine.connect(LOOPBACK_HOST, port)
            val serverCh = server.accept()

            val transport = (client as AbstractPipelinedChannel).transport
            val closedSignal = CompletableDeferred<Unit>()
            transport.onReadClosed = { closedSignal.complete(Unit) }

            // Settle the handshake so the failure mode is unambiguously about
            // loop stop and not a race on accept.
            delay(SETTLE_MS)
            assertFalse(closedSignal.isCompleted, "nothing has closed this connection yet")

            engine.close()

            withTimeout(LOOP_STOP_TIMEOUT_S.seconds) { closedSignal.await() }
            serverCh.close()
            server.close()
        }
    }

    @Test
    fun `a reader parked when its EventLoop stops returns EOF instead of hanging`() = runBlocking {
        // The production wiring left alone, so this pins what a caller sees: the
        // sweep tells the transport, the channel's own onReadClosed runs
        // notifyInactive(), and the reader parked in read() comes back with -1.
        // Without the sweep it never returns -- the "waited forever" this exists
        // to end.
        withTimeout(BODY_TIMEOUT_S.seconds) {
            val engine = EpollEngine()
            val server = engine.bind(LOOPBACK_HOST, 0)
            val port = (server.localAddress as InetSocketAddress).port

            val client = engine.connect(LOOPBACK_HOST, port)
            val serverCh = server.accept()
            val pipelined = client as AbstractPipelinedChannel

            val readResult = CompletableDeferred<Int>()
            val reader = launch {
                val buf = DefaultAllocator.allocate(READ_BUF_BYTES)
                try {
                    readResult.complete(client.read(buf))
                } finally {
                    buf.release()
                }
            }

            // Wait on a signal the reader publishes, not on a fixed delay.
            // `read()` hops to the loop thread, sets readEnabled, then parks; so
            // readEnabled == true means the reader is there. `isCompleted == false`
            // alone would not: it reads the same before the reader has started,
            // and stopping the loop in that state is unrecoverable -- the reader
            // then dispatches onto a joined loop, and neither withTimeout can
            // unwind through a child that can never be resumed. Measured: a reader
            // delayed past a fixed SETTLE_MS made this test hang for 90s with no
            // timeout firing. Waiting on readEnabled turns that into a failure.
            val parked = withTimeoutOrNull(PARK_TIMEOUT_S.seconds) {
                while (!pipelined.readEnabled) delay(PARK_POLL_MS)
                true
            }
            assertNotNull(
                parked,
                "the reader never reached its park; stopping the loop from here hangs rather than fails",
            )
            assertFalse(readResult.isCompleted, "and nothing has ended it yet")

            engine.close()

            assertEquals(
                -1,
                withTimeout(LOOP_STOP_TIMEOUT_S.seconds) { readResult.await() },
                "a stopped loop must surface as EOF to a parked reader, not as a hang",
            )
            reader.join()
            serverCh.close()
            server.close()
        }
    }

    @Test
    fun `a connection holding no registration is told when its EventLoop stops`() = runBlocking {
        // The connection the participant registry exists for. `init` arms READ
        // once; with `readEnabled` still false (nothing has called read()), the
        // first readiness event pops that one-shot entry and `onReadable`
        // declines to re-arm -- so the ledger holds nothing for this fd and the
        // kernel interest is taken back. A notification keyed on the ledger
        // walked straight past this state; keyed on the registry, the transport
        // is told all the same.
        withTimeout(BODY_TIMEOUT_S.seconds) {
            val engine = EpollEngine()
            val server = engine.bind(LOOPBACK_HOST, 0)
            val port = (server.localAddress as InetSocketAddress).port

            val client = engine.connect(LOOPBACK_HOST, port)
            val serverCh = server.accept()

            val transport = (client as AbstractPipelinedChannel).transport as EpollIoTransport
            val closedSignal = CompletableDeferred<Unit>()
            transport.onReadClosed = { closedSignal.complete(Unit) }

            // One readiness event: the peer sends a byte, the declined wake
            // consumes the one-shot entry and disarms.
            val buf = DefaultAllocator.allocate(8)
            buf.writeByte(0x41)
            serverCh.write(buf)
            serverCh.flush()

            // Wait for the ledger to actually empty -- a signal, not a sleep.
            val empty = withTimeoutOrNull(PARK_TIMEOUT_S.seconds) {
                while (
                    engine.hasWorkerRegistration(transport.fd, Interest.READ) ||
                    engine.hasWorkerRegistration(transport.fd, Interest.WRITE)
                ) {
                    delay(PARK_POLL_MS)
                }
                true
            }
            assertNotNull(empty, "the declined wake must leave the ledger empty for this fd")
            assertFalse(closedSignal.isCompleted, "nothing has closed this connection yet")

            engine.close()

            withTimeout(LOOP_STOP_TIMEOUT_S.seconds) { closedSignal.await() }
            serverCh.close()
            server.close()
        }
    }

    @Test
    fun `a closed connection leaves the participant registry`() = runBlocking {
        // The remove half of the registry contract, through the public API. A
        // transport that closes must leave, or a long-lived loop with
        // connection churn grows the registry without bound -- the retention
        // this registry was built to end, reintroduced by a missing removal.
        withTimeout(BODY_TIMEOUT_S.seconds) {
            val engine = EpollEngine()
            val server = engine.bind(LOOPBACK_HOST, 0)
            val port = (server.localAddress as InetSocketAddress).port

            val client = engine.connect(LOOPBACK_HOST, port)
            val serverCh = server.accept()
            assertEquals(
                2,
                engine.workerParticipants(),
                "both live transports (connect side and accepted side) are in the registry",
            )

            client.close()
            serverCh.close()

            // Teardown runs on the loop; wait for it to land, bounded.
            val left = withTimeoutOrNull(PARK_TIMEOUT_S.seconds) {
                while (engine.workerParticipants() != 0) delay(PARK_POLL_MS)
                true
            }
            assertNotNull(left, "closed connections must leave the registry, or churn grows it forever")

            server.close()
            engine.close()
        }
    }
}
