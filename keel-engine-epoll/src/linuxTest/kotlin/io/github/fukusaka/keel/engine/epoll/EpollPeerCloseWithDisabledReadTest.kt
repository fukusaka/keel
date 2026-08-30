package io.github.fukusaka.keel.engine.epoll

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
 * `EpollEventLoop` peer-FIN detection: `onReadClosed` must fire even when the
 * user has never armed read (`readEnabled = false`, no prior `read(...)`
 * call). Mirrors `KqueuePeerCloseWithDisabledReadTest` for the kqueue engine.
 *
 * **Red without fix**: `ReadinessIoTransport` only armed `EPOLLIN` lazily inside
 * its `armRead()` path, which is reached on `readEnabled = true` or the
 * implicit `read(...)` flow. With `readEnabled = false`, the fd is not in
 * epoll's interest list, so `EPOLLHUP` / `EPOLLRDHUP` / `EPOLLERR` is never
 * delivered on peer FIN; `onReadClosed` does not fire and the connection
 * sits in CLOSE-WAIT until the next write attempt or `SO_KEEPALIVE` timer.
 *
 * **Green with fix**: the engine arms `EPOLLIN` at transport construction
 * and surfaces peer-close via the `EV_EOF`-equivalent
 * (`EPOLLHUP|EPOLLERR|EPOLLRDHUP`) flag through the dispatch path, so
 * `onReadClosed` fires within milliseconds of peer FIN regardless of
 * `readEnabled` state.
 */
class EpollPeerCloseWithDisabledReadTest {

    @Test
    fun `peer FIN fires onReadClosed when readEnabled stays false`() = runBlocking {
        val engine = EpollEngine()
        val server = engine.bind(LOOPBACK_HOST, 0)
        val port = (server.localAddress as InetSocketAddress).port

        val client = engine.connect("127.0.0.1", port)
        val serverCh = server.accept()

        try {
            val transport = (client as AbstractPipelinedChannel).transport
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

    /**
     * Back-pressure invariant: when the user keeps `readEnabled = false` and
     * the peer sends data (no FIN), the always-armed `EPOLLIN` fires but the
     * listener must NOT consume the data. The kernel `rcvbuf` retains the
     * bytes; `dispatchReady`'s "no re-register" branch removes the interest
     * so epoll does not busy-loop. Symmetric guarantee to the peer-FIN
     * detection test — mirrors `KqueuePeerCloseWithDisabledReadTest`.
     */
    @Test
    fun `data with readEnabled=false stays in kernel buffer — back-pressure`() = runBlocking {
        val engine = EpollEngine()
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

            val payload = ByteArray(PAYLOAD_BYTES) { (it and 0xFF).toByte() }
            val outBuf = DefaultAllocator.allocate(PAYLOAD_BYTES)
            for (b in payload) outBuf.writeByte(b)
            serverCh.write(outBuf)
            serverCh.flush()

            delay(SETTLE_MS)

            assertFalse(
                clientBytesReceived > 0,
                "back-pressure: $clientBytesReceived bytes were delivered to onRead while " +
                    "readEnabled was false. The always-arm EPOLLIN semantic for EOF detection " +
                    "must not consume payload bytes — the kernel rcvbuf must retain them so TCP " +
                    "applies back-pressure to the peer.",
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
        val engine = EpollEngine()
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
        private const val EOF_DETECT_TIMEOUT_S = 1
        private const val PAYLOAD_BYTES = 1024
    }
}
