package io.github.fukusaka.keel.server.websocket

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.codec.websocket.WsFrame
import io.github.fukusaka.keel.codec.websocket.WsFrameEncoder
import io.github.fukusaka.keel.codec.websocket.WsOpcode
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import io.github.fukusaka.keel.pipeline.SuspendMessageBridge
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * Leak-discipline tests for [WsSession.onMessage] — the scoped, leak-safe
 * receive helper that auto-releases a [WsMessage.BinaryChunks]' pooled chunks
 * after the block, suppressed when the block forwards the message via [send].
 *
 * Drives a real [WsSessionImpl] over a [TestIoTransport] backed by a
 * [TrackingAllocator]: a pooled-binary [WsFrame] (the decoder fast-path shape)
 * is fed through the bridge, the pump assembles it into a `BinaryChunks`, and
 * the test asserts alloc/release balance after `onMessage` either drops or
 * echoes it.
 *
 * **jvmTest, not commonTest**: needs `runBlocking` to drive the pump and the
 * `onMessage` consumer as concurrent coroutines (no portable commonMain
 * blocking builder).
 */
class WsSessionOnMessageTest {

    private class Fixture {
        val tracker = TrackingAllocator(DefaultAllocator)
        val transport = TestIoTransport(tracker)
        val channel = object : AbstractPipelinedChannel(transport, PrintLogger("onmsg")) {}
        val bridge = SuspendMessageBridge(WsFrame::class, releaseUndelivered = { it.inboundPayload?.release() })
        val session: WsSessionImpl

        init {
            // Outbound encoder (so send() serialises frames into IoBufs the
            // transport captures) + the inbound bridge the pump consumes,
            // mirroring addWsServerCodec's ordering.
            channel.pipeline.addLast("ws-encoder", WsFrameEncoder())
            channel.pipeline.addLast("bridge", bridge)
            session = WsSessionImpl(channel, bridge, emptyMap(), null)
        }

        /** Feed one complete pooled BINARY data frame (the decoder fast-path output shape). */
        fun feedPooledBinary(payload: ByteArray) {
            val buf = tracker.allocate(payload.size).apply { writeByteArray(payload, 0, payload.size) }
            channel.pipeline.notifyRead(WsFrame(fin = true, opcode = WsOpcode.BINARY, inboundPayload = buf))
        }

        /** Feed a CLOSE control frame so the pump breaks and runs its teardown. */
        fun feedClose() {
            channel.pipeline.notifyRead(WsFrame(fin = true, opcode = WsOpcode.CLOSE, payload = ByteArray(0)))
        }

        fun bytesOf(message: WsMessage): ByteArray {
            val chunks = assertIs<WsMessage.BinaryChunks>(message).chunks
            val out = ByteArray(chunks.totalSize)
            var offset = 0
            chunks.forEach { c ->
                val n = c.readableBytes
                c.readByteArray(out, offset, n)
                offset += n
            }
            return out
        }

        /** Release the transport's captured outbound buffers, then assert balance. */
        fun assertBalanced() {
            transport.close()
            assertEquals(
                0,
                tracker.outstandingCount,
                "IoBuf leak (alloc=${tracker.allocateCount} release=${tracker.releaseCount})",
            )
        }
    }

    @Test
    fun `onMessage auto-releases the pooled chunks of a processed message`() = runBlocking {
        withTimeout(5.seconds) {
            val f = Fixture()
            val pump = launch { f.session.runForward() }
            val payload = byteArrayOf(1, 2, 3, 4)
            f.feedPooledBinary(payload)

            var received: ByteArray? = null
            f.session.onMessage { message ->
                received = f.bytesOf(message)
                // Do NOT send — onMessage must release the chunks after the block.
                f.session.close()
            }
            pump.cancelAndJoin()

            assertContentEquals(payload, received)
            f.assertBalanced()
        }
    }

    @Test
    fun `pump teardown does not steal an application message not yet consumed`() = runBlocking {
        withTimeout(5.seconds) {
            val f = Fixture()
            val pump = launch { f.session.runForward() }
            // The pump processes the data frame (-> applicationFrames) and then
            // CLOSE (-> break -> finally) before any consumer drains incoming.
            // runForward's finally must NOT drain applicationFrames -- the
            // handler is its legitimate consumer -- so the message stays
            // receivable. (Regression: an earlier finally drained here and stole
            // the buffered echo messages on slower runners.)
            f.feedPooledBinary(byteArrayOf(1, 2, 3))
            f.feedClose()
            pump.join()

            val received = f.session.incoming.tryReceive().getOrNull()
            val message = assertIs<WsMessage.BinaryChunks>(received)
            assertContentEquals(byteArrayOf(1, 2, 3), f.bytesOf(message))
            message.chunks.release()
            f.assertBalanced()
        }
    }

    @Test
    fun `onMessage echoing a pooled message via send does not double-release`() = runBlocking {
        withTimeout(5.seconds) {
            val f = Fixture()
            val pump = launch { f.session.runForward() }
            val payload = byteArrayOf(5, 6, 7, 8, 9)
            f.feedPooledBinary(payload)

            f.session.onMessage { message ->
                // Echo via send: ownership of the chunks transfers to the
                // transport, so onMessage must NOT release them. A failure of
                // the suppression would double-release and crash the test.
                f.session.send(message)
                f.session.close()
            }
            pump.cancelAndJoin()

            // The echoed chunk (plus header + CLOSE wire bufs) are captured in
            // the transport's `written`; releasing them there is the single
            // release of the pooled chunk.
            f.assertBalanced()
        }
    }
}
