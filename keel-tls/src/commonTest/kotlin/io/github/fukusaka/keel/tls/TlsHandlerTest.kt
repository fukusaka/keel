package io.github.fukusaka.keel.tls

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import io.github.fukusaka.keel.pipeline.DuplexHandler
import io.github.fukusaka.keel.pipeline.InboundHandler
import io.github.fukusaka.keel.pipeline.Pipeline
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TlsHandlerTest {

    private val logger = PrintLogger("tls-test")
    private val allocator: BufferAllocator = DefaultAllocator

    // --- Test infrastructure ---

    private val transport = TestIoTransport()
    private val channel = object : AbstractPipelinedChannel(transport, logger) {}

    private fun createPipeline(tlsHandler: TlsHandler): Pipeline {
        val pipeline = channel.pipeline
        pipeline.addLast("tls", tlsHandler)
        return pipeline
    }

    // --- Recording handler (placed after TlsHandler) ---

    private class RecordingHandler : DuplexHandler {
        val reads = mutableListOf<ByteArray>()
        val errors = mutableListOf<Throwable>()
        val userEvents = mutableListOf<Any>()

        override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
            if (msg is IoBuf) {
                val bytes = ByteArray(msg.readableBytes)
                for (i in bytes.indices) bytes[i] = msg.readByte()
                reads.add(bytes)
                msg.release()
            }
        }

        override fun onError(ctx: PipelineHandlerContext, cause: Throwable) {
            errors.add(cause)
        }

        override fun onUserEvent(ctx: PipelineHandlerContext, event: Any) {
            userEvents.add(event)
        }

        override fun onInactive(ctx: PipelineHandlerContext) {
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
                plaintext.writeByte(
                    (ciphertext.getByte(ciphertext.readerIndex + i).toInt() xor xorKey.toInt()).toByte(),
                )
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
        assertEquals("hello", recorder.reads[0].decodeToString())
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

    // --- RFC-compliant BUFFER_OVERFLOW handling ---
    //
    // RFC 5246 §6.2.3 and RFC 8446 §5.2 cap TLSCiphertext.length at
    // 2^14 + 2048 and 2^14 + 256 respectively. If the codec cannot fit
    // a record into the output buffer, it returns
    // TlsResult.BUFFER_OVERFLOW. Every TlsHandler call site maps that
    // status to a TlsException with TlsErrorCategory.BUFFER_ERROR and
    // stops processing, which matches the RFC-mandated receiver
    // response (terminate with record_overflow) — the pipeline layer
    // above observes the error and tears the channel down.
    //
    // These tests use minimal mock codecs that return BUFFER_OVERFLOW
    // unconditionally, which is how JSSE, OpenSSL, MbedTLS, and AWS-LC
    // all signal "the destination buffer you gave me is too small for
    // the record I need to emit". No real cipher suite reaches the
    // ceiling with keel's current 17 KiB TLS_RECORD_BUF_SIZE, so the
    // production overflow path is not exercised end-to-end; the tests
    // simulate the codec-level signal so that the handler's RFC
    // alignment is verified directly.

    @Test
    fun `unprotect BUFFER_OVERFLOW propagates TlsException with BUFFER_ERROR`() {
        val overflowCodec = object : TlsCodec {
            override var isHandshakeComplete = true
            override val negotiatedProtocol: String? = null
            override val peerCertificates: List<ByteArray> = emptyList()

            override fun unprotect(ciphertext: IoBuf, plaintext: IoBuf): TlsCodecResult =
                TlsCodecResult(TlsResult.BUFFER_OVERFLOW, 0, 0)

            override fun protect(plaintext: IoBuf, ciphertext: IoBuf): TlsCodecResult =
                TlsCodecResult(TlsResult.OK, 0, 0)

            override fun close() {}
        }

        val handler = TlsHandler(overflowCodec)
        val pipeline = createPipeline(handler)
        val recorder = RecordingHandler()
        pipeline.addAfter("tls", "recorder", recorder)

        pipeline.notifyRead(allocBuf(byteArrayOf(1, 2, 3, 4, 5)))

        // Handler must signal the RFC-mandated "terminate on
        // record_overflow" condition via a structured error on the
        // pipeline; the downstream layer is responsible for closing.
        assertEquals(1, recorder.errors.size, "exactly one error expected on overflow")
        val error = recorder.errors[0]
        assertIs<TlsException>(error)
        assertEquals(
            TlsErrorCategory.BUFFER_ERROR,
            error.category,
            "overflow error must use BUFFER_ERROR category so upstream handlers can distinguish it from protocol errors",
        )
        assertTrue(recorder.reads.isEmpty(), "no plaintext must reach downstream on overflow")
    }

    @Test
    fun `protect BUFFER_OVERFLOW propagates TlsException with BUFFER_ERROR`() {
        // Codec completes handshake on first unprotect, then returns
        // BUFFER_OVERFLOW for every subsequent protect call. This models
        // the "codec cannot fit the ciphertext for this plaintext record
        // into the destination buffer" case on the application-data
        // write path.
        val overflowCodec = object : TlsCodec {
            override var isHandshakeComplete = false
                private set
            override val negotiatedProtocol: String? = null
            override val peerCertificates: List<ByteArray> = emptyList()

            override fun unprotect(ciphertext: IoBuf, plaintext: IoBuf): TlsCodecResult {
                val n = ciphertext.readableBytes
                for (i in 0 until n) plaintext.writeByte(ciphertext.getByte(ciphertext.readerIndex + i))
                isHandshakeComplete = true
                return TlsCodecResult(TlsResult.OK, n, n)
            }

            override fun protect(plaintext: IoBuf, ciphertext: IoBuf): TlsCodecResult =
                TlsCodecResult(TlsResult.BUFFER_OVERFLOW, 0, 0)

            override fun close() {}
        }

        val handler = TlsHandler(overflowCodec)
        val pipeline = createPipeline(handler)
        val recorder = RecordingHandler()
        pipeline.addAfter("tls", "recorder", recorder)

        // Complete handshake so subsequent requestWrite enters
        // processOutbound application-data encoding.
        pipeline.notifyRead(allocBuf(byteArrayOf(1, 2, 3)))
        // Discard handshake plaintext delivered by unprotect.
        recorder.reads.clear()

        // Trigger processOutbound, which will hit BUFFER_OVERFLOW.
        pipeline.requestWrite(allocBuf("world".encodeToByteArray()))

        assertEquals(1, recorder.errors.size, "exactly one error expected on outbound overflow")
        val error = recorder.errors[0]
        assertIs<TlsException>(error)
        assertEquals(
            TlsErrorCategory.BUFFER_ERROR,
            error.category,
            "outbound overflow error must use BUFFER_ERROR category",
        )
        // No ciphertext should reach the transport.
        transport.written.forEach { it.release() }
        assertTrue(transport.written.isEmpty(), "no ciphertext must reach transport on outbound overflow")
    }

    @Test
    fun `handshake protect BUFFER_OVERFLOW propagates TlsException with BUFFER_ERROR`() {
        // Codec drives the handshake by returning NEED_WRAP from
        // unprotect, which makes TlsHandler enter flushHandshakeResponse.
        // The subsequent protect call returns BUFFER_OVERFLOW,
        // simulating a handshake flight whose ciphertext cannot fit in
        // the buffer (e.g. an oversized server certificate chain).
        val overflowHandshakeCodec = object : TlsCodec {
            override var isHandshakeComplete = false
            override val negotiatedProtocol: String? = null
            override val peerCertificates: List<ByteArray> = emptyList()

            override fun unprotect(ciphertext: IoBuf, plaintext: IoBuf): TlsCodecResult =
                TlsCodecResult(TlsResult.NEED_WRAP, ciphertext.readableBytes, 0)

            override fun protect(plaintext: IoBuf, ciphertext: IoBuf): TlsCodecResult =
                TlsCodecResult(TlsResult.BUFFER_OVERFLOW, 0, 0)

            override fun close() {}
        }

        val handler = TlsHandler(overflowHandshakeCodec)
        val pipeline = createPipeline(handler)
        val recorder = RecordingHandler()
        pipeline.addAfter("tls", "recorder", recorder)

        pipeline.notifyRead(allocBuf(byteArrayOf(1, 2, 3, 4)))

        assertEquals(1, recorder.errors.size, "exactly one error expected on handshake overflow")
        val error = recorder.errors[0]
        assertIs<TlsException>(error)
        assertEquals(
            TlsErrorCategory.BUFFER_ERROR,
            error.category,
            "handshake overflow error must use BUFFER_ERROR category",
        )
        // Handshake did not complete; no TlsHandshakeComplete event.
        assertTrue(
            recorder.userEvents.none { it is TlsHandshakeComplete },
            "handshake must not complete when flush overflows",
        )
        transport.written.forEach { it.release() }
    }

    // --- processOutbound hardening against unexpected codec states ---
    //
    // PR #249 rebuilt processOutbound's status dispatch with explicit
    // BUFFER_OVERFLOW / CLOSED handling but left three latent issues
    // that a buggy codec could trigger: an OK-with-no-progress stall,
    // a silent break on NEED_WRAP, and a silent break on
    // NEED_MORE_INPUT. Each produced a silent write truncation — the
    // caller saw the write as successful but only a prefix of the
    // plaintext (or nothing at all) actually reached the wire. These
    // tests pin down the follow-up fix that turns each state into a
    // structured PROTOCOL_ERROR propagated through the pipeline.

    @Test
    fun `protect OK with no progress propagates stall error`() {
        // Codec completes the handshake on the first unprotect, then
        // returns OK with bytesConsumed = 0 and bytesProduced = 0 on
        // every subsequent protect call. Without the stall guard the
        // processOutbound loop would re-enter with identical state and
        // spin forever.
        val stallCodec = object : TlsCodec {
            override var isHandshakeComplete = false
                private set
            override val negotiatedProtocol: String? = null
            override val peerCertificates: List<ByteArray> = emptyList()

            override fun unprotect(ciphertext: IoBuf, plaintext: IoBuf): TlsCodecResult {
                val n = ciphertext.readableBytes
                for (i in 0 until n) plaintext.writeByte(ciphertext.getByte(ciphertext.readerIndex + i))
                isHandshakeComplete = true
                return TlsCodecResult(TlsResult.OK, n, n)
            }

            override fun protect(plaintext: IoBuf, ciphertext: IoBuf): TlsCodecResult =
                TlsCodecResult(TlsResult.OK, 0, 0)

            override fun close() {}
        }

        val handler = TlsHandler(stallCodec)
        val pipeline = createPipeline(handler)
        val recorder = RecordingHandler()
        pipeline.addAfter("tls", "recorder", recorder)

        // Complete handshake so subsequent requestWrite enters
        // processOutbound application-data encoding.
        pipeline.notifyRead(allocBuf(byteArrayOf(1, 2, 3)))
        recorder.reads.clear()

        pipeline.requestWrite(allocBuf("hello".encodeToByteArray()))

        assertEquals(1, recorder.errors.size, "exactly one error expected on outbound stall")
        val error = recorder.errors[0]
        assertIs<TlsException>(error)
        assertEquals(
            TlsErrorCategory.PROTOCOL_ERROR,
            error.category,
            "outbound stall must surface as PROTOCOL_ERROR",
        )
        // "hello" is 5 bytes; the stall fires on the first protect call
        // before plainBuf.readerIndex is advanced, so all 5 bytes should
        // still be remaining and the message must report that count.
        assertTrue(
            error.message!!.contains("5 plaintext bytes remaining"),
            "stall error message should include remaining plaintext byte count for operator visibility, was: ${error.message}",
        )
        transport.written.forEach { it.release() }
        assertTrue(transport.written.isEmpty(), "no ciphertext must reach transport on stall")
    }

    @Test
    fun `protect NEED_WRAP during application data propagates PROTOCOL_ERROR`() {
        // Codec completes the handshake on the first unprotect, then
        // returns NEED_WRAP from protect as if it wanted to interleave
        // a post-handshake message. keel's TlsCodec contract does not
        // support that pattern during application-data encoding, so
        // the handler must surface the unexpected state rather than
        // silently truncate the caller's write.
        val needWrapCodec = object : TlsCodec {
            override var isHandshakeComplete = false
                private set
            override val negotiatedProtocol: String? = null
            override val peerCertificates: List<ByteArray> = emptyList()

            override fun unprotect(ciphertext: IoBuf, plaintext: IoBuf): TlsCodecResult {
                val n = ciphertext.readableBytes
                for (i in 0 until n) plaintext.writeByte(ciphertext.getByte(ciphertext.readerIndex + i))
                isHandshakeComplete = true
                return TlsCodecResult(TlsResult.OK, n, n)
            }

            override fun protect(plaintext: IoBuf, ciphertext: IoBuf): TlsCodecResult =
                TlsCodecResult(TlsResult.NEED_WRAP, 0, 0)

            override fun close() {}
        }

        val handler = TlsHandler(needWrapCodec)
        val pipeline = createPipeline(handler)
        val recorder = RecordingHandler()
        pipeline.addAfter("tls", "recorder", recorder)

        pipeline.notifyRead(allocBuf(byteArrayOf(1, 2, 3)))
        recorder.reads.clear()

        pipeline.requestWrite(allocBuf("hello".encodeToByteArray()))

        assertEquals(1, recorder.errors.size, "exactly one error expected on NEED_WRAP during protect")
        val error = recorder.errors[0]
        assertIs<TlsException>(error)
        assertEquals(
            TlsErrorCategory.PROTOCOL_ERROR,
            error.category,
            "unexpected NEED_WRAP during application protect must surface as PROTOCOL_ERROR",
        )
        assertTrue(
            error.message!!.contains("5 plaintext bytes remaining"),
            "NEED_WRAP error message should include remaining plaintext byte count, was: ${error.message}",
        )
        transport.written.forEach { it.release() }
        assertTrue(transport.written.isEmpty(), "no ciphertext must reach transport on unexpected NEED_WRAP")
    }

    @Test
    fun `protect NEED_MORE_INPUT during application data propagates PROTOCOL_ERROR`() {
        // NEED_MORE_INPUT is a signal specific to unprotect (the codec
        // needs more ciphertext to decode a record) and is meaningless
        // on the protect path — the caller has already handed over all
        // the plaintext it intends to encode. A codec returning this
        // status from protect is in a broken state machine and must
        // not be allowed to silently truncate the outbound stream.
        val needMoreInputCodec = object : TlsCodec {
            override var isHandshakeComplete = false
                private set
            override val negotiatedProtocol: String? = null
            override val peerCertificates: List<ByteArray> = emptyList()

            override fun unprotect(ciphertext: IoBuf, plaintext: IoBuf): TlsCodecResult {
                val n = ciphertext.readableBytes
                for (i in 0 until n) plaintext.writeByte(ciphertext.getByte(ciphertext.readerIndex + i))
                isHandshakeComplete = true
                return TlsCodecResult(TlsResult.OK, n, n)
            }

            override fun protect(plaintext: IoBuf, ciphertext: IoBuf): TlsCodecResult =
                TlsCodecResult(TlsResult.NEED_MORE_INPUT, 0, 0)

            override fun close() {}
        }

        val handler = TlsHandler(needMoreInputCodec)
        val pipeline = createPipeline(handler)
        val recorder = RecordingHandler()
        pipeline.addAfter("tls", "recorder", recorder)

        pipeline.notifyRead(allocBuf(byteArrayOf(1, 2, 3)))
        recorder.reads.clear()

        pipeline.requestWrite(allocBuf("hello".encodeToByteArray()))

        assertEquals(1, recorder.errors.size, "exactly one error expected on NEED_MORE_INPUT during protect")
        val error = recorder.errors[0]
        assertIs<TlsException>(error)
        assertEquals(
            TlsErrorCategory.PROTOCOL_ERROR,
            error.category,
            "unexpected NEED_MORE_INPUT during application protect must surface as PROTOCOL_ERROR",
        )
        assertTrue(
            error.message!!.contains("5 plaintext bytes remaining"),
            "NEED_MORE_INPUT error message should include remaining plaintext byte count, was: ${error.message}",
        )
        transport.written.forEach { it.release() }
        assertTrue(transport.written.isEmpty(), "no ciphertext must reach transport on unexpected NEED_MORE_INPUT")
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
        val inactiveHandler = object : InboundHandler {
            override fun onInactive(ctx: PipelineHandlerContext) { inactive = true }
        }
        pipeline.addAfter("tls", "inactive-check", inactiveHandler)

        pipeline.notifyRead(allocBuf(byteArrayOf(1)))
        assertTrue(inactive)
    }

    // --- Outbound plaintext buffer ownership ---

    /**
     * Pins the ownership contract for [TlsHandler.onWrite]'s `msg` parameter:
     * the caller hands over an [IoBuf] and does not retain it past the call
     * (matching [io.github.fukusaka.keel.codec.http.HttpResponseEncoder]'s
     * `ctx.propagateWrite(content.content)` — fire-and-forget, no follow-up
     * release of its own), so [TlsHandler] must be the one to release it once
     * [processOutbound] has fully consumed it. A real caller with this exact
     * shape (`respondFromBytes`) leaked 100 KB of Netty-pooled direct memory
     * per HTTPS response before this was fixed, reproduced as
     * `ResourceLeakDetector` warnings and a ~16x throughput collapse under
     * concurrent load.
     */
    @Test
    fun `onWrite releases the plaintext buffer once fully consumed`() {
        val tracker = TrackingAllocator(DefaultAllocator)
        val trackedTransport = TestIoTransport(allocator = tracker)
        val trackedChannel = object : AbstractPipelinedChannel(trackedTransport, logger) {}
        val handler = TlsHandler(MockTlsCodec())
        trackedChannel.pipeline.addLast("tls", handler)

        val plain = tracker.allocate(5)
        plain.writeByteArray("hello".encodeToByteArray(), 0, 5)
        trackedChannel.pipeline.requestWrite(plain)

        trackedTransport.written.forEach { it.release() }
        tracker.assertNoLeaks("TlsHandler.onWrite must release the plaintext buffer it fully consumed")
    }

    // --- Accumulate buffer right-sizing ---

    /**
     * Mock TlsCodec simulating JSSE's `SSLEngine.unwrap()` `BUFFER_UNDERFLOW`
     * contract: returns [TlsResult.NEED_MORE_INPUT] with `bytesConsumed=0`
     * (no partial consumption) whenever fewer bytes than a full TLS record —
     * the same 5-byte header ([TlsHandler]'s private `TLS_RECORD_HEADER_SIZE`)
     * + declared payload length wire format [TlsHandler.recordSizeIfKnown]
     * parses — are available. Unlike [MockTlsCodec] and the native
     * BIO-callback codecs (which always drain everything they are handed
     * before reporting NEED_MORE_INPUT), this mirrors the codec shape that
     * actually exercises [TlsHandler]'s accumulate path.
     */
    private class JsseLikeMockCodec(private val xorKey: Byte = 0x42) : TlsCodec {
        override var isHandshakeComplete: Boolean = true
        override val negotiatedProtocol: String? = "http/1.1"
        override val peerCertificates: List<ByteArray> = emptyList()

        override fun unprotect(ciphertext: IoBuf, plaintext: IoBuf): TlsCodecResult {
            val readable = ciphertext.readableBytes
            if (readable < RECORD_HEADER_SIZE) return TlsCodecResult(TlsResult.NEED_MORE_INPUT, 0, 0)
            val base = ciphertext.readerIndex
            val length = ((ciphertext.getByte(base + 3).toInt() and 0xFF) shl 8) or
                (ciphertext.getByte(base + 4).toInt() and 0xFF)
            val total = RECORD_HEADER_SIZE + length
            if (readable < total) return TlsCodecResult(TlsResult.NEED_MORE_INPUT, 0, 0)
            var produced = 0
            for (i in RECORD_HEADER_SIZE until total) {
                plaintext.writeByte((ciphertext.getByte(base + i).toInt() xor xorKey.toInt()).toByte())
                produced++
            }
            return TlsCodecResult(TlsResult.OK, total, produced)
        }

        override fun protect(plaintext: IoBuf, ciphertext: IoBuf): TlsCodecResult =
            TlsCodecResult(TlsResult.OK, plaintext.readableBytes, 0)

        override fun close() {}

        private companion object {
            const val RECORD_HEADER_SIZE = 5
        }
    }

    /** Records the [capacity] passed to every [allocate] call, delegating the actual work. */
    private class SizeRecordingAllocator(delegate: BufferAllocator) : BufferAllocator by delegate {
        val allocatedSizes = mutableListOf<Int>()
        private val delegate = delegate

        override fun allocate(capacity: Int): IoBuf {
            allocatedSizes.add(capacity)
            return delegate.allocate(capacity)
        }
    }

    /**
     * Builds a fake TLS record: 5-byte header (type=0x17, version=0x0303,
     * big-endian length) + XOR-"encrypted" [plaintext] (matching
     * [JsseLikeMockCodec.unprotect]'s XOR "decryption").
     */
    private fun fakeRecord(plaintext: ByteArray): ByteArray {
        val header = byteArrayOf(0x17, 0x03, 0x03, (plaintext.size shr 8).toByte(), (plaintext.size and 0xFF).toByte())
        return header + xorBytes(plaintext)
    }

    @Test
    fun `saveAccumulate right-sizes to the full record length once the header is known`() {
        val spy = SizeRecordingAllocator(DefaultAllocator)
        val spyTransport = TestIoTransport(allocator = spy)
        val spyChannel = object : AbstractPipelinedChannel(spyTransport, logger) {}
        val handler = TlsHandler(JsseLikeMockCodec())
        spyChannel.pipeline.addLast("tls", handler)
        val recorder = RecordingHandler()
        spyChannel.pipeline.addAfter("tls", "recorder", recorder)

        val plain = "hello world!".encodeToByteArray() // 12-byte payload -> 17-byte record.
        val record = fakeRecord(plain)

        // First read: header (5B) + 3B of payload — well short of the full
        // 17-byte record. Without right-sizing, saveAccumulate would
        // allocate exactly 8 bytes here.
        spyChannel.pipeline.notifyRead(allocBuf(record.copyOfRange(0, 8)))
        // Second read: the remaining 9 bytes. Without right-sizing,
        // mergeWithAccumulate would have to grow-and-recopy the first 8
        // bytes into a new 17-byte buffer here (a second accumulate-path
        // allocation). With right-sizing, the first allocation already
        // reserved room for all 17 bytes, so this read only appends.
        spyChannel.pipeline.notifyRead(allocBuf(record.copyOfRange(8, 17)))

        assertEquals(1, recorder.reads.size, "the complete record must decrypt once both reads arrive")
        assertEquals("hello world!", recorder.reads[0].decodeToString())
        assertEquals(
            listOf(17),
            spy.allocatedSizes.filter { it != TlsHandler.TLS_PLAINTEXT_BUF_SIZE_DEFAULT },
            "the accumulate buffer must be allocated once, sized to the full 17-byte record " +
                "(5-byte header + 12-byte payload) parsed from the header on the first short read — " +
                "not to the 8 bytes available at that point, and not grown again on the second read " +
                "(plaintextBufferSize allocations for the per-loop plainBuf are filtered out)",
        )
    }

    /**
     * Pins that [TlsHandler.saveAccumulate] does not re-allocate on every
     * `NEED_MORE_INPUT` for a record spanning 3+ reads — only the first
     * short read (before the accumulate buffer exists) should allocate; once
     * `input === accumulate`, later short reads are a no-op (the buffer is
     * already right-sized and [TlsHandler.mergeWithAccumulate] already
     * appended into its headroom). A prior version of this fix allocated
     * and re-copied on every intermediate read regardless, silently
     * defeating the right-sizing optimization for any record split across
     * more than two reads — undetected by the 2-read test above.
     */
    @Test
    fun `saveAccumulate does not reallocate on intermediate reads of a record spanning 3 or more reads`() {
        val spy = SizeRecordingAllocator(DefaultAllocator)
        val spyTransport = TestIoTransport(allocator = spy)
        val spyChannel = object : AbstractPipelinedChannel(spyTransport, logger) {}
        val handler = TlsHandler(JsseLikeMockCodec())
        spyChannel.pipeline.addLast("tls", handler)
        val recorder = RecordingHandler()
        spyChannel.pipeline.addAfter("tls", "recorder", recorder)

        val plain = "hello world!".encodeToByteArray() // 12-byte payload -> 17-byte record.
        val record = fakeRecord(plain)

        // Three reads, none of which alone completes the 17-byte record:
        // header (5B) + 1B payload, then 2 more bytes, then the rest.
        spyChannel.pipeline.notifyRead(allocBuf(record.copyOfRange(0, 6)))
        spyChannel.pipeline.notifyRead(allocBuf(record.copyOfRange(6, 8)))
        spyChannel.pipeline.notifyRead(allocBuf(record.copyOfRange(8, 17)))

        assertEquals(1, recorder.reads.size, "the complete record must decrypt once all three reads arrive")
        assertEquals("hello world!", recorder.reads[0].decodeToString())
        assertEquals(
            listOf(17),
            spy.allocatedSizes.filter { it != TlsHandler.TLS_PLAINTEXT_BUF_SIZE_DEFAULT },
            "only the first short read (6B, header known) should allocate the accumulate buffer, " +
                "sized to the full 17-byte record — the second short read (2B, input already the " +
                "accumulate buffer) must not trigger a second allocation",
        )
    }

    /**
     * Pins [TlsHandler.recordSizeIfKnown]'s header parsing at a non-zero
     * `readerIndex`: when one `onRead` delivers a complete record followed
     * by the start of a partial one, [TlsHandler.processInbound]'s loop
     * consumes the complete record first, leaving `input.readerIndex`
     * pointing at the partial record's header — not at absolute offset 0
     * of the buffer.
     */
    @Test
    fun `saveAccumulate right-sizes correctly when the partial record follows a complete one in the same read`() {
        val spy = SizeRecordingAllocator(DefaultAllocator)
        val spyTransport = TestIoTransport(allocator = spy)
        val spyChannel = object : AbstractPipelinedChannel(spyTransport, logger) {}
        val handler = TlsHandler(JsseLikeMockCodec())
        spyChannel.pipeline.addLast("tls", handler)
        val recorder = RecordingHandler()
        spyChannel.pipeline.addAfter("tls", "recorder", recorder)

        val recordA = fakeRecord("first".encodeToByteArray()) // 10-byte record.
        val recordB = fakeRecord("hello world!".encodeToByteArray()) // 17-byte record.

        // One read: complete record A, followed by the header + 3 bytes of
        // record B's payload (short of B's full 17 bytes).
        spyChannel.pipeline.notifyRead(allocBuf(recordA + recordB.copyOfRange(0, 8)))
        spyChannel.pipeline.notifyRead(allocBuf(recordB.copyOfRange(8, 17)))

        assertEquals(2, recorder.reads.size)
        assertEquals("first", recorder.reads[0].decodeToString())
        assertEquals("hello world!", recorder.reads[1].decodeToString())
        assertEquals(
            listOf(17),
            spy.allocatedSizes.filter { it != TlsHandler.TLS_PLAINTEXT_BUF_SIZE_DEFAULT },
            "record B's accumulate buffer must be right-sized to 17 even though its header sits at " +
                "a non-zero offset within the shared input buffer (after record A was consumed)",
        )
    }

    @Test
    fun `saveAccumulate falls back to remaining-only sizing when the header itself is incomplete`() {
        val spy = SizeRecordingAllocator(DefaultAllocator)
        val spyTransport = TestIoTransport(allocator = spy)
        val spyChannel = object : AbstractPipelinedChannel(spyTransport, logger) {}
        val handler = TlsHandler(JsseLikeMockCodec())
        spyChannel.pipeline.addLast("tls", handler)
        val recorder = RecordingHandler()
        spyChannel.pipeline.addAfter("tls", "recorder", recorder)

        val plain = "hi".encodeToByteArray() // 2-byte payload -> 7-byte record.
        val record = fakeRecord(plain)

        // First read: only 3 bytes — shorter than the 5-byte header itself,
        // so the record length cannot be determined yet.
        spyChannel.pipeline.notifyRead(allocBuf(record.copyOfRange(0, 3)))
        spyChannel.pipeline.notifyRead(allocBuf(record.copyOfRange(3, 7)))

        assertEquals(1, recorder.reads.size)
        assertEquals("hi", recorder.reads[0].decodeToString())
        assertEquals(
            listOf(3, 7),
            spy.allocatedSizes.filter { it != TlsHandler.TLS_PLAINTEXT_BUF_SIZE_DEFAULT },
            "with fewer than 5 bytes on the first read, the header cannot be parsed yet — " +
                "saveAccumulate falls back to allocating exactly what's available (3), then " +
                "mergeWithAccumulate must grow-and-recopy to 7 once the rest arrives " +
                "(plaintextBufferSize allocations for the per-loop plainBuf are filtered out)",
        )
    }
}
