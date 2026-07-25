package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.seconds

/**
 * Where the construction-time read arm stops covering a connection, and what
 * brings it back.
 *
 * The transport arms read at construction so a peer close reaches a listener
 * that never enables reading — a write-only push client. That arm is one-shot.
 * A peer that sends data first wakes it, the back-pressure path declines to
 * re-arm (it does not want the data), and the interest carrying EOF is dropped
 * with it. From then on a close is not reported: the sibling peer-close test
 * covers the connection that receives nothing and stays covered; this covers
 * the one that receives something and does not.
 *
 * Asserted in both directions, because the negative half alone would pass for a
 * connection that was simply never closed: first that the close is *not*
 * reported while the interest is down, then that re-enabling read reports it.
 * That recovery is the reason the give-up is acceptable — the close is deferred,
 * not lost — and the only recovery there is, since `readEnabled = true` is what
 * calls `armRead()`.
 *
 * A client that keeps read disabled after receiving data therefore never learns
 * of the close. Fixing that needs a close-only interest the engine can hold
 * without waking on data; the engines cannot express one symmetrically today.
 */
class EpollReadResumeAfterFinTest {

    @Test
    fun `re-enabling read reports a FIN that arrived while read was disabled`() = runBlocking {
        withTimeout(TEST_BUDGET_S.seconds) {
        val engine = EpollEngine()
        val server = engine.bind(LOOPBACK_HOST, 0)
        val port = (server.localAddress as InetSocketAddress).port

        val client = engine.connect("127.0.0.1", port)
        val serverCh = server.accept()

        try {
            val transport = (client as AbstractPipelinedChannel).transport
            transport.readEnabled = false

            val closed = CompletableDeferred<Unit>()
            transport.onReadClosed = { closed.complete(Unit) }
            transport.onRead = { it.release() }

            val outBuf = DefaultAllocator.allocate(PAYLOAD_BYTES)
            repeat(PAYLOAD_BYTES) { outBuf.writeByte((it and 0xFF).toByte()) }
            serverCh.write(outBuf)
            serverCh.flush()
            delay(SETTLE_MS)

            // FIN lands while the interest is disarmed — nothing is delivered.
            serverCh.close()
            delay(SETTLE_MS)

            // The state under test. Without this the test would also pass if
            // the FIN were delivered while READ was still armed, which is the
            // ordinary path and not what this is pinning.
            assertFalse(
                closed.isCompleted,
                "peer close was reported while read was disarmed; this test is meant to cover the " +
                    "state where it is not, so the recovery below is what actually delivers it.",
            )

            transport.readEnabled = true
            closed.await()
        } finally {
            client.close()
            server.close()
            engine.close()
        }
        }
    }

    private companion object {
        private const val SETTLE_MS = 100L
        /** Wall-clock bound for the whole body, not just the await. */
        private const val TEST_BUDGET_S = 15
        private const val PAYLOAD_BYTES = 1024
    }
}
