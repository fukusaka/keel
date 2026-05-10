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
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Streaming SPI (`update(input, output): CodecStatus`) round-trip tests.
 *
 * Verifies the JVM zlib backend produces output that the JDK's
 * `GZIPInputStream` / `Inflater` can decode (interop test) AND that
 * keel's own decoder round-trips its own encoder output, all driven
 * through the new caller-provided-output streaming SPI.
 *
 * Output buf size of 256 bytes is intentional: it forces the encoder
 * to repeatedly hit `NEED_OUTPUT` for any non-trivial input, exercising
 * the streaming chunk emit path even on small payloads.
 */
class JvmZlibRoundTripTest {

    private val allocator: BufferAllocator = DefaultAllocator
    private val outputCap = 256

    @Test
    fun `gzip round-trip via keel encoder + JDK GZIPInputStream`() {
        val payload = "Hello, compression world! ".repeat(64).toByteArray()
        val compressed = encodeAll(payload, GzipEncoder.newSession(allocator, EncoderOptions(flushMode = FlushMode.NoFlush)))

        // gzip magic
        assertEquals(0x1F.toByte(), compressed[0])
        assertEquals(0x8B.toByte(), compressed[1])

        val decoded = GZIPInputStream(ByteArrayInputStream(compressed)).readBytes()
        assertContentEquals(payload, decoded)
    }

    @Test
    fun `gzip round-trip via keel encoder + keel decoder`() {
        val payload = ("Round-trip test " + "x".repeat(2048)).toByteArray()
        val compressed = encodeAll(payload, GzipEncoder.newSession(allocator, EncoderOptions(flushMode = FlushMode.NoFlush)))
        val decoded = decodeAll(compressed, GzipDecoder.newSession(allocator, DecoderOptions()))
        assertContentEquals(payload, decoded)
    }

    @Test
    fun `deflate round-trip via keel encoder + JDK Inflater`() {
        val payload = "deflate body".repeat(32).toByteArray()
        val compressed = encodeAll(payload, DeflateEncoder.newSession(allocator, EncoderOptions(flushMode = FlushMode.NoFlush)))

        val inflater = Inflater()
        inflater.setInput(compressed)
        val out = ByteArray(payload.size * 2)
        val n = inflater.inflate(out)
        inflater.end()
        assertContentEquals(payload, out.copyOf(n))
    }

    @Test
    fun `deflate round-trip via keel encoder + keel decoder`() {
        val payload = "x".repeat(4096).toByteArray()
        val compressed = encodeAll(payload, DeflateEncoder.newSession(allocator, EncoderOptions(flushMode = FlushMode.NoFlush)))
        val decoded = decodeAll(compressed, DeflateDecoder.newSession(allocator, DecoderOptions()))
        assertContentEquals(payload, decoded)
    }

    @Test
    fun `streaming high-ratio decompression yields multiple bounded chunks`() {
        // 100 KB of 'x' compresses to ~140 bytes; decoding back should emit
        // many bounded chunks rather than a single 100 KB output.
        val payload = "x".repeat(100_000).toByteArray()
        val compressed = encodeAll(payload, GzipEncoder.newSession(allocator, EncoderOptions(flushMode = FlushMode.NoFlush)))
        val (decoded, chunkCount) = decodeAllWithChunkCount(compressed, GzipDecoder.newSession(allocator, DecoderOptions()))
        assertContentEquals(payload, decoded)
        assertTrue(
            chunkCount >= 100_000 / outputCap - 5,
            "expected many bounded chunks (decoded ${decoded.size} bytes in $chunkCount chunks of $outputCap)",
        )
    }

    @Test
    fun `decoder rejects oversize output via maxOutputSize after first chunk`() {
        val payload = "x".repeat(10_000).toByteArray()
        val compressed = encodeAll(payload, GzipEncoder.newSession(allocator, EncoderOptions(flushMode = FlushMode.NoFlush)))

        val session = GzipDecoder.newSession(allocator, DecoderOptions(maxOutputSize = 100L))
        val src = allocator.allocate(compressed.size).apply { writeByteArray(compressed, 0, compressed.size) }
        val output = allocator.allocate(outputCap)
        try {
            // First update should produce ≥ 100 bytes (well over cap), triggering exception.
            session.update(src, output)
            fail("expected DecompressionLimitException")
        } catch (e: DecompressionLimitException) {
            assertTrue(e.message!!.contains("max-output-size"), "unexpected message: ${e.message}")
        } finally {
            output.release()
            src.release()
            session.close()
        }
    }

    @Test
    fun `raw deflate round-trip`() {
        val payload = "raw deflate test".repeat(16).toByteArray()
        val encSession = GzipEncoder.newSession(
            allocator,
            EncoderOptions(wrapFormat = WrapFormat.Raw, flushMode = FlushMode.NoFlush),
        )
        val compressed = encodeAll(payload, encSession)

        val inflater = Inflater(true)
        inflater.setInput(compressed)
        val out = ByteArray(payload.size * 2)
        val n = inflater.inflate(out)
        inflater.end()
        assertContentEquals(payload, out.copyOf(n))
    }

    @Test
    fun `contextTakeover=false fully resets state across messages`() {
        val sample = "the quick brown fox".toByteArray()
        val session = DeflateEncoder.newSession(
            allocator,
            EncoderOptions(contextTakeover = false, flushMode = FlushMode.NoFlush),
        )
        // First message
        encodeAllWith(sample, session)
        session.reset()
        // Second + third — would have failed with the previous best-effort impl
        // (end()'d Deflater left in place).
        encodeAllWith(sample, session)
        session.reset()
        val third = encodeAllWith(sample, session)
        session.close()

        // 3rd-message decode must succeed independently — proves session state
        // was fully reset.
        val infl = Inflater()
        infl.setInput(third)
        val out = ByteArray(sample.size * 4)
        val n = infl.inflate(out)
        infl.end()
        assertContentEquals(sample, out.copyOf(n))
    }

    // ---- helpers ----

    /** Drive the encoder to completion via the streaming SPI; collect emitted bytes. */
    private fun encodeAll(payload: ByteArray, session: EncoderSession): ByteArray {
        val collected = encodeAllWith(payload, session)
        session.close()
        return collected
    }

    private fun encodeAllWith(payload: ByteArray, session: EncoderSession): ByteArray {
        val src = allocator.allocate(payload.size).apply { writeByteArray(payload, 0, payload.size) }
        val output = allocator.allocate(outputCap)
        val total = ArrayList<Byte>(payload.size)
        while (true) {
            when (session.update(src, output)) {
                CodecStatus.NEED_OUTPUT -> {
                    drainOutput(output, total)
                }
                CodecStatus.NEED_INPUT -> break
                CodecStatus.FINISHED -> error("update should not return FINISHED")
            }
        }
        // Drain any pending output bytes already in the buf.
        drainOutput(output, total)
        // Now finish.
        while (true) {
            when (session.finish(output)) {
                CodecStatus.NEED_OUTPUT -> drainOutput(output, total)
                CodecStatus.NEED_INPUT -> drainOutput(output, total)
                CodecStatus.FINISHED -> {
                    drainOutput(output, total)
                    break
                }
            }
        }
        output.release()
        src.release()
        return total.toByteArray()
    }

    private fun decodeAll(compressed: ByteArray, session: DecoderSession): ByteArray =
        decodeAllWithChunkCount(compressed, session).first

    private fun decodeAllWithChunkCount(compressed: ByteArray, session: DecoderSession): Pair<ByteArray, Int> {
        val src = allocator.allocate(compressed.size).apply { writeByteArray(compressed, 0, compressed.size) }
        val output = allocator.allocate(outputCap)
        val total = ArrayList<Byte>(compressed.size * 4)
        var chunkCount = 0
        while (true) {
            when (session.update(src, output)) {
                CodecStatus.NEED_OUTPUT -> {
                    chunkCount++
                    drainOutput(output, total)
                }
                CodecStatus.NEED_INPUT -> break
                CodecStatus.FINISHED -> error("update should not return FINISHED")
            }
        }
        if (output.readableBytes > 0) {
            chunkCount++
            drainOutput(output, total)
        }
        while (true) {
            when (session.finish(output)) {
                CodecStatus.NEED_OUTPUT -> {
                    chunkCount++
                    drainOutput(output, total)
                }
                CodecStatus.NEED_INPUT -> {
                    if (output.readableBytes > 0) drainOutput(output, total)
                    break
                }
                CodecStatus.FINISHED -> {
                    if (output.readableBytes > 0) drainOutput(output, total)
                    break
                }
            }
        }
        output.release()
        src.release()
        session.close()
        return total.toByteArray() to chunkCount
    }

    private fun drainOutput(output: IoBuf, dest: ArrayList<Byte>) {
        val n = output.readableBytes
        if (n == 0) return
        val tmp = ByteArray(n)
        output.readByteArray(tmp, 0, n)
        for (b in tmp) dest.add(b)
        output.clear()
    }

    private fun ArrayList<Byte>.toByteArray(): ByteArray {
        val out = ByteArray(size)
        for (i in indices) out[i] = this[i]
        return out
    }
}
