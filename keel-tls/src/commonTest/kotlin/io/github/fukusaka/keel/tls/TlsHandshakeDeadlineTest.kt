package io.github.fukusaka.keel.tls

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import io.github.fukusaka.keel.pipeline.Pipeline
import io.github.fukusaka.keel.pipeline.TimerHandle
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [TlsHandler]'s handshake deadline, driven deterministically: the
 * test channel's [scheduleDeadline] override captures each scheduled task so the
 * deadline can be "fired" by invoking the captured task. No wall-clock involved.
 */
class TlsHandshakeDeadlineTest {

    private class FakeTimerHandle : TimerHandle {
        var cancelled = false
        override fun touch() = Unit
        override fun cancel() { cancelled = true }
    }

    private class Scheduled(val millis: Long, val task: () -> Unit, val handle: FakeTimerHandle)

    private val transport = TestIoTransport()
    private val scheduled = mutableListOf<Scheduled>()

    private val channel = object : AbstractPipelinedChannel(transport, PrintLogger("tls-test")) {
        override fun scheduleDeadline(delayMillis: Long, task: () -> Unit): TimerHandle =
            FakeTimerHandle().also { scheduled.add(Scheduled(delayMillis, task, it)) }
    }

    /**
     * Minimal codec: consumes all ciphertext, produces no plaintext, and reports the
     * handshake complete only once [completeOnUnprotect] is set — enough to exercise
     * the deadline's arm / disarm without a real TLS state machine.
     */
    private class FakeTlsCodec : TlsCodec {
        var completeOnUnprotect = false
        override var isHandshakeComplete: Boolean = false
            private set
        override val negotiatedProtocol: String? = "http/1.1"
        override val peerCertificates: List<ByteArray> = emptyList()

        override fun unprotect(ciphertext: IoBuf, plaintext: IoBuf): TlsCodecResult {
            val n = ciphertext.readableBytes
            if (n == 0) return TlsCodecResult(TlsResult.NEED_MORE_INPUT, 0, 0)
            return if (completeOnUnprotect) {
                isHandshakeComplete = true
                TlsCodecResult(TlsResult.OK, n, 0)
            } else {
                // Consume the bytes but stay mid-handshake (no completion).
                TlsCodecResult(TlsResult.NEED_MORE_INPUT, n, 0)
            }
        }

        override fun protect(plaintext: IoBuf, ciphertext: IoBuf): TlsCodecResult =
            TlsCodecResult(TlsResult.OK, plaintext.readableBytes, 0)

        override fun close() = Unit
    }

    private fun pipelineWith(codec: TlsCodec, timeoutMillis: Long): Pipeline {
        val p = channel.pipeline
        p.addLast("tls", TlsHandler(codec, handshakeTimeoutMillis = timeoutMillis))
        return p
    }

    private fun bufOf(text: String): IoBuf {
        val bytes = text.encodeToByteArray()
        val buf = DefaultAllocator.allocate(bytes.size)
        buf.writeByteArray(bytes, 0, bytes.size)
        return buf
    }

    @Test
    fun `the first inbound record arms the handshake deadline with the configured timeout`() {
        pipelineWith(FakeTlsCodec(), HANDSHAKE_TIMEOUT).notifyRead(bufOf("clienthello"))
        assertEquals(
            listOf(HANDSHAKE_TIMEOUT),
            scheduled.map { it.millis },
            "the first record should arm exactly the handshake deadline",
        )
        assertFalse(transport.closed, "arming must not close the channel")
    }

    @Test
    fun `a completed handshake disarms the deadline`() {
        val codec = FakeTlsCodec().apply { completeOnUnprotect = true }
        pipelineWith(codec, HANDSHAKE_TIMEOUT).notifyRead(bufOf("clienthello"))
        assertTrue(scheduled.single().handle.cancelled, "a completed handshake must cancel the deadline")
        assertFalse(transport.closed)
    }

    @Test
    fun `the handshake deadline elapsing force-closes the channel`() {
        pipelineWith(FakeTlsCodec(), HANDSHAKE_TIMEOUT).notifyRead(bufOf("clienthello"))
        scheduled.single().task.invoke()
        assertTrue(transport.closed, "an elapsed handshake deadline must force-close the stalled peer")
    }

    @Test
    fun `a zero timeout arms no deadline`() {
        pipelineWith(FakeTlsCodec(), timeoutMillis = 0).notifyRead(bufOf("clienthello"))
        assertTrue(scheduled.isEmpty(), "a disabled deadline must not schedule anything")
        assertFalse(transport.closed)
    }

    @Test
    fun `the deadline is armed at most once across multiple records`() {
        val p = pipelineWith(FakeTlsCodec(), HANDSHAKE_TIMEOUT)
        p.notifyRead(bufOf("first"))
        p.notifyRead(bufOf("second"))
        assertEquals(1, scheduled.size, "the absolute deadline is armed once, not re-armed per record")
    }

    @Test
    fun `a missing engine timer disables enforcement without closing`() {
        // A plain channel whose transport wires no EventLoop timer: scheduleDeadline
        // returns null, so the deadline cannot be armed. The handler must degrade
        // gracefully (no crash, no force-close).
        val plainTransport = TestIoTransport()
        val plainChannel = object : AbstractPipelinedChannel(plainTransport, PrintLogger("tls-test")) {}
        plainChannel.pipeline.addLast("tls", TlsHandler(FakeTlsCodec(), handshakeTimeoutMillis = HANDSHAKE_TIMEOUT))
        plainChannel.pipeline.notifyRead(bufOf("clienthello"))
        assertFalse(plainTransport.closed, "an unenforceable deadline must not close the connection")
    }

    @Test
    fun `removing the handler cancels a pending deadline`() {
        val p = pipelineWith(FakeTlsCodec(), HANDSHAKE_TIMEOUT)
        p.notifyRead(bufOf("clienthello"))
        p.remove("tls")
        assertTrue(scheduled.single().handle.cancelled, "handler removal must cancel a still-armed deadline")
    }

    private companion object {
        const val HANDSHAKE_TIMEOUT = 10_000L
    }
}
