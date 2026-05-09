package io.github.fukusaka.keel.engine.iouring

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
 * Verifies that engine-io-uring surfaces peer FIN through
 * [io.github.fukusaka.keel.pipeline.IoTransport.onReadClosed] even when
 * `PipelinedChannel.readEnabled = false` for the entire connection
 * lifetime — the natural shape of a write-only push client,
 * one-direction logger, or monitoring metrics sender.
 *
 * **Failure scenario without the fix**: `IoUringIoTransport` armed
 * multishot recv only when `readEnabled` flipped to `true`. While
 * `readEnabled` stayed `false`, no recv SQE was queued and the kernel
 * had no way to deliver a `res = 0` CQE on FIN; `onReadClosed` did
 * not fire and the connection sat in `CLOSE-WAIT` until either the
 * next write attempt or the `SO_KEEPALIVE` timer expired (default
 * ~2 hours).
 *
 * **Post-fix behaviour**: the engine arms a single-shot
 * `IORING_OP_POLL_ADD` watching for `POLLRDHUP | POLLHUP | POLLERR`
 * (no `POLLIN` — bytes do not trigger the CQE) at transport
 * construction via the `IoTransport.onChannelAttached()` hook (PR #475).
 * The kernel produces one CQE the moment any of those bits becomes
 * set, and `onReadClosed` fires within milliseconds of peer FIN
 * regardless of `readEnabled` state. The fd's receive buffer is left
 * untouched so genuine TCP back-pressure (kernel `rcvbuf` retention)
 * remains effective when the user holds `readEnabled = false`.
 *
 * Equivalent to `engine-kqueue`'s `EVFILT_READ` + `EV_EOF` flag
 * observation and `engine-epoll`'s `epoll_ctl(EPOLL_CTL_ADD,
 * EPOLLRDHUP)` pattern; engine-io-uring achieves the same
 * "peer-close detection without active read" semantics through
 * `IORING_OP_POLL_ADD` rather than honouring [io.github.fukusaka.keel.core.IdleReadPolicy].
 *
 * Red-Green verification:
 * - Red (pre-fix): comment out the [armPollAddForFin] call in
 *   `IoUringIoTransport.onChannelAttached`. The test fails with a
 *   `TimeoutCancellationException` at 1 s because no POLL_ADD SQE is
 *   queued and the kernel never delivers a peer-close CQE.
 * - Green (post-fix): `onReadClosed` fires within ~1 ms of
 *   `serverCh.close()` on loopback.
 */
class IoUringPeerCloseWithDisabledReadTest {

    @Test
    fun `peer FIN fires onReadClosed when readEnabled stays false`() = runBlocking<Unit> {
        val engine = IoUringEngine()
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

            // POLL_ADD CQE fires on POLLRDHUP within ms of FIN.
            withTimeout(EOF_DETECT_TIMEOUT_S.seconds) { closedSignal.await() }
        } finally {
            client.close()
            server.close()
            engine.close()
        }
    }

    /**
     * Back-pressure invariant: when the user keeps `readEnabled =
     * false` and the peer sends data (no FIN), the POLL_ADD watching
     * for `POLLRDHUP | POLLHUP | POLLERR` (no `POLLIN`) does not fire
     * for data. The bytes sit in kernel `rcvbuf` and apply TCP
     * back-pressure to the peer; `onRead` is not invoked because no
     * multishot recv SQE is armed.
     *
     * This is the symmetric guarantee to `peer FIN fires onReadClosed`:
     * the POLL_ADD-based peer-close detection added for io_uring must
     * not silently drain application-visible bytes.
     */
    @Test
    fun `data with readEnabled=false stays in kernel buffer — back-pressure`() = runBlocking<Unit> {
        val engine = IoUringEngine()
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

            // Settle: POLL_ADD watches for POLLRDHUP/POLLHUP/POLLERR
            // and ignores POLLIN, so data does not fire any CQE on the
            // client. No multishot recv SQE is armed because
            // readEnabled = false. Data sits in kernel rcvbuf.
            delay(SETTLE_MS)

            assertFalse(
                clientBytesReceived > 0,
                "$clientBytesReceived bytes were delivered to onRead while readEnabled was " +
                    "false. The POLL_ADD-based peer-close detection must not consume payload " +
                    "bytes — the kernel rcvbuf must retain them so TCP applies back-pressure " +
                    "to the peer.",
            )
            // The "flip readEnabled = true and observe buffered bytes" part of
            // the back-pressure proof is omitted here because the io-uring
            // engine's `readEnabled` setter calls `armRecv` synchronously
            // from the caller thread, but `armRecv` issues an io_uring SQE
            // which must run on the owning EL pthread under SINGLE_ISSUER.
            // That cross-thread issue is independent of POLL_ADD-based FIN
            // detection (it predates this PR) and should be addressed by a
            // separate "dispatch armRecv to EL" fix; the assertion above is
            // already sufficient to prove POLL_ADD does not silently drain
            // bytes — which is the invariant this test is here to guard.
        } finally {
            client.close()
            serverCh.close()
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
