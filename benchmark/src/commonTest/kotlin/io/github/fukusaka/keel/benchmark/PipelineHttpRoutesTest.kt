package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.compression.CodecStatus
import io.github.fukusaka.keel.compression.EncoderOptions
import io.github.fukusaka.keel.compression.zlib.GzipEncoder
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.pipeline.AbstractIoTransport
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import io.github.fukusaka.keel.pipeline.Pipeline
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [installPipelineHttpHandlers] routing behaviour.
 *
 * Drives the full encoder → decoder → routing stack via raw HTTP/1.1 byte
 * input and asserts on the captured wire output — identical to the pattern
 * used by `RoutingHandlerTest` in `:keel-codec-http`.
 */
class PipelineHttpRoutesTest {

    private class CapturingTransport : AbstractIoTransport(DefaultAllocator) {
        val written: MutableList<IoBuf> = mutableListOf()
        override var readEnabled: Boolean = false
        override val ioDispatcher: CoroutineDispatcher get() = Dispatchers.Unconfined
        override fun write(buf: IoBuf) { buf.retain(); written.add(buf) }
        override fun flush(): Boolean = true
        override fun shutdownOutput() {}
        override fun close() {
            if (!markClosing()) return
            if (!markTeardownStarted()) return
            for (buf in written) buf.release()
            written.clear()
        }
    }

    private val transport = CapturingTransport()
    private val channel = object : AbstractPipelinedChannel(transport, PrintLogger("test")) {}

    @AfterTest
    fun tearDown() {
        channel.close()
    }

    private fun createPipeline(compression: Boolean = false): Pipeline {
        val pipeline = channel.pipeline
        installPipelineHttpHandlers(pipeline, compression = compression)
        return pipeline
    }

    /**
     * Build a deterministic gzip blob for [plain] using the same
     * `keel-compression-zlib` `GzipEncoder` the route handlers use.
     * Returned bytes carry the RFC 1952 magic + DEFLATE stream + CRC32 +
     * ISIZE trailer; suitable as a `Content-Encoding: gzip` request body.
     */
    private fun gzipEncode(plain: ByteArray): ByteArray {
        val session = GzipEncoder.newSession(DefaultAllocator, EncoderOptions())
        val input = DefaultAllocator.allocate(plain.size.coerceAtLeast(1))
        val output = DefaultAllocator.allocate(64)
        val out = ArrayList<Byte>(plain.size / 4)
        try {
            input.writeByteArray(plain, 0, plain.size)
            // Drain update.
            while (true) {
                output.clear()
                when (session.update(input, output)) {
                    CodecStatus.NEED_OUTPUT -> drainTo(output, out)
                    CodecStatus.NEED_INPUT -> { drainTo(output, out); break }
                    CodecStatus.FINISHED -> error("update should not return FINISHED")
                }
            }
            // Finish — emit gzip trailer.
            while (true) {
                output.clear()
                val status = session.finish(output)
                drainTo(output, out)
                if (status == CodecStatus.FINISHED) break
            }
        } finally {
            session.close()
            input.release()
            output.release()
        }
        return ByteArray(out.size) { out[it] }
    }

    private fun drainTo(buf: IoBuf, sink: ArrayList<Byte>) {
        val n = buf.readableBytes
        if (n == 0) return
        val tmp = ByteArray(n)
        buf.readByteArray(tmp, 0, n)
        for (b in tmp) sink.add(b)
    }

    private fun Pipeline.feed(text: String) {
        val bytes = text.encodeToByteArray()
        val buf = DefaultAllocator.allocate(bytes.size)
        buf.writeByteArray(bytes, 0, bytes.size)
        notifyRead(buf)
    }

    private fun IoBuf.readString(): String {
        val bytes = ByteArray(readableBytes)
        readByteArray(bytes, 0, bytes.size)
        return bytes.decodeToString()
    }

    private fun collectWire(): String =
        transport.written.joinToString("") { it.readString() }

    @Test
    fun `hello returns 200 with expected body`() {
        createPipeline()
        channel.pipeline.feed("GET /hello HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n")

        val wire = collectWire()
        assertTrue(wire.startsWith("HTTP/1.1 200 OK\r\n"), "expected 200: $wire")
        assertTrue(wire.endsWith("Hello, World!"), "expected body: $wire")
    }

    @Test
    fun `sse-stream emits exactly one response and does not send unsolicited second response on keep-alive`() {
        // Regression test for K3: BenchmarkRoutingHandler was resetting currentPath
        // to null inside emitSseStream (called on HttpRequestHead) but did NOT set a
        // flag to suppress the HttpBodyEnd handler. When HttpBodyEnd arrived, it fell
        // to emitResponse(currentPath=null) → 404 Not Found. That second, unsolicited
        // response broke HTTP/1.1 keep-alive for all subsequent requests on the same
        // connection.
        createPipeline()

        // First SSE request.
        channel.pipeline.feed(
            "GET /sse-stream?count=2&size=5 HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n",
        )
        val wire = collectWire()

        // Must start with exactly one 200 OK, not a second HTTP/ response afterwards.
        val statusLineCount = "HTTP/1.1".toRegex().findAll(wire).count()
        assertEquals(
            1,
            statusLineCount,
            "expected exactly 1 HTTP response for SSE stream, got $statusLineCount. Wire:\n$wire",
        )
        assertTrue(wire.startsWith("HTTP/1.1 200 OK\r\n"), "expected 200: $wire")
        assertTrue(wire.contains("Transfer-Encoding: chunked"), "expected chunked: $wire")
        // Verify terminator present (complete chunked stream).
        assertTrue(wire.endsWith("0\r\n\r\n"), "expected chunked terminator: $wire")
    }

    // ----------------------------------------------------- compression=true wiring

    @Test
    fun `compression=true compresses GET hello response when client accepts gzip`() {
        // Verifies that installPipelineHttpHandlers(compression = true)
        // wires CompressionHandler on the outbound side: when the client
        // sends Accept-Encoding: gzip, the response's Content-Encoding
        // becomes gzip and the body bytes contain the RFC 1952 magic
        // (0x1f 0x8b) — i.e. the response went through gzip, not just an
        // unmodified pass-through.
        createPipeline(compression = true)
        channel.pipeline.feed(
            "GET /hello HTTP/1.1\r\nHost: localhost\r\nAccept-Encoding: gzip\r\nContent-Length: 0\r\n\r\n",
        )

        val wireBytes = transport.written.fold(ByteArray(0)) { acc, buf ->
            val n = buf.readableBytes
            val tmp = ByteArray(n)
            buf.readByteArray(tmp, 0, n)
            acc + tmp
        }
        // Header part is ASCII; only it gets decoded as String. The body
        // is binary gzip and is searched at byte level.
        val headerEndIdx = indexOfHeaderTerminator(wireBytes)
        assertTrue(headerEndIdx > 0, "expected end-of-headers marker")
        val headerWire = wireBytes.copyOfRange(0, headerEndIdx).decodeToString()
        assertTrue(headerWire.startsWith("HTTP/1.1 200 OK\r\n"), "expected 200: $headerWire")
        assertTrue(
            headerWire.contains("Content-Encoding: gzip", ignoreCase = true),
            "expected Content-Encoding: gzip header, got: $headerWire",
        )
        // Body region (after `\r\n\r\n`) — chunked framing: hex-size + CRLF +
        // raw bytes + CRLF. We just look for the gzip magic anywhere in
        // the body region rather than parse the chunk framing exactly.
        val body = wireBytes.copyOfRange(headerEndIdx + 4, wireBytes.size)
        val magic1 = body.indexOfFirst { it == 0x1f.toByte() }
        assertTrue(magic1 >= 0 && magic1 + 1 < body.size, "expected gzip ID1 byte in body region")
        assertEquals(0x8b.toByte(), body[magic1 + 1], "gzip ID2 byte must follow ID1")
    }

    /** Returns the index of `\r\n\r\n` (header terminator), or -1 if absent. */
    private fun indexOfHeaderTerminator(bytes: ByteArray): Int {
        for (i in 0..bytes.size - 4) {
            if (bytes[i] == 0x0d.toByte() &&
                bytes[i + 1] == 0x0a.toByte() &&
                bytes[i + 2] == 0x0d.toByte() &&
                bytes[i + 3] == 0x0a.toByte()
            ) return i
        }
        return -1
    }

    @Test
    fun `compression=true decompresses POST upload-stream body and reports decoded byte count`() {
        // Verifies that installPipelineHttpHandlers(compression = true)
        // wires HttpRequestDecompressionHandler on the inbound side: when
        // the client posts a gzip-encoded body with Content-Encoding: gzip,
        // the route handler sees the *decoded* bytes and X-Bytes-Received
        // reports the decoded length, not the wire (compressed) length.
        //
        // Regression-pin for the bundled fix in this PR: stripDecodedHeaders
        // previously called HttpHeaders.remove() which mutates in place and
        // corrupted the upstream HttpRequestDecoder's local head reference,
        // causing the body to never reach this handler. End-to-end
        // X-Bytes-Received: 0 reproduced the issue; this test pins the
        // decoded byte count.
        createPipeline(compression = true)
        val plain = "Hello, request decompression!".encodeToByteArray()
        val gzipped = gzipEncode(plain)
        val wireRequest = buildString {
            append("POST /upload-stream HTTP/1.1\r\n")
            append("Host: localhost\r\n")
            append("Content-Encoding: gzip\r\n")
            append("Content-Length: ${gzipped.size}\r\n\r\n")
        }
        // Feed headers + body as a single buffer.
        val headerBytes = wireRequest.encodeToByteArray()
        val combined = headerBytes + gzipped
        val buf = DefaultAllocator.allocate(combined.size)
        buf.writeByteArray(combined, 0, combined.size)
        channel.pipeline.notifyRead(buf)

        val wire = collectWire()
        assertTrue(wire.startsWith("HTTP/1.1 200 OK\r\n"), "expected 200: $wire")
        assertTrue(
            wire.contains("X-Bytes-Received: ${plain.size}", ignoreCase = true),
            "expected X-Bytes-Received: ${plain.size} (decoded), got: $wire",
        )
    }

    @Test
    fun `sse-stream followed by hello on keep-alive returns correct responses for both`() {
        // Verifies that the connection stays alive after SSE and the next request works.
        createPipeline()

        channel.pipeline.feed(
            "GET /sse-stream?count=1&size=3 HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n" +
                "GET /hello HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n",
        )
        val wire = collectWire()

        // Two HTTP responses total.
        val statusLineCount = "HTTP/1.1".toRegex().findAll(wire).count()
        assertEquals(2, statusLineCount, "expected 2 HTTP responses, got $statusLineCount. Wire:\n$wire")

        // Second response must be 200 Hello, World! (not 404 from the spurious emitResponse).
        assertTrue(wire.endsWith("Hello, World!"), "expected /hello body at end: $wire")
    }
}
