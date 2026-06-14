package io.github.fukusaka.keel.tls

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [TlsHandler]'s `plaintextBufferSize` constructor parameter is the size
 * the downstream codec sees as its "recv segment" on a TLS connection.
 * Verifies that the parameter actually flows to the per-record
 * `allocator.allocate(...)` call (the surface that determines the codec's
 * effective segment size) and that the
 * [TlsHandler.requireValidPlaintextBufferSize] invariant is enforced at
 * construction.
 */
class TlsHandlerPlaintextBufferSizeTest {

    /** Records every `allocate(capacity)` call made via this allocator. */
    private class RecordingAllocator(private val delegate: BufferAllocator) : BufferAllocator {
        val allocateSizes: MutableList<Int> = mutableListOf()

        @Suppress("IoBufLeak") // Returns ownership to caller, like the delegate.
        override fun allocate(capacity: Int): IoBuf {
            allocateSizes.add(capacity)
            return delegate.allocate(capacity)
        }

        override fun wrapBytes(bytes: ByteArray, offset: Int, length: Int): IoBuf? =
            delegate.wrapBytes(bytes, offset, length)

        override fun slice(source: IoBuf, offset: Int, length: Int): IoBuf =
            delegate.slice(source, offset, length)

        override fun registerPoolSize(size: Int, maxSlots: Int) {
            delegate.registerPoolSize(size, maxSlots)
        }

        override fun createChild(): BufferAllocator = this
    }

    /**
     * Trivial codec that on the first `unprotect` writes a single byte of
     * plaintext into the provided buffer and reports OK with the
     * ciphertext consumed. Enough to exercise the [TlsHandler] read path
     * exactly once so the per-record `allocate` is observable.
     */
    private class OneShotCodec : TlsCodec {
        override var isHandshakeComplete: Boolean = true
            private set
        override val negotiatedProtocol: String? = null
        override val peerCertificates: List<ByteArray> = emptyList()
        private var done = false

        @OptIn(io.github.fukusaka.keel.buf.UnsafeIoBufApi::class)
        override fun unprotect(ciphertext: IoBuf, plaintext: IoBuf): TlsCodecResult {
            if (done || ciphertext.readableBytes == 0) {
                return TlsCodecResult(TlsResult.NEED_MORE_INPUT, 0, 0)
            }
            plaintext.writeByte(0x42)
            done = true
            return TlsCodecResult(TlsResult.OK, ciphertext.readableBytes, 1)
        }

        override fun protect(plaintext: IoBuf, ciphertext: IoBuf): TlsCodecResult =
            TlsCodecResult(TlsResult.OK, 0, 0)

        override fun close() {}
    }

    @Test
    fun `read path allocates plaintext buffer of the configured size`() {
        val recorder = RecordingAllocator(DefaultAllocator)
        val transport = TestIoTransport(recorder)
        val channel = object : AbstractPipelinedChannel(transport, PrintLogger("tls-bufsize-test")) {}
        val customSize = 32 * 1024
        channel.pipeline.addLast("tls", TlsHandler(OneShotCodec(), plaintextBufferSize = customSize))

        // Feed a single-byte ciphertext through the pipeline so the read
        // path runs once and calls allocator.allocate(plaintextBufferSize).
        val cipherIn = recorder.allocate(1)
        cipherIn.writeByte(0x01)
        recorder.allocateSizes.clear()
        channel.pipeline.notifyRead(cipherIn)

        assertTrue(
            recorder.allocateSizes.any { it == customSize },
            "expected an allocate($customSize) call from TlsHandler, recorded sizes: ${recorder.allocateSizes}",
        )
    }

    @Test
    fun `read path allocates plaintext buffer of the default size when not overridden`() {
        val recorder = RecordingAllocator(DefaultAllocator)
        val transport = TestIoTransport(recorder)
        val channel = object : AbstractPipelinedChannel(transport, PrintLogger("tls-bufsize-test")) {}
        channel.pipeline.addLast("tls", TlsHandler(OneShotCodec()))

        val cipherIn = recorder.allocate(1)
        cipherIn.writeByte(0x01)
        recorder.allocateSizes.clear()
        channel.pipeline.notifyRead(cipherIn)

        assertTrue(
            recorder.allocateSizes.any { it == TlsHandler.TLS_PLAINTEXT_BUF_SIZE_DEFAULT },
            "expected default ${TlsHandler.TLS_PLAINTEXT_BUF_SIZE_DEFAULT} allocate, recorded: ${recorder.allocateSizes}",
        )
    }

    @Test
    fun `construction with the minimum size is accepted`() {
        TlsHandler(OneShotCodec(), plaintextBufferSize = TlsHandler.TLS_PLAINTEXT_BUF_SIZE_MIN)
    }

    @Test
    fun `construction below the minimum is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            TlsHandler(OneShotCodec(), plaintextBufferSize = TlsHandler.TLS_PLAINTEXT_BUF_SIZE_MIN / 2)
        }
    }

    @Test
    fun `construction with non power-of-two is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            TlsHandler(OneShotCodec(), plaintextBufferSize = TlsHandler.TLS_PLAINTEXT_BUF_SIZE_MIN + 7)
        }
    }

    @Test
    fun `constants expose the validated range`() {
        // Sanity checks on the public companion constants; not a behaviour
        // test but guards against accidental drift between the min/max
        // KDoc claims and their actual values.
        assertEquals(16 * 1024, TlsHandler.TLS_PLAINTEXT_BUF_SIZE_DEFAULT)
        assertEquals(16 * 1024, TlsHandler.TLS_PLAINTEXT_BUF_SIZE_MIN)
        assertEquals(1 shl 20, TlsHandler.TLS_PLAINTEXT_BUF_SIZE_MAX)
    }
}
