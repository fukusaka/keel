package io.github.fukusaka.keel.engine.nodejs

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
 * Verifies the Node.js engine surfaces peer FIN to user code via
 * [io.github.fukusaka.keel.pipeline.IoTransport.onReadClosed] even when the
 * application keeps `PipelinedChannel.readEnabled = false` for the entire
 * connection lifetime — the natural shape of a write-only push client,
 * one-direction logger, or monitoring metrics sender.
 *
 * **Failure scenario without the fix**: `NodeIoTransport` registered the
 * `'end'` and `'error'` listeners lazily inside `armRead()`, which was
 * reached only on `readEnabled = true` or the implicit `read(...)` flow.
 * With `readEnabled = false`, no listener was attached to the Node.js
 * stream's lifecycle events; when the peer closed gracefully, Node.js
 * emitted `'end'` to a registered-listener-less event and the signal was
 * silently lost. `onReadClosed` did not fire and the connection sat in
 * CLOSE-WAIT until either the next write attempt failed (`EPIPE`) or the
 * `SO_KEEPALIVE` timer declared the peer dead.
 *
 * **Post-fix behaviour**: the engine registers `'end'` and `'error'`
 * listeners at transport construction. Node.js delivers these events to
 * registered listeners regardless of stream paused / flowing state, so
 * peer FIN fires `onReadClosed` within milliseconds, independent of
 * `readEnabled` state. The `'data'` listener stays bound to
 * `readEnabled` (attached only when true) so genuine TCP back-pressure
 * is preserved (kernel `rcvbuf` retains bytes when the user has
 * disabled reads).
 *
 * Red-Green verification:
 * - Red (pre-fix): `withTimeout(1.seconds) { closedSignal.await() }`
 *   throws `TimeoutCancellationException`.
 * - Green (post-fix): the await resumes within ~1 ms of `serverCh.close()`.
 */
class NodePeerCloseWithDisabledReadTest {

    @Test
    fun peerFinFiresOnReadClosedWhenReadEnabledStaysFalse() = runTest {
        // Real-time wall-clock context: Node.js stream events ('end' /
        // 'error') fire on the libuv event loop in real time, but
        // [runTest] uses a virtual [TestCoroutineScheduler] where
        // [delay] / [withTimeout] advance virtual time without ever
        // observing real-time wakeups. Without this `withContext`, the
        // `withTimeout(2.seconds)` below resolves at virtual t=2s with a
        // `TimeoutCancellationException` *before* the real-time 'end'
        // event has had a chance to fire. The `Dispatchers.Default`
        // limited-parallelism dispatcher restores wall-clock semantics
        // for the test body.
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            val engine = NodeEngine()
            val server = engine.bind("127.0.0.1", 0)
            val port = (server.localAddress as InetSocketAddress).port

            val client = engine.connect("127.0.0.1", port)
            val serverCh = server.accept()

            try {
                val transport = (client as AbstractPipelinedChannel).transport
                // Public API contract: a write-only push client may keep
                // readEnabled = false for the entire connection lifetime.
                // Setting readEnabled = false is a no-op (already false),
                // but it makes the contract explicit.
                transport.readEnabled = false

                val closedSignal = CompletableDeferred<Unit>()
                transport.onReadClosed = { closedSignal.complete(Unit) }

                // Settle the connection establishment before triggering FIN
                // so the test failure mode is unambiguously about
                // post-establish peer-close detection and not a race on
                // accept handshake.
                delay(SETTLE_MS)

                // Peer (server-side) closes — Node.js emits 'end' on the
                // client.
                serverCh.close()

                // Pre-fix: 'end' listener never registered (readEnabled =
                // false → armRead() never called) → 'end' silently lost.
                // Post-fix: 'end' listener registered at construction →
                // onReadClosed fires within ~1 ms on loopback.
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
     * and the peer sends data (no FIN), the `'data'` listener must NOT be
     * attached, so Node.js leaves the stream in paused mode and the
     * kernel `rcvbuf` retains the bytes. No `onRead` invocation occurs;
     * flipping `readEnabled = true` resumes flowing mode and delivers
     * the previously-buffered bytes.
     *
     * This is the symmetric guarantee to the peer-FIN-with-disabled-read
     * test: the always-registered `'end'` / `'error'` listeners must not
     * imply that data also flows when reads are disabled.
     */
    @Test
    fun dataWithReadEnabledFalseStaysInKernelBuffer() = runTest {
        // See peerFinFiresOnReadClosedWhenReadEnabledStaysFalse for the
        // [runTest] virtual-time / real-time mismatch rationale.
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            val engine = NodeEngine()
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

                // Settle: Node.js stream stays in paused mode (no 'data'
                // listener attached because readEnabled = false). The
                // kernel rcvbuf retains the bytes — onRead must NOT be
                // invoked.
                delay(SETTLE_MS)

                assertEquals(
                    0,
                    clientBytesReceived,
                    "$clientBytesReceived bytes were delivered to onRead " +
                        "while readEnabled was false. The 'data' listener " +
                        "attach/detach tied to readEnabled must keep the " +
                        "stream paused so kernel rcvbuf retains bytes for " +
                        "genuine TCP back-pressure.",
                )

                // Sanity: flipping readEnabled to true re-attaches the
                // 'data' listener, Node.js transitions to flowing mode,
                // and the buffered bytes are delivered.
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
        private const val EOF_DETECT_TIMEOUT_S = 2
        private const val PAYLOAD_BYTES = 1024
    }
}
