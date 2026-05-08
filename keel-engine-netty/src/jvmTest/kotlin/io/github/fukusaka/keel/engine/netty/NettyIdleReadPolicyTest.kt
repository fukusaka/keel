package io.github.fukusaka.keel.engine.netty

import io.github.fukusaka.keel.core.IdleReadPolicy
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Verifies the contract of [IdleReadPolicy] for `engine-netty` when
 * the underlying [NettyTransport] is [NettyTransport.Nio].
 *
 * The native Netty transports ([NettyTransport.Epoll] /
 * [NettyTransport.KQueue]) issue `epoll_ctl(EPOLLRDHUP)` /
 * `kevent(EVFILT_READ)` directly through JNI and observe peer FIN
 * regardless of `setAutoRead` state, so [IdleReadPolicy] is a no-op
 * for those — `NettyEngine` resolves [effectiveIdleReadPolicy] to
 * [IdleReadPolicy.PRESERVE_BACKPRESSURE] for native transports
 * regardless of the user's [IoEngineConfig.idleReadPolicy], and the
 * existing `NettyPeerCloseWithDisabledReadTest` already covers that
 * path.
 *
 * This file targets the [NettyTransport.Nio] branch where the policy
 * is meaningful:
 *
 * - [IdleReadPolicy.DETECT_PEER_CLOSE]: `NettyIoTransport` enables
 *   `setAutoRead = true` at construction so the underlying Java NIO
 *   `Selector` keeps `OP_READ` registered and observes peer FIN as a
 *   `channelInactive` / `ChannelInputShutdownReadComplete` event.
 *   Bytes the peer sends while `readEnabled = false` are released
 *   without delivery (the documented cost — Java NIO `Selector` cannot
 *   detect peer FIN without an active read).
 * - [IdleReadPolicy.PRESERVE_BACKPRESSURE] (default): existing
 *   behaviour — auto-read stays disabled until `readEnabled = true`,
 *   so kernel `rcvbuf` retains bytes; peer FIN is not surfaced in this
 *   idle window.
 *
 * Red-Green verification (DETECT_PEER_CLOSE on NIO): swap the
 * `if (idleReadPolicy == DETECT_PEER_CLOSE) armRead()` block in
 * `NettyIoTransport` with `// noop`; the test fails with a 3 s timeout
 * because the channel never has `OP_READ` interest set and Java NIO
 * `Selector` cannot deliver any close event for the channel. Restoring
 * the always-arm makes it pass within ~1 ms on loopback.
 */
class NettyIdleReadPolicyTest {

    @Test
    fun `DETECT_PEER_CLOSE on NIO transport — peer FIN fires onReadClosed when readEnabled stays false`() = runBlocking {
        // Wrap the test body in a real-time-backed dispatcher because
        // runTest's virtual time clashes with the Channel-based wait
        // primitives used by the engine on the EventLoop thread.
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            val engine = NettyEngine(
                config = IoEngineConfig(idleReadPolicy = IdleReadPolicy.DETECT_PEER_CLOSE),
                nettyTransport = NettyTransport.Nio,
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

                // Settle the connection establishment before triggering
                // the FIN so the test failure mode is unambiguously about
                // post-establish peer-close detection.
                delay(SETTLE_MS)

                // Peer (server-side) closes — sends FIN to client.
                serverCh.close()

                // With DETECT_PEER_CLOSE on NIO, the engine arms
                // setAutoRead = true at construction so OP_READ stays
                // registered. channelInactive (or
                // ChannelInputShutdownReadComplete) fires on FIN and
                // onReadClosed surfaces the close.
                withTimeout(EOF_DETECT_TIMEOUT_S.seconds) { closedSignal.await() }
            } finally {
                client.close()
                server.close()
                engine.close()
            }
        }
    }

    private companion object {
        private const val SETTLE_MS = 100L
        private const val EOF_DETECT_TIMEOUT_S = 3
    }
}
