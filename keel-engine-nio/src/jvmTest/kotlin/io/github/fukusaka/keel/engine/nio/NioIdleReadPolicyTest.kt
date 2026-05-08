package io.github.fukusaka.keel.engine.nio

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.core.IdleReadPolicy
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.seconds

/**
 * Verifies the contract of [IdleReadPolicy] for `engine-nio`:
 *
 * - [IdleReadPolicy.DETECT_PEER_CLOSE]: peer FIN surfaces through
 *   `IoTransport.onReadClosed` even when `PipelinedChannel.readEnabled =
 *   false` for the entire connection lifetime (write-only push client
 *   shape). Java NIO `Selector` cannot expose `POLLRDHUP` directly, so
 *   the engine arms `OP_READ` at construction and observes peer FIN as
 *   `SocketChannel.read = -1` regardless of `readEnabled`. The cost is
 *   that bytes the peer sends while `readEnabled = false` are released
 *   instead of being held in `rcvbuf` (kernel-level back-pressure is
 *   not preserved); this test exercises peer-FIN detection only.
 * - [IdleReadPolicy.PRESERVE_BACKPRESSURE]: the engine keeps `OP_READ`
 *   disarmed while `readEnabled = false`, so `rcvbuf` retains the
 *   bytes and the peer's TCP window stalls. Peer FIN is not surfaced
 *   in this idle window.
 *
 * Red-Green verification (DETECT_PEER_CLOSE): swap the [init] block in
 * `NioIoTransport` from `if (idleReadPolicy == DETECT_PEER_CLOSE)
 * armRead()` to `// noop`; the test fails with a 1 s timeout because
 * the channel's selection key never has `OP_READ` interest set, so the
 * `read = -1` event is never observed. Restoring the always-arm makes
 * it pass within ~1 ms on loopback.
 */
class NioIdleReadPolicyTest {

    @Test
    fun `DETECT_PEER_CLOSE — peer FIN fires onReadClosed when readEnabled stays false`() = runBlocking {
        val engine = NioEngine(
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

            // Settle the connection establishment before triggering the FIN
            // so the test failure mode is unambiguously about post-establish
            // peer-close detection and not a race on accept handshake.
            delay(SETTLE_MS)

            // Peer (server-side) closes — sends FIN to client.
            serverCh.close()

            // With DETECT_PEER_CLOSE, the engine has OP_READ armed since
            // construction. The selector fires a readable event on FIN,
            // SocketChannel.read returns -1, and onReadClosed fires.
            withTimeout(EOF_DETECT_TIMEOUT_S.seconds) { closedSignal.await() }
        } finally {
            client.close()
            server.close()
            engine.close()
        }
    }

    @Test
    fun `PRESERVE_BACKPRESSURE — data with readEnabled=false stays in kernel rcvbuf`() = runBlocking {
        val engine = NioEngine(
            IoEngineConfig(idleReadPolicy = IdleReadPolicy.PRESERVE_BACKPRESSURE),
        )
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val client = engine.connect("127.0.0.1", port)
        val serverCh = server.accept()

        try {
            val transport = (client as AbstractPipelinedChannel).transport
            transport.readEnabled = false

            var clientBytesReceived = 0
            transport.onRead = { buf ->
                clientBytesReceived += buf.readableBytes
                buf.release()
            }

            // Server sends data toward the client.
            val payload = ByteArray(PAYLOAD_BYTES) { (it and 0xFF).toByte() }
            val outBuf = DefaultAllocator.allocate(PAYLOAD_BYTES)
            for (b in payload) outBuf.writeByte(b)
            serverCh.write(outBuf)
            serverCh.flush()

            // PRESERVE_BACKPRESSURE: the engine does not arm OP_READ while
            // readEnabled is false, so the selector never fires a readable
            // event on this channel. The data sits in kernel rcvbuf and
            // applies TCP back-pressure to the peer.
            delay(SETTLE_MS)

            assertFalse(
                clientBytesReceived > 0,
                "$clientBytesReceived bytes were delivered to onRead while readEnabled was false in " +
                    "PRESERVE_BACKPRESSURE mode. The kernel rcvbuf must retain the data so TCP " +
                    "applies back-pressure to the peer; bytes should only be drained when the user " +
                    "flips readEnabled = true.",
            )

            // Sanity: flipping readEnabled to true delivers the buffered bytes.
            transport.readEnabled = true
            withTimeout(EOF_DETECT_TIMEOUT_S.seconds) {
                while (clientBytesReceived < PAYLOAD_BYTES) delay(10)
            }
        } finally {
            client.close()
            serverCh.close()
            server.close()
            engine.close()
        }
    }

    private companion object {
        private const val SETTLE_MS = 100L

        /**
         * Generous outer cap for the peer-close detection — after the
         * fix the actual delay is sub-millisecond on loopback, so the
         * 1 s cap is purely a safety net against a regressed
         * implementation that re-introduces the bug.
         */
        private const val EOF_DETECT_TIMEOUT_S = 1

        /** Test payload size — small enough to fit in any reasonable rcvbuf. */
        private const val PAYLOAD_BYTES = 1024
    }
}
