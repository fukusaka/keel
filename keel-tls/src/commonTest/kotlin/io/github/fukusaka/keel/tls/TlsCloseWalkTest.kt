package io.github.fukusaka.keel.tls

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import io.github.fukusaka.keel.pipeline.InboundHandler
import io.github.fukusaka.keel.pipeline.OutboundHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * That closing a channel releases the TLS session on it, and releases it
 * where the handler's own frames cannot still be using it.
 *
 * The session is released by `handlerRemoved`, which the end of a channel's
 * life runs for every handler, once, outside any handler callback. It used to
 * be released by [TlsHandler.onClose] too, and the close walk that runs is
 * synchronous: a handler below the TLS handler closing the channel from its
 * `onRead` ran that `onClose` from inside the TLS handler's own decrypt loop,
 * which then decrypted the next record of the same read with a freed session.
 * The check is worth having on its own rather than resting on the
 * channel-level tests, because those use a handler written for them — this
 * one runs the handler that actually holds something.
 */
class TlsCloseWalkTest {

    private val logger = PrintLogger("TlsCloseWalkTest")

    private fun channel(transport: TestIoTransport): PipelinedChannel =
        object : AbstractPipelinedChannel(transport, logger) {}

    /**
     * Records whether the session was released, and whether it was asked to
     * decrypt after that. Decrypts [recordSize] bytes of ciphertext into as
     * many bytes of plaintext per call, so one read can carry two records.
     */
    private class ClosingCodec(private val recordSize: Int = 0, private val handshakeRecords: Int = 0) : TlsCodec {
        var closeCount: Int = 0
        var unprotectAfterClose: Int = 0
        private var records: Int = 0

        /** Complete once the handshake records have been consumed, so the completion event is raised mid-read. */
        override val isHandshakeComplete: Boolean get() = records >= handshakeRecords
        override val negotiatedProtocol: String? get() = null
        override val peerCertificates: List<ByteArray> get() = emptyList()

        override fun unprotect(ciphertext: IoBuf, plaintext: IoBuf): TlsCodecResult {
            if (closeCount > 0) unprotectAfterClose++
            records++
            if (records <= handshakeRecords) {
                // A handshake record: consumed, no plaintext.
                return TlsCodecResult(TlsResult.OK, minOf(recordSize, ciphertext.readableBytes), 0)
            }
            val n = minOf(recordSize, ciphertext.readableBytes)
            // The handler advances the reader by `bytesConsumed`; the codec only reads.
            repeat(n) { plaintext.writeByte(ciphertext.getByte(ciphertext.readerIndex + it)) }
            return TlsCodecResult(TlsResult.OK, n, n)
        }

        var protectAfterClose: Int = 0

        override fun protect(plaintext: IoBuf, ciphertext: IoBuf): TlsCodecResult {
            if (closeCount > 0) protectAfterClose++
            val n = minOf(recordSize, plaintext.readableBytes)
            repeat(n) { ciphertext.writeByte(plaintext.getByte(plaintext.readerIndex + it)) }
            return TlsCodecResult(TlsResult.OK, n, n)
        }

        override fun close() {
            closeCount++
        }
    }

    @Test
    fun `closing a channel releases the TLS session on it`() {
        val transport = TestIoTransport()
        val channel = channel(transport)
        val codec = ClosingCodec()
        channel.pipeline.addLast("tls", TlsHandler(codec))

        channel.close()

        assertTrue(
            codec.closeCount > 0,
            "the session is released when the connection carrying it is closed — before the walk " +
                "existed nothing reached this handler and the session outlived the channel",
        )
        assertFalse(transport.isOpen)
    }

    @Test
    fun `closing twice releases it once`() {
        val transport = TestIoTransport()
        val channel = channel(transport)
        val codec = ClosingCodec()
        channel.pipeline.addLast("tls", TlsHandler(codec))

        channel.close()
        channel.close()

        // `handlerRemoved` runs once per handler. A codec is documented
        // idempotent, and every backend in the tree does guard itself, but the
        // pipeline should not be leaning on that.
        assertTrue(codec.closeCount == 1, "released once, got ${codec.closeCount}")
    }

    @Test
    fun `a handler below that removes the TLS handler from the handshake event stops the decrypt loop`() {
        val tracker = TrackingAllocator()
        val transport = TestIoTransport(tracker)
        val channel = channel(transport)
        val codec = ClosingCodec(recordSize = 4, handshakeRecords = 1)
        channel.pipeline.addLast("tls", TlsHandler(codec))
        channel.pipeline.addLast(
            "swap",
            object : InboundHandler {
                override fun onUserEvent(ctx: PipelineHandlerContext, event: Any) {
                    // A protocol switch on handshake completion, removing the
                    // TLS stage from inside the event it raised.
                    if (event is TlsHandshakeComplete) ctx.pipeline.remove("tls")
                }

                override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
                    (msg as IoBuf).release()
                }
            },
        )
        val twoRecords = tracker.allocate(8).also { repeat(8) { i -> it.writeByte(i.toByte()) } }

        channel.pipeline.notifyRead(twoRecords)

        assertEquals(0, codec.unprotectAfterClose, "nothing is decrypted after the session was released")
        assertEquals(1, codec.closeCount)
        assertEquals(0, tracker.outstandingCount)
    }

    @Test
    fun `a handler below that removes the TLS handler from a write's error stops the encrypt loop`() {
        val tracker = TrackingAllocator()
        val transport = TestIoTransport(tracker)
        val channel = channel(transport)
        val codec = ClosingCodec(recordSize = 4)
        channel.pipeline.addFirst(
            "faulty",
            object : OutboundHandler {
                override fun onWrite(ctx: PipelineHandlerContext, msg: Any) {
                    (msg as IoBuf).release()
                    throw IllegalStateException("cannot write")
                }
            },
        )
        channel.pipeline.addLast("tls", TlsHandler(codec))
        channel.pipeline.addLast(
            "swap",
            object : InboundHandler {
                override fun onError(ctx: PipelineHandlerContext, cause: Throwable) {
                    ctx.pipeline.remove("tls")
                }
            },
        )
        val twoRecords = tracker.allocate(8).also { repeat(8) { i -> it.writeByte(i.toByte()) } }

        channel.pipeline.requestWrite(twoRecords)

        assertEquals(0, codec.protectAfterClose, "nothing is encrypted after the session was released")
        assertEquals(1, codec.closeCount)
        assertEquals(0, tracker.outstandingCount)
    }

    @Test
    fun `a handler below that closes the channel from a read does not have the next record decrypted by a freed session`() {
        val tracker = TrackingAllocator()
        val transport = TestIoTransport(tracker)
        val channel = channel(transport)
        val codec = ClosingCodec(recordSize = 4)
        val delivered = mutableListOf<Int>()
        channel.pipeline.addLast("tls", TlsHandler(codec))
        channel.pipeline.addLast(
            "closer",
            object : InboundHandler {
                override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
                    val buf = msg as IoBuf
                    delivered.add(buf.readableBytes)
                    buf.release()
                    // The shape of a server that drains: it answers the first
                    // request and closes, while the same read still holds the
                    // pipelined second one.
                    ctx.channel.close()
                }
            },
        )
        val twoRecords = tracker.allocate(8).also { repeat(8) { i -> it.writeByte(i.toByte()) } }

        channel.pipeline.notifyRead(twoRecords)

        assertEquals(0, codec.unprotectAfterClose, "the second record was decrypted before the session was released")
        assertEquals(1, codec.closeCount, "released once, by the end of the channel's life")
        assertEquals(listOf(4, 4), delivered)
        assertEquals(0, tracker.outstandingCount)
        assertFalse(transport.isOpen)
    }
}
