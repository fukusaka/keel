package io.github.fukusaka.keel.engine.netty

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Verifies the Netty engine surfaces peer FIN to user code via
 * [io.github.fukusaka.keel.pipeline.IoTransport.onReadClosed] even when
 * the application keeps `PipelinedChannel.readEnabled = false` for the
 * entire connection lifetime — the natural shape of a write-only push
 * client, one-direction logger, or monitoring metrics sender.
 *
 * **Failure scenario without the fix**: `NettyEngine` used
 * `NioEventLoopGroup` unconditionally, which inherits Java NIO
 * `Selector`'s API constraint: [sun.nio.ch.SocketChannelImpl.translateInterestOps]
 * maps `OP_READ` to `Net.POLLIN` only — `POLLRDHUP` / `EPOLLRDHUP` is
 * never set. With `setAutoRead(false)` (= keel `readEnabled = false`),
 * Netty does not register `OP_READ`, the kernel never delivers
 * peer-FIN events, and `channelInactive` / `ChannelInputShutdownEvent`
 * never fire. `onReadClosed` did not fire and the connection sat in
 * CLOSE-WAIT until either the next write attempt failed (`EPIPE`) or
 * the `SO_KEEPALIVE` timer declared the peer dead.
 *
 * **Post-fix behaviour**: [NettyTransport.Auto] selects a native
 * transport when available — `EpollEventLoopGroup` on Linux,
 * `KQueueEventLoopGroup` on macOS / BSD — that calls `epoll_ctl` /
 * `kevent` directly through JNI with `EPOLLRDHUP` / `EV_EOF` requested.
 * Peer FIN fires `channelInactive` (or `ChannelInputShutdownEvent`
 * with `ALLOW_HALF_CLOSURE = true`) within milliseconds, regardless of
 * `setAutoRead(false)`. On platforms without a native transport
 * (typically Windows), `NioEventLoopGroup` is used as a fallback and
 * the gap remains; that is documented as a contract limitation, not
 * a bug.
 *
 * Red-Green verification:
 * - Red (pre-fix on Linux / macOS): `withTimeout(2.seconds) { closedSignal.await() }`
 *   throws `TimeoutCancellationException`.
 * - Green (post-fix on Linux / macOS): the await resumes within ~10 ms
 *   on loopback (probe tests at the time of writing show 100-300 ms
 *   end-to-end including connection setup).
 */
class NettyPeerCloseWithDisabledReadTest {

    @Test
    fun `peer FIN fires onReadClosed when readEnabled stays false`() = runTest {
        // Real-time wall-clock context: Netty native transports
        // (Epoll / KQueue) deliver peer-FIN events via JNI on the libev
        // loop in real time, but [runTest] uses a virtual
        // [TestCoroutineScheduler] where [delay] / [withTimeout] advance
        // virtual time without observing real-time wakeups. Without this
        // `withContext`, the `withTimeout(2.seconds)` below resolves at
        // virtual t=2s with a `TimeoutCancellationException` *before*
        // the real-time `channelInactive` callback has had a chance to
        // fire.
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            val engine = NettyEngine()
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

                delay(SETTLE_MS)
                serverCh.close()

                withTimeout(EOF_DETECT_TIMEOUT_S.seconds) { closedSignal.await() }
            } finally {
                client.close()
                server.close()
                engine.close()
            }
        }
    }

    /**
     * Back-pressure invariant: when the user keeps `readEnabled = false`
     * and the peer sends data (no FIN), the native transport keeps the
     * channel in `setAutoRead(false)` mode so Netty does not pull bytes
     * out of the kernel `rcvbuf`. The user-visible `onRead` must NOT be
     * invoked; flipping `readEnabled = true` flushes any buffered bytes.
     *
     * Together with the peer-FIN test above this guarantees the native
     * transport supports the full kqueue / epoll back-pressure contract
     * (no silent payload drain when reads are disabled, peer-close
     * still detected immediately).
     */
    @Test
    fun `data with readEnabled=false stays in kernel buffer — back-pressure`() = runTest {
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            val engine = NettyEngine()
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

                val payload = ByteArray(PAYLOAD_BYTES) { (it and 0xFF).toByte() }
                val outBuf = DefaultAllocator.allocate(PAYLOAD_BYTES)
                for (b in payload) outBuf.writeByte(b)
                serverCh.write(outBuf)
                serverCh.flush()

                delay(SETTLE_MS)

                assertEquals(
                    0,
                    clientBytesReceived,
                    "$clientBytesReceived bytes were delivered to onRead " +
                        "while readEnabled was false. Native transport " +
                        "must keep setAutoRead(false) so kernel rcvbuf " +
                        "retains the bytes for genuine TCP back-pressure.",
                )

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
    }

    private companion object {
        private const val SETTLE_MS = 100L
        private const val EOF_DETECT_TIMEOUT_S = 5
        private const val PAYLOAD_BYTES = 1024
    }
}
