package io.github.fukusaka.keel.compression.zlib

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.compression.CodecStatus
import io.github.fukusaka.keel.compression.DecoderOptions
import io.github.fukusaka.keel.compression.DecoderSession
import io.github.fukusaka.keel.compression.DecompressionLimitException
import io.github.fukusaka.keel.compression.EncoderOptions
import io.github.fukusaka.keel.compression.EncoderSession
import io.github.fukusaka.keel.compression.FlushMode
import io.github.fukusaka.keel.compression.WrapFormat
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Streaming SPI round-trip tests for the Native zlib backend.
 *
 * Closure tests (Native ↔ Native). The wire format is platform-agnostic
 * so JVM-decode-Native-encode equivalence holds transitively from the
 * JVM `JdkZlib*` interop tests.
 */
class NativeZlibRoundTripTest {

    private val allocator: BufferAllocator = DefaultAllocator
    private val outputCap = 256

    @Test
    fun `gzip round-trip`() {
        val payload = "Hello, native compression. ".repeat(64).encodeToByteArray()
        val compressed = encodeAll(
            payload,
            GzipEncoder.newSession(allocator, EncoderOptions(flushMode = FlushMode.NoFlush)),
        )

        // gzip magic
        assertEquals(0x1F.toByte(), compressed[0])
        assertEquals(0x8B.toByte(), compressed[1])

        val decoded = decodeAll(compressed, GzipDecoder.newSession(allocator, DecoderOptions()))
        assertContentEquals(payload, decoded)
    }

    @Test
    fun `deflate round-trip`() {
        val payload = "x".repeat(4096).encodeToByteArray()
        val compressed = encodeAll(
            payload,
            DeflateEncoder.newSession(allocator, EncoderOptions(flushMode = FlushMode.NoFlush)),
        )
        val decoded = decodeAll(compressed, DeflateDecoder.newSession(allocator, DecoderOptions()))
        assertContentEquals(payload, decoded)
    }

    @Test
    fun `raw deflate round-trip`() {
        val payload = "raw deflate test".repeat(16).encodeToByteArray()
        val encOpts = EncoderOptions(wrapFormat = WrapFormat.Raw, flushMode = FlushMode.NoFlush)
        val decOpts = DecoderOptions(wrapFormat = WrapFormat.Raw)
        val compressed = encodeAll(payload, GzipEncoder.newSession(allocator, encOpts))
        val decoded = decodeAll(compressed, GzipDecoder.newSession(allocator, decOpts))
        assertContentEquals(payload, decoded)
    }

    @Test
    fun `streaming high-ratio decompression yields multiple bounded chunks`() {
        val payload = "x".repeat(100_000).encodeToByteArray()
        val compressed = encodeAll(
            payload,
            GzipEncoder.newSession(allocator, EncoderOptions(flushMode = FlushMode.NoFlush)),
        )
        val (decoded, chunkCount) = decodeAllWithChunkCount(
            compressed,
            GzipDecoder.newSession(allocator, DecoderOptions()),
        )
        assertContentEquals(payload, decoded)
        assertTrue(chunkCount >= 100_000 / outputCap - 5, "expected many bounded chunks (got $chunkCount)")
    }

    @Test
    fun `decoder rejects oversize via maxOutputSize`() {
        val payload = "x".repeat(10_000).encodeToByteArray()
        val compressed = encodeAll(
            payload,
            GzipEncoder.newSession(allocator, EncoderOptions(flushMode = FlushMode.NoFlush)),
        )

        val session = GzipDecoder.newSession(allocator, DecoderOptions(maxOutputSize = 100L))
        val src = allocator.allocate(compressed.size).apply { writeByteArray(compressed, 0, compressed.size) }
        val output = allocator.allocate(outputCap)
        try {
            session.update(src, output)
            fail("expected DecompressionLimitException")
        } catch (_: DecompressionLimitException) {
            // expected
        } finally {
            output.release()
            src.release()
            session.close()
        }
    }

    // ---- helpers ----

    private fun encodeAll(payload: ByteArray, session: EncoderSession): ByteArray {
        val src = allocator.allocate(payload.size).apply { writeByteArray(payload, 0, payload.size) }
        val output = allocator.allocate(outputCap)
        val total = mutableListOf<Byte>()
        while (true) {
            when (session.update(src, output)) {
                CodecStatus.NEED_OUTPUT -> drainOutput(output, total)
                CodecStatus.NEED_INPUT -> break
                CodecStatus.FINISHED -> error("update should not return FINISHED")
            }
        }
        drainOutput(output, total)
        var finishing = true
        while (finishing) {
            when (session.finish(output)) {
                CodecStatus.NEED_OUTPUT -> drainOutput(output, total)
                CodecStatus.NEED_INPUT, CodecStatus.FINISHED -> {
                    drainOutput(output, total)
                    finishing = false
                }
            }
        }
        output.release()
        src.release()
        session.close()
        return ByteArray(total.size) { i -> total[i] }
    }

    private fun decodeAll(compressed: ByteArray, session: DecoderSession): ByteArray =
        decodeAllWithChunkCount(compressed, session).first

    private fun decodeAllWithChunkCount(compressed: ByteArray, session: DecoderSession): Pair<ByteArray, Int> {
        val src = allocator.allocate(compressed.size).apply { writeByteArray(compressed, 0, compressed.size) }
        val output = allocator.allocate(outputCap)
        val total = mutableListOf<Byte>()
        var chunks = 0
        while (true) {
            when (session.update(src, output)) {
                CodecStatus.NEED_OUTPUT -> {
                    chunks++
                    drainOutput(output, total)
                }
                CodecStatus.NEED_INPUT -> break
                CodecStatus.FINISHED -> error("update should not return FINISHED")
            }
        }
        if (output.readableBytes > 0) {
            chunks++
            drainOutput(output, total)
        }
        var finishing = true
        while (finishing) {
            when (session.finish(output)) {
                CodecStatus.NEED_OUTPUT -> {
                    chunks++
                    drainOutput(output, total)
                }
                CodecStatus.NEED_INPUT, CodecStatus.FINISHED -> {
                    if (output.readableBytes > 0) drainOutput(output, total)
                    finishing = false
                }
            }
        }
        output.release()
        src.release()
        session.close()
        return ByteArray(total.size) { i -> total[i] } to chunks
    }

    private fun drainOutput(output: IoBuf, dest: MutableList<Byte>) {
        val n = output.readableBytes
        if (n == 0) return
        val tmp = ByteArray(n)
        output.readByteArray(tmp, 0, n)
        for (b in tmp) dest.add(b)
        output.clear()
    }
}
