package io.github.fukusaka.keel.engine.nwconnection

import io.github.fukusaka.keel.core.IdleReadPolicy
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Verifies the contract of [IdleReadPolicy] for `engine-nwconnection`:
 *
 * - [IdleReadPolicy.DETECT_PEER_CLOSE]: peer FIN surfaces through
 *   `IoTransport.onReadClosed` even when `PipelinedChannel.readEnabled =
 *   false` for the entire connection lifetime (write-only push client
 *   shape). NWConnection has no event-readiness API distinct from
 *   `nw_connection_receive` data delivery, so the engine arms a
 *   receive at construction and observes peer FIN via the receive
 *   completion's `is_complete = true` flag with `len = 0`. Two
 *   trade-offs accompany this mode (documented on `NwIoTransport` and
 *   `IdleReadPolicy`): kernel-level back-pressure is not preserved,
 *   and bytes the peer sends before the channel's pipeline acquires
 *   its first user inbound handler are released by `TailHandler`.
 *   This test exercises peer-FIN detection only.
 *
 * Red-Green verification: revert the `init { armRead() }` block in
 * `NwIoTransport`; the test fails with a 1 s timeout because no
 * receive is pending and NWConnection delivers no completion on FIN.
 * Restoring it makes the test pass within ~1 ms on loopback.
 */
class NwIdleReadPolicyTest {

    @Test
    fun `DETECT_PEER_CLOSE — peer FIN fires onReadClosed when readEnabled stays false`() = runBlocking {
        val engine = NwEngine(
            IoEngineConfig(idleReadPolicy = IdleReadPolicy.DETECT_PEER_CLOSE),
        )
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val client = engine.connect("127.0.0.1", port)
        val serverCh = server.accept()

        try {
            val transport = (client as AbstractPipelinedChannel).transport
            // Public API contract: a write-only push client may keep
            // readEnabled = false for the entire connection lifetime.
            transport.readEnabled = false

            val closedSignal = CompletableDeferred<Unit>()
            transport.onReadClosed = { closedSignal.complete(Unit) }

            // Settle the connection establishment before triggering the
            // FIN so the test failure mode is unambiguously about
            // post-establish peer-close detection and not a race on
            // accept handshake.
            delay(SETTLE_MS)

            // Peer (server-side) closes — sends FIN to client.
            serverCh.close()

            // With DETECT_PEER_CLOSE, the engine arms a receive at
            // construction. The receive completes with `is_complete =
            // true` on FIN and onReadClosed fires.
            withTimeout(EOF_DETECT_TIMEOUT_S.seconds) { closedSignal.await() }
        } finally {
            client.close()
            server.close()
            engine.close()
        }
    }

    private companion object {
        private const val SETTLE_MS = 100L
        private const val EOF_DETECT_TIMEOUT_S = 1
    }
}
