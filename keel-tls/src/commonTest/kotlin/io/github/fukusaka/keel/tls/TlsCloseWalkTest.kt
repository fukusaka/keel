package io.github.fukusaka.keel.tls

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * That closing a channel releases the TLS session on it.
 *
 * [TlsHandler.onClose] is the only `onClose` any production handler in the
 * tree implements, and until a channel's `close()` walked the pipeline nothing
 * reached it: a session was released only by `handlerRemoved`, which nothing
 * removing the handler ever triggered. The check is worth having on its own
 * rather than resting on the channel-level tests, because those use a handler
 * written for them — this one runs the handler that actually holds something.
 */
class TlsCloseWalkTest {

    private val logger = PrintLogger("TlsCloseWalkTest")

    private fun channel(transport: TestIoTransport): PipelinedChannel =
        object : AbstractPipelinedChannel(transport, logger) {}

    /** Records whether the session was released, and does nothing else. */
    private class ClosingCodec : TlsCodec {
        var closeCount: Int = 0

        override val isHandshakeComplete: Boolean get() = true
        override val negotiatedProtocol: String? get() = null
        override val peerCertificates: List<ByteArray> get() = emptyList()

        override fun unprotect(ciphertext: IoBuf, plaintext: IoBuf): TlsCodecResult =
            TlsCodecResult(TlsResult.OK, 0, 0)

        override fun protect(plaintext: IoBuf, ciphertext: IoBuf): TlsCodecResult =
            TlsCodecResult(TlsResult.OK, 0, 0)

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

        // The walk is asked for once per channel. A codec is documented
        // idempotent, and every backend in the tree does guard itself, but the
        // pipeline should not be leaning on that.
        assertTrue(codec.closeCount == 1, "released once, got ${codec.closeCount}")
    }
}
