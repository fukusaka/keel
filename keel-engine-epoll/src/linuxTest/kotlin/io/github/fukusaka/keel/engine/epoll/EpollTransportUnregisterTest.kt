@file:OptIn(InternalReadinessEngineApi::class)

package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.native.readiness.Interest
import io.github.fukusaka.keel.native.readiness.InternalReadinessEngineApi
import io.github.fukusaka.keel.native.readiness.PosixIoTransport
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.seconds

/**
 * Closing a connection must withdraw its callback registration.
 *
 * The registrations map is keyed by fd number, so one left behind is not a
 * growing leak — the next connection on that number overwrites it — but until
 * then it keeps the transport, its channel and the pipeline graph behind it
 * reachable. The server side already withdraws its listener on close; the
 * transport did not.
 */
class EpollTransportUnregisterTest {

    @Test
    fun `closing a connection withdraws its callback registration`() = runBlocking {
        withTimeout(TEST_BUDGET_S.seconds) {
            val engine = EpollEngine()
            val server = engine.bind(LOOPBACK_HOST, 0)
            val port = (server.localAddress as InetSocketAddress).port

            val client = engine.connect(LOOPBACK_HOST, port)
            val accepted = server.accept()
            val fd = ((client as AbstractPipelinedChannel).transport as PosixIoTransport).fd

            client.close()
            accepted.close()

            // Settle: close is asynchronous by contract, so the teardown runs on
            // the loop some time after close() returns. Polled to a deadline
            // rather than wrapped in withTimeout so the assertion below is what
            // reports a regression.
            awaitOrGiveUp { !engine.hasWorkerRegistration(fd, Interest.READ) }
            assertFalse(
                engine.hasWorkerRegistration(fd, Interest.READ),
                "a closed connection left its callback registration behind; it keeps the " +
                    "transport and everything it references reachable until the fd number is reused.",
            )

            server.close()
            engine.close()
        }
    }

    @Test
    fun `closing a connection with a stalled write withdraws that registration too`() = runBlocking {
        withTimeout(TEST_BUDGET_S.seconds) {
            val engine = EpollEngine()
            val server = engine.bind(LOOPBACK_HOST, 0)
            val port = (server.localAddress as InetSocketAddress).port

            val client = engine.connect(LOOPBACK_HOST, port)
            val accepted = server.accept()
            val transport = (client as AbstractPipelinedChannel).transport as PosixIoTransport
            val fd = transport.fd

            // The peer never reads, so the kernel buffers fill and the write
            // stalls — the only thing that registers a WRITE callback. The
            // writing has to happen off to the side: flush() suspends until the
            // data is drained, which by construction never happens here, so
            // driving it inline would hang instead of stalling.
            val writer = launch {
                // Unbounded on purpose: how much a host absorbs before a write
                // stalls depends on its socket buffer limits, so a fixed budget
                // would leave this test passing or failing by tcp_wmem. The
                // close below cancels it.
                while (isActive) {
                    val buf = DefaultAllocator.allocate(CHUNK_BYTES)
                    repeat(CHUNK_BYTES) { i -> buf.writeByte((i and 0xFF).toByte()) }
                    client.write(buf)
                    client.flush()
                }
            }
            withTimeout(STALL_BUDGET_S.seconds) {
                while (!engine.hasWorkerRegistration(fd, Interest.WRITE)) delay(POLL_MS)
            }

            // Closing unblocks the stalled writer.
            client.close()
            writer.cancel()

            // Asked per fd and per interest on purpose. A total returns to its
            // baseline whichever half of the teardown ran, because the peer's
            // transport tears down at the same time — which is why an earlier
            // version of this test passed with the WRITE withdrawal deleted.
            // Poll to a deadline and then assert, rather than letting a
            // withTimeout carry the failure: a regression should say which
            // registration survived, not just that something took too long.
            awaitOrGiveUp { !engine.hasWorkerRegistration(fd, Interest.WRITE) }
            assertFalse(
                engine.hasWorkerRegistration(fd, Interest.WRITE),
                "the stalled write's WRITE registration survived the close; the teardown withdrew " +
                    "READ but not WRITE, so the transport stays reachable from the loop.",
            )
            assertFalse(
                engine.hasWorkerRegistration(fd, Interest.READ),
                "the READ registration survived the close.",
            )

            accepted.close()
            server.close()
            engine.close()
        }
    }

    /** Polls [condition] to a deadline; returns either way so an assertion reports the failure. */
    private suspend fun awaitOrGiveUp(condition: () -> Boolean) {
        val deadline = SETTLE_BUDGET_S * MILLIS_PER_SECOND
        var waited = 0L
        while (waited < deadline && !condition()) {
            delay(POLL_MS)
            waited += POLL_MS
        }
    }

    private companion object {
        private const val TEST_BUDGET_S = 20
        private const val SETTLE_BUDGET_S = 5
        private const val POLL_MS = 10L
        private const val MILLIS_PER_SECOND = 1000L
        private const val STALL_BUDGET_S = 10
        private const val CHUNK_BYTES = 64 * 1024
    }
}
