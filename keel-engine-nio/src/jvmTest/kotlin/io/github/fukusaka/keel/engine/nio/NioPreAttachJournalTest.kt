package io.github.fukusaka.keel.engine.nio

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.core.IdleReadPolicy
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Integration test for the [DefaultPipeline] pre-attach event journal
 * + dispatcher-tick drain interaction with
 * [IdleReadPolicy.DETECT_PEER_CLOSE] on `engine-nio`.
 *
 * **Scenario** (engine-driven race that the journal closes):
 *
 * 1. Server uses `IdleReadPolicy.DETECT_PEER_CLOSE`, so accepted
 *    channels arm `OP_READ` at construction. Java NIO `Selector` then
 *    fires a readable event for any inbound bytes regardless of the
 *    user's `readEnabled` state.
 * 2. Peer (client) writes data immediately after the connection is
 *    established — *before* the server-side test code calls
 *    `serverCh.read(buf)`. The selector fires, `NioIoTransport`
 *    consumes the bytes via `SocketChannel.read`, and delivers them
 *    through `transport.onRead → pipeline.notifyRead(buf)`.
 * 3. At this point the server-side pipeline has no user
 *    [io.github.fukusaka.keel.pipeline.InboundHandler] installed
 *    (the user has not yet called `read()` which would lazily install
 *    [io.github.fukusaka.keel.pipeline.SuspendBridgeHandler] via
 *    `ensureBridge`). Without the journal the bytes would reach
 *    `TailHandler.onRead` and be released with a `WARN` log; the
 *    subsequent `serverCh.read(buf)` would time out waiting for data
 *    that has already been discarded.
 * 4. With the journal in place, `notifyRead(buf)` is captured and
 *    replayed once the first user `addX` schedules the
 *    dispatcher-tick drain — which is exactly what `ensureBridge`'s
 *    `pipeline.addLast(SuspendBridgeHandler)` triggers.
 *
 * **Red-Green verification**: revert the journal additions in
 * `DefaultPipeline.notifyRead` (so the pre-attach branch invokes
 * `head.invokeOnRead` directly) and the test fails with a 3 s timeout
 * because the bytes are released by `TailHandler` before
 * `serverCh.read` runs. With the journal restored the test passes.
 */
class NioPreAttachJournalTest {

    @Test
    fun `peer write before serverCh read is preserved by the pre-attach journal under DETECT_PEER_CLOSE`() = runBlocking<Unit> {
        val engine = NioEngine(
            IoEngineConfig(idleReadPolicy = IdleReadPolicy.DETECT_PEER_CLOSE),
        )
        val server = engine.bind(LOOPBACK_HOST, 0)
        val port = (server.localAddress as InetSocketAddress).port

        val client = engine.connect("127.0.0.1", port)
        val serverCh = server.accept()

        try {
            // Settle the accept handshake so the failure mode is
            // unambiguously about post-establish data race, not the
            // accept path itself.
            delay(SETTLE_MS)

            // Peer writes data BEFORE the server-side test code calls
            // serverCh.read. With DETECT_PEER_CLOSE on engine-nio,
            // OP_READ is armed at construction so the selector fires
            // and SocketChannel.read consumes the bytes immediately.
            // The bytes flow through transport.onRead →
            // pipeline.notifyRead, where the pre-attach journal
            // captures them.
            val payload = "early-payload"
            val outBuf = DefaultAllocator.allocate(payload.length)
            for (b in payload.encodeToByteArray()) outBuf.writeByte(b)
            client.write(outBuf)
            client.flush()

            // Give the OP_READ -> notifyRead -> journal-buffer cycle time
            // to complete before we install the SuspendBridgeHandler
            // via serverCh.read.
            delay(SETTLE_MS)

            // First read on the server: ensureBridge installs
            // SuspendBridgeHandler (an InboundHandler) → schedules
            // drain on the engine's NioEventLoop. The drain replays
            // the buffered notifyRead through the bridge, which
            // resumes this read with the buffered bytes.
            val readBuf = DefaultAllocator.allocate(payload.length)
            val n = withTimeout(IO_TIMEOUT_S.seconds) { serverCh.read(readBuf) }
            assertEquals(payload.length, n)
            assertEquals(
                payload,
                buildString {
                    repeat(n) { append(readBuf.readByte().toInt().toChar()) }
                },
            )
            readBuf.release()
        } finally {
            client.close()
            serverCh.close()
            server.close()
            engine.close()
        }
    }

    private companion object {
        private const val SETTLE_MS = 100L

        /** Generous outer cap — drain runs sub-millisecond on loopback after fix. */
        private const val IO_TIMEOUT_S = 3
    }
}
