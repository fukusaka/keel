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
 * Re-enabling read delivers a FIN that arrived while read was disabled.
 *
 * Declining to re-arm under back-pressure gives up peer-close detection: the
 * interest that carries EOF is taken back, and the registration is one-shot, so
 * nothing re-delivers a close in the meantime. This is the documented recovery
 * path out of that state, and the reason the give-up is acceptable — the close
 * is not lost, only deferred until the reader comes back.
 *
 * Ordering here is data-then-FIN, the case that leaves the interest disarmed at
 * the moment the peer closes.
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
