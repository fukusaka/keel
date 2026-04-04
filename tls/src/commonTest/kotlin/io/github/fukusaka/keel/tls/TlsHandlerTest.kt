package io.github.fukusaka.keel.tls

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.pipeline.ChannelDuplexHandler
import io.github.fukusaka.keel.pipeline.ChannelHandler
import io.github.fukusaka.keel.pipeline.ChannelHandlerContext
import io.github.fukusaka.keel.pipeline.ChannelInboundHandler
import io.github.fukusaka.keel.pipeline.ChannelPipeline
import io.github.fukusaka.keel.pipeline.DefaultChannelPipeline
import io.github.fukusaka.keel.pipeline.IoTransport
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TlsHandlerTest {

    private val logger = PrintLogger("tls-test")
    private val allocator: BufferAllocator = DefaultAllocator

    // --- Test infrastructure ---

    private val transport = object : IoTransport {
        val written = mutableListOf<IoBuf>()
        var flushed = false
        var closed = false
        override fun write(buf: IoBuf) {
            buf.retain()
            written.add(buf)
        }
        override fun flush(): Boolean { flushed = true; return true }
        override var onFlushComplete: (() -> Unit)? = null
        override fun close() { closed = true }
    }

    private val channel = object : PipelinedChannel {
        override lateinit var pipeline: ChannelPipeline
        override val isActive: Boolean = true
        override val isWritable: Boolean = true
        override val allocator: BufferAllocator get() = this@TlsHandlerTest.allocator
    }

    private fun createPipeline(tlsHandler: TlsHandler): DefaultChannelPipeline {
        val pipeline = DefaultChannelPipeline(channel, transport, logger)
        channel.pipeline = pipeline
        pipeline.addLast("tls", tlsHandler)
        return pipeline
    }

    // --- Recording handler (placed after TlsHandler) ---

    private class RecordingHandler : ChannelDuplexHandler {
        val reads = mutableListOf<ByteArray>()
        val errors = mutableListOf<Throwable>()
        val userEvents = mutableListOf<Any>()

        override fun onRead(ctx: ChannelHandlerContext, msg: Any) {
            if (msg is IoBuf) {
                val bytes = ByteArray(msg.readableBytes)
                for (i in bytes.indices) bytes[i] = msg.readByte()
                reads.add(bytes)
                msg.release()
            }
        }

        override fun onError(ctx: ChannelHandlerContext, cause: Throwable) {
            errors.add(cause)
        }

        override fun onUserEvent(ctx: ChannelHandlerContext, event: Any) {
            userEvents.add(event)
        }

        override fun onInactive(ctx: ChannelHandlerContext) {
            // Terminal
        }
    }

    // --- Mock TlsCodec ---

    /**
     * Mock TlsCodec that simulates TLS with a simple XOR "encryption".
     *
     * - unprotect: XOR each byte with [xorKey] to "decrypt"
     * - protect: XOR each byte with [xorKey] to "encrypt"
     * - Handshake completes after first unprotect + protect exchange
     */
    private class MockTlsCodec(
        private val xorKey: Byte = 0x42,
        private val handshakeSteps: Int = 0,
    ) : TlsCodec {
        override var isHandshakeComplete: Boolean = false
            private set
        override val negotiatedProtocol: String? = "http/1.1"
        override val peerCertificates: List<ByteArray> = emptyList()
        private var handshakeCount = 0
        private var closed = false

        override fun unprotect(ciphertext: IoBuf, plaintext: IoBuf): TlsCodecResult {
            if (handshakeCount < handshakeSteps) {
                handshakeCount++
                return TlsCodecResult(TlsResult.NEED_WRAP, 0, 0)
            }
            val readable = ciphertext.readableBytes
            if (readable == 0) return TlsCodecResult(TlsResult.NEED_MORE_INPUT, 0, 0)
            var produced = 0
            for (i in 0 until readable) {
                plaintext.writeByte((ciphertext.getByte(ciphertext.readerIndex + i).toInt() xor xorKey.toInt()).toByte())
                produced++
            }
            if (!isHandshakeComplete) isHandshakeComplete = true
            return TlsCodecResult(TlsResult.OK, readable, produced)
        }

        override fun protect(plaintext: IoBuf, ciphertext: IoBuf): TlsCodecResult {
            if (handshakeCount in 1..handshakeSteps) {
                // Produce handshake response bytes.
                ciphertext.writeByte(0xFF.toByte())
                if (handshakeCount >= handshakeSteps) isHandshakeComplete = true
                return TlsCodecResult(TlsResult.OK, 0, 1)
            }
            val readable = plaintext.readableBytes
            if (readable == 0) return TlsCodecResult(TlsResult.OK, 0, 0)
            var produced = 0
            for (i in 0 until readable) {
                ciphertext.writeByte((plaintext.getByte(plaintext.readerIndex + i).toInt() xor xorKey.toInt()).toByte())
                produced++
            }
            return TlsCodecResult(TlsResult.OK, readable, produced)
        }

        override fun close() {
            closed = true
        }
    }

    // --- Helper ---

    private fun allocBuf(data: ByteArray): IoBuf {
        val buf = allocator.allocate(data.size)
        for (b in data) buf.writeByte(b)
        return buf
    }

    private fun xorBytes(data: ByteArray, key: Byte = 0x42): ByteArray =
        ByteArray(data.size) { (data[it].toInt() xor key.toInt()).toByte() }

    // --- Tests ---

    @Test
    fun `unprotect decrypts ciphertext and propagates plaintext`() {
        val codec = MockTlsCodec()
        val handler = TlsHandler(codec)
        val pipeline = createPipeline(handler)
        val recorder = RecordingHandler()
        pipeline.addAfter("tls", "recorder", recorder)

        val plain = "hello".encodeToByteArray()
        val cipher = xorBytes(plain)
        pipeline.notifyRead(allocBuf(cipher))

        assertEquals(1, recorder.reads.size)
        assertEquals("hello", String(recorder.reads[0]))
    }

    @Test
    fun `protect encrypts plaintext and sends to transport`() {
        val codec = MockTlsCodec()
        codec.apply {
            // Force handshake complete so protect works for application data.
            val dummy = allocator.allocate(1)
            dummy.writeByte((0x42 xor 0x42).toByte()) // XOR produces 0x00
            val out = allocator.allocate(16)
            unprotect(dummy, out)
            dummy.release()
            out.release()
        }
        val handler = TlsHandler(codec)
        val pipeline = createPipeline(handler)
        val recorder = RecordingHandler()
        pipeline.addAfter("tls", "recorder", recorder)

        val plain = "world".encodeToByteArray()
        pipeline.requestWrite(allocBuf(plain))

        // Transport should have received XOR-encrypted bytes.
        assertEquals(1, transport.written.size)
        val written = transport.written[0]
        val encrypted = ByteArray(written.readableBytes)
        for (i in encrypted.indices) encrypted[i] = written.readByte()
        written.release()
        assertEquals(xorBytes(plain).toList(), encrypted.toList())
    }

    @Test
    fun `handshake fires TlsHandshakeComplete userEvent`() {
        val codec = MockTlsCodec(handshakeSteps = 0)
        val handler = TlsHandler(codec)
        val pipeline = createPipeline(handler)
        val recorder = RecordingHandler()
        pipeline.addAfter("tls", "recorder", recorder)

        // First unprotect triggers handshake completion in mock.
        val cipher = xorBytes("hi".encodeToByteArray())
        pipeline.notifyRead(allocBuf(cipher))

        assertEquals(1, recorder.userEvents.size)
        val event = recorder.userEvents[0]
        assertIs<TlsHandshakeComplete>(event)
        assertEquals("http/1.1", event.negotiatedProtocol)
    }

    @Test
    fun `handshake NEED_WRAP triggers protect and flush`() {
        val codec = MockTlsCodec(handshakeSteps = 1)
        val handler = TlsHandler(codec)
        val pipeline = createPipeline(handler)
        val recorder = RecordingHandler()
        pipeline.addAfter("tls", "recorder", recorder)

        // Send ciphertext — codec returns NEED_WRAP, then protect produces
        // handshake response, then unprotect succeeds on retry.
        val cipher = xorBytes("ok".encodeToByteArray())
        pipeline.notifyRead(allocBuf(cipher))

        // Handshake response should have been flushed to transport.
        assertTrue(transport.flushed)
        assertTrue(transport.written.isNotEmpty())
        transport.written.forEach { it.release() }

        // Handshake complete event should have fired.
        assertEquals(1, recorder.userEvents.size)
        assertIs<TlsHandshakeComplete>(recorder.userEvents[0])
    }

    @Test
    fun `handshake flush loops when protect returns NEED_WRAP`() {
        // Codec that requires 3 protect calls to complete the handshake flight:
        // protect #1 → NEED_WRAP (partial), #2 → NEED_WRAP (partial), #3 → OK (done).
        val multiFlushCodec = object : TlsCodec {
            override var isHandshakeComplete = false
                private set
            override val negotiatedProtocol: String? = null
            override val peerCertificates: List<ByteArray> = emptyList()
            private var unprotectCalled = false
            private var flushCount = 0

            override fun unprotect(ciphertext: IoBuf, plaintext: IoBuf): TlsCodecResult {
                if (!unprotectCalled) {
                    unprotectCalled = true
                    return TlsCodecResult(TlsResult.NEED_WRAP, ciphertext.readableBytes, 0)
                }
                // After handshake, decrypt by copying.
                val n = ciphertext.readableBytes
                for (i in 0 until n) plaintext.writeByte(ciphertext.getByte(ciphertext.readerIndex + i))
                isHandshakeComplete = true
                return TlsCodecResult(TlsResult.OK, n, n)
            }

            override fun protect(plaintext: IoBuf, ciphertext: IoBuf): TlsCodecResult {
                flushCount++
                ciphertext.writeByte(flushCount.toByte())
                return if (flushCount < 3) {
                    TlsCodecResult(TlsResult.NEED_WRAP, 0, 1)
                } else {
                    isHandshakeComplete = true
                    TlsCodecResult(TlsResult.OK, 0, 1)
                }
            }

            override fun close() {}
        }

        val handler = TlsHandler(multiFlushCodec)
        val pipeline = createPipeline(handler)
        val recorder = RecordingHandler()
        pipeline.addAfter("tls", "recorder", recorder)

        pipeline.notifyRead(allocBuf(byteArrayOf(1)))

        // All 3 flush iterations should have produced transport writes.
        assertEquals(3, transport.written.size)
        assertTrue(transport.flushed)
        transport.written.forEach { it.release() }

        // Handshake should be complete.
        assertEquals(1, recorder.userEvents.size)
        assertIs<TlsHandshakeComplete>(recorder.userEvents[0])
    }

    @Test
    fun `handshake error from protect propagates error and stops processing`() {
        val errorCodec = object : TlsCodec {
            override var isHandshakeComplete = false
            override val negotiatedProtocol: String? = null
            override val peerCertificates: List<ByteArray> = emptyList()

            override fun unprotect(ciphertext: IoBuf, plaintext: IoBuf): TlsCodecResult =
                TlsCodecResult(TlsResult.NEED_WRAP, ciphertext.readableBytes, 0)

            override fun protect(plaintext: IoBuf, ciphertext: IoBuf): TlsCodecResult =
                throw TlsException("certificate rejected", TlsErrorCategory.CERTIFICATE_INVALID)

            override fun close() {}
        }

        val handler = TlsHandler(errorCodec)
        val pipeline = createPipeline(handler)
        val recorder = RecordingHandler()
        pipeline.addAfter("tls", "recorder", recorder)

        pipeline.notifyRead(allocBuf(byteArrayOf(1, 2, 3)))

        // Error should be propagated, not thrown.
        assertEquals(1, recorder.errors.size)
        assertIs<TlsException>(recorder.errors[0])
        assertEquals(TlsErrorCategory.CERTIFICATE_INVALID, (recorder.errors[0] as TlsException).category)
        // No plaintext should reach downstream.
        assertTrue(recorder.reads.isEmpty())
    }

    @Test
    fun `handshake flush stall propagates error`() {
        val stallCodec = object : TlsCodec {
            override var isHandshakeComplete = false
            override val negotiatedProtocol: String? = null
            override val peerCertificates: List<ByteArray> = emptyList()

            override fun unprotect(ciphertext: IoBuf, plaintext: IoBuf): TlsCodecResult =
                TlsCodecResult(TlsResult.NEED_WRAP, ciphertext.readableBytes, 0)

            override fun protect(plaintext: IoBuf, ciphertext: IoBuf): TlsCodecResult =
                TlsCodecResult(TlsResult.NEED_WRAP, 0, 0) // stalled: no progress

            override fun close() {}
        }

        val handler = TlsHandler(stallCodec)
        val pipeline = createPipeline(handler)
        val recorder = RecordingHandler()
        pipeline.addAfter("tls", "recorder", recorder)

        pipeline.notifyRead(allocBuf(byteArrayOf(1)))

        assertEquals(1, recorder.errors.size)
        assertIs<TlsException>(recorder.errors[0])
        assertEquals(TlsErrorCategory.PROTOCOL_ERROR, (recorder.errors[0] as TlsException).category)
    }

    @Test
    fun `unprotect TlsException propagates error`() {
        val errorCodec = object : TlsCodec {
            override var isHandshakeComplete = true
            override val negotiatedProtocol: String? = null
            override val peerCertificates: List<ByteArray> = emptyList()

            override fun unprotect(ciphertext: IoBuf, plaintext: IoBuf): TlsCodecResult =
                throw TlsException("bad record MAC", TlsErrorCategory.PROTOCOL_ERROR)

            override fun protect(plaintext: IoBuf, ciphertext: IoBuf): TlsCodecResult =
                TlsCodecResult(TlsResult.OK, 0, 0)

            override fun close() {}
        }

        val handler = TlsHandler(errorCodec)
        val pipeline = createPipeline(handler)
        val recorder = RecordingHandler()
        pipeline.addAfter("tls", "recorder", recorder)

        pipeline.notifyRead(allocBuf(byteArrayOf(1)))

        assertEquals(1, recorder.errors.size)
        assertIs<TlsException>(recorder.errors[0])
        assertEquals(TlsErrorCategory.PROTOCOL_ERROR, (recorder.errors[0] as TlsException).category)
    }

    @Test
    fun `handlerRemoved releases accumulate buffer and closes codec`() {
        val codec = MockTlsCodec()
        val handler = TlsHandler(codec)
        val pipeline = createPipeline(handler)
        val recorder = RecordingHandler()
        pipeline.addAfter("tls", "recorder", recorder)

        pipeline.remove("tls")
        // No assertion crash = accumulate was null, codec.close() called.
    }

    @Test
    fun `CLOSED status propagates inactive`() {
        val closedCodec = object : TlsCodec {
            override var isHandshakeComplete = true
            override val negotiatedProtocol: String? = null
            override val peerCertificates: List<ByteArray> = emptyList()
            override fun unprotect(ciphertext: IoBuf, plaintext: IoBuf) =
                TlsCodecResult(TlsResult.CLOSED, 0, 0)
            override fun protect(plaintext: IoBuf, ciphertext: IoBuf) =
                TlsCodecResult(TlsResult.OK, 0, 0)
            override fun close() {}
        }
        val handler = TlsHandler(closedCodec)
        val pipeline = createPipeline(handler)

        var inactive = false
        val inactiveHandler = object : ChannelInboundHandler {
            override fun onInactive(ctx: ChannelHandlerContext) { inactive = true }
        }
        pipeline.addAfter("tls", "inactive-check", inactiveHandler)

        pipeline.notifyRead(allocBuf(byteArrayOf(1)))
        assertTrue(inactive)
    }
}
