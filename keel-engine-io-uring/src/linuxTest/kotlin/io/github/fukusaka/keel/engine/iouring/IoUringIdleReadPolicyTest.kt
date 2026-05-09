package io.github.fukusaka.keel.engine.iouring

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
 * Verifies the contract of [IdleReadPolicy] for `engine-io-uring`:
 *
 * - [IdleReadPolicy.DETECT_PEER_CLOSE]: peer FIN surfaces through
 *   `IoTransport.onReadClosed` even when `PipelinedChannel.readEnabled =
 *   false` for the entire connection lifetime (write-only push client
 *   shape). io_uring's multishot `IORING_OP_RECV` with a provided buffer
 *   ring is an active receive operation, not a pure event-readiness
 *   notification — so detecting peer FIN requires the recv to be armed.
 *   `engine-io-uring` therefore honours [IdleReadPolicy] the same way
 *   `engine-nio` / `engine-netty` NIO fallback / `engine-nwconnection`
 *   do: arm the multishot recv at construction (after
 *   `AbstractPipelinedChannel.init` wires up callbacks via the
 *   `onChannelAttached` hook from PR #475) so the kernel delivers a
 *   `res = 0` CQE on FIN regardless of `readEnabled` state.
 *
 * Red-Green verification: comment out `if (idleReadPolicy ==
 * DETECT_PEER_CLOSE) armRecv()` in `IoUringIoTransport.onChannelAttached`;
 * the test fails with a 1 s timeout because the multishot recv is never
 * submitted and no CQE fires on FIN. Restore the call and the test
 * passes within ~1 ms.
 */
class IoUringIdleReadPolicyTest {

    @Test
    fun `DETECT_PEER_CLOSE — peer FIN fires onReadClosed when readEnabled stays false`() = runBlocking<Unit> {
        val engine = IoUringEngine(
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
            // post-establish peer-close detection.
            delay(SETTLE_MS)

            // Peer (server-side) closes — sends FIN to client.
            serverCh.close()

            // With DETECT_PEER_CLOSE, the engine arms multishot recv
            // at onChannelAttached() so the kernel delivers a res = 0
            // CQE on FIN regardless of readEnabled, and onReadClosed
            // fires within milliseconds.
            withTimeout(EOF_DETECT_TIMEOUT_S.seconds) { closedSignal.await() }
        } finally {
            client.close()
            server.close()
            engine.close()
        }
    }

    private companion object {
        private const val SETTLE_MS = 100L

        /**
         * Generous outer cap — drain runs sub-millisecond on loopback
         * after fix.
         */
        private const val EOF_DETECT_TIMEOUT_S = 1
    }
}
