package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.seconds

/**
 * Verifies the kqueue engine surfaces peer-FIN to user code via
 * [io.github.fukusaka.keel.pipeline.IoTransport.onReadClosed] even when the
 * application keeps `PipelinedChannel.readEnabled = false` for the entire
 * connection lifetime — the natural shape of a write-only push client,
 * one-direction logger, or monitoring metrics sender.
 *
 * **Failure scenario without the fix**: `ReadinessIoTransport` only armed
 * `EVFILT_READ` lazily inside its `armRead()` path, which is reached on
 * `readEnabled = true` or the implicit `read(...)` flow. When `readEnabled`
 * stayed `false`, `EVFILT_READ` was never registered, so kqueue delivered
 * no event on peer FIN; `onReadClosed` did not fire and the connection sat
 * in CLOSE-WAIT until either the next write attempt failed (`EPIPE`) or
 * the `SO_KEEPALIVE` timer declared the peer dead (default ~2 hours).
 *
 * **Post-fix behaviour**: the engine arms `EVFILT_READ` at transport
 * construction and surfaces `EV_EOF` flagged events through a separate
 * `FdReadyListener.onPeerClosed` callback so `onReadClosed` fires within
 * milliseconds of peer FIN, regardless of `readEnabled` state.
 *
 * Uses public APIs only: `engine.connect`, `server.accept`,
 * `transport.onReadClosed`, `transport.readEnabled`. `SO_KEEPALIVE` is
 * deliberately not enabled — it would shift detection to the keep-alive
 * timer rather than fix the engine-side gap, and `SO_KEEPALIVE` is the
 * appropriate safety net for ungraceful disconnects (peer crash / network
 * partition) where no FIN is delivered, not for the graceful close that
 * this test exercises.
 *
 * Red-Green verification:
 * - Red (pre-fix): `withTimeout(1.seconds) { closedSignal.await() }`
 *   throws `TimeoutCancellationException`.
 * - Green (post-fix): the await resumes within ~1 ms of `serverCh.close()`.
 */
class KqueuePeerCloseWithDisabledReadTest {

    @Test
    fun `peer FIN fires onReadClosed when readEnabled stays false`() = runBlocking {
        val engine = KqueueEngine()
        val server = engine.bind(LOOPBACK_HOST, 0)
        val port = (server.localAddress as InetSocketAddress).port

        val client = engine.connect("127.0.0.1", port)
        val serverCh = server.accept()

        try {
            val transport = (client as AbstractPipelinedChannel).transport
            // Public API contract: a write-only push client may keep
            // readEnabled = false for the entire connection lifetime.
            // Setting readEnabled = false is a no-op (already false), but
            // it makes the contract explicit.
            transport.readEnabled = false

            val closedSignal = CompletableDeferred<Unit>()
            transport.onReadClosed = { closedSignal.complete(Unit) }

            // Settle the connection establishment before triggering the FIN
            // so the test failure mode is unambiguously about post-establish
            // peer-close detection and not a race on accept handshake.
            delay(SETTLE_MS)

            // Peer (server-side) closes — sends FIN to client.
            serverCh.close()

            // Pre-fix: kqueue has no EVFILT_READ registered for the client
            // fd, so no event fires; closedSignal.await() times out.
            // Post-fix: the engine arms EVFILT_READ at construction and
            // dispatches EV_EOF through onPeerClosed, so onReadClosed
            // fires within ms of the FIN.
            withTimeout(EOF_DETECT_TIMEOUT_S.seconds) { closedSignal.await() }
        } finally {
            client.close()
            server.close()
            engine.close()
        }
    }

    /**
     * Back-pressure invariant: when the user keeps `readEnabled = false`
     * and the peer sends data (no FIN), the always-armed `EVFILT_READ`
     * fires but the listener must NOT consume the data. The kernel `rcvbuf`
     * retains the bytes and applies TCP back-pressure to the peer; the wake
     * re-registers narrowed — an arm the peer's FIN wakes and arriving data
     * does not — so the engine neither busy-loops on the unread bytes nor
     * gives up the close notification.
     *
     * This is the symmetric guarantee to `peer FIN fires onReadClosed`:
     * the always-arm semantic added for peer-close detection must not
     * silently drain application-visible bytes.
     *
     * Verification: `onRead` is registered to count callback invocations.
     * The peer sends 1 KB. We wait briefly. The counter must remain at 0,
     * because `readEnabled` is `false`. Only when the user flips
     * `readEnabled = true` does the data get delivered.
     */
    @Test
    fun `data with readEnabled=false stays in kernel buffer — back-pressure`() = runBlocking {
        val engine = KqueueEngine()
        val server = engine.bind(LOOPBACK_HOST, 0)
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

            // Settle: kqueue should fire (data ready) but onReadable returns
            // without consuming because readEnabled is false. The filter is
            // removed by dispatchReady's no-register branch. Data sits in
            // kernel rcvbuf.
            delay(SETTLE_MS)

            assertFalse(
                clientBytesReceived > 0,
                "$clientBytesReceived bytes were delivered to onRead while readEnabled was " +
                    "false. The always-arm EVFILT_READ semantic for peer-close detection must " +
                    "not consume payload bytes — the kernel rcvbuf must retain them so TCP " +
                    "applies back-pressure to the peer.",
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

    /**
     * The sequence the always-armed guarantee used to lose: **data first, then
     * the close.**
     *
     * The two cases above cover a peer that only closes, and a peer that only
     * sends. Between them sits the one that does both, and it was the gap: the
     * arm is one-shot, so the data wake consumed it, and a connection with
     * reads disabled had no way to ask for another — only `readEnabled = true`
     * re-armed. The interest was withdrawn and the close that came afterwards
     * reached nobody, leaving a write-only client in CLOSE-WAIT until the
     * keep-alive timer (~2 hours).
     *
     * The back-pressure path now re-registers narrowed instead of going quiet,
     * so this asks the kernel to honour that: quiet while the unread bytes sit
     * there, awake on the FIN behind them.
     *
     * Red-Green: without the narrowed re-arm the await below times out; with
     * it the signal arrives in about a millisecond on loopback.
     */
    @Test
    fun `peer FIN fires onReadClosed after unread data has already woken the connection`() = runBlocking {
        val engine = KqueueEngine()
        val server = engine.bind(LOOPBACK_HOST, 0)
        val port = (server.localAddress as InetSocketAddress).port

        val client = engine.connect(LOOPBACK_HOST, port)
        val serverCh = server.accept()

        try {
            val transport = (client as AbstractPipelinedChannel).transport
            transport.readEnabled = false

            var clientBytesReceived = 0
            transport.onRead = { buf ->
                clientBytesReceived += buf.readableBytes
                buf.release()
            }
            val closedSignal = CompletableDeferred<Unit>()
            transport.onReadClosed = { closedSignal.complete(Unit) }

            // Send first, and let it be delivered and declined. This is the
            // wake that used to spend the connection's only registration.
            val outBuf = DefaultAllocator.allocate(PAYLOAD_BYTES)
            for (i in 0 until PAYLOAD_BYTES) outBuf.writeByte((i and 0xFF).toByte())
            serverCh.write(outBuf)
            serverCh.flush()
            delay(SETTLE_MS)

            assertEquals(
                0,
                clientBytesReceived,
                "precondition: back-pressure still holds, so the bytes are in the kernel buffer and " +
                    "the wake that delivered them was declined",
            )

            // Now the close, on a connection whose data wake has already been
            // spent. Small enough that the receive buffer is nowhere near full,
            // so the FIN can actually be delivered -- a peer that filled it
            // could not send one at all, which is TCP's limit rather than the
            // engine's.
            serverCh.close()

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
         * Generous outer cap for the peer-close detection — after the fix
         * the actual delay should be sub-millisecond on loopback, so a 1 s
         * cap is purely a safety net against a regressed implementation
         * that re-introduces the bug.
         */
        private const val EOF_DETECT_TIMEOUT_S = 1

        /** Test payload size — small enough to fit in any reasonable rcvbuf. */
        private const val PAYLOAD_BYTES = 1024
    }
}
