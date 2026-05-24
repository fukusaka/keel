package io.github.fukusaka.keel.compression.zlib

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.compression.CodecStatus
import io.github.fukusaka.keel.compression.DecoderOptions
import io.github.fukusaka.keel.compression.DecoderSession
import io.github.fukusaka.keel.compression.DecompressionException
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * RFC 1952 gzip header coverage tests for the chunk-aware
 * [GzipHeaderParser] and the [GzipDecoder] session that uses it.
 *
 * Verifies:
 *   - FNAME / FCOMMENT / FEXTRA / FHCRC parse correctly
 *   - any byte split point through the header still yields the same
 *     parse result (chunk-boundary safety — the bug class the manual
 *     parser exists to solve)
 *   - GzipDecoder round-trips synthetic gzip streams with all four
 *     optional fields set
 *   - invalid magic / unsupported CM / reserved FLG bits throw
 */
class GzipHeaderParserTest {

    private val sample = "the quick brown fox jumps over the lazy dog ".repeat(8).toByteArray()

    @Test
    fun `parses gzip header with FNAME via keel decoder`() {
        val gzip = makeGzipWithFlags(sample, fname = "data.txt")
        val decoded = decodeAll(gzip)
        assertContentEquals(sample, decoded)
    }

    @Test
    fun `parses gzip header with FCOMMENT via keel decoder`() {
        val gzip = makeGzipWithFlags(sample, fcomment = "test comment")
        val decoded = decodeAll(gzip)
        assertContentEquals(sample, decoded)
    }

    @Test
    fun `parses gzip header with FEXTRA via keel decoder`() {
        val gzip = makeGzipWithFlags(sample, fextra = byteArrayOf(0x01, 0x02, 0x03, 0x04))
        val decoded = decodeAll(gzip)
        assertContentEquals(sample, decoded)
    }

    @Test
    fun `parses gzip header with FHCRC via keel decoder`() {
        val gzip = makeGzipWithFlags(sample, fhcrc = true)
        val decoded = decodeAll(gzip)
        assertContentEquals(sample, decoded)
    }

    @Test
    fun `parses gzip header with all four optional fields via keel decoder`() {
        val gzip = makeGzipWithFlags(
            sample,
            fname = "all-fields.bin",
            fcomment = "this stream has every gzip optional field",
            fextra = ByteArray(64) { it.toByte() },
            fhcrc = true,
        )
        val decoded = decodeAll(gzip)
        assertContentEquals(sample, decoded)
    }

    @Test
    fun `chunk split at every byte still parses`() {
        val gzip = makeGzipWithFlags(sample, fname = "x.bin", fcomment = "c", fhcrc = true)
        // Feed one byte at a time. The decoder session must produce the same
        // output as the contiguous case — verifies chunk-boundary safety
        // through the gzip header AND through the deflate body.
        val session = GzipDecoder.newSession(DefaultAllocator, DecoderOptions())
        val output = DefaultAllocator.allocate(256)
        val collected = mutableListOf<Byte>()
        for (i in gzip.indices) {
            val src = DefaultAllocator.allocate(1).apply { writeByteArray(gzip, i, 1) }
            // Drive update until NEED_INPUT (single byte fully consumed or buffered).
            var done = false
            while (!done) {
                when (session.update(src, output)) {
                    CodecStatus.NEED_OUTPUT -> drainOutput(output, collected)
                    CodecStatus.NEED_INPUT -> done = true
                    CodecStatus.FINISHED -> error("update should not return FINISHED")
                }
            }
            drainOutput(output, collected)
            src.release()
        }
        // Drive finish.
        var finishing = true
        while (finishing) {
            when (session.finish(output)) {
                CodecStatus.NEED_OUTPUT -> drainOutput(output, collected)
                CodecStatus.NEED_INPUT, CodecStatus.FINISHED -> {
                    drainOutput(output, collected)
                    finishing = false
                }
            }
        }
        output.release()
        session.close()
        assertContentEquals(sample, collected.toByteArray())
    }

    private fun drainOutput(output: IoBuf, dest: MutableList<Byte>) {
        val n = output.readableBytes
        if (n == 0) return
        val tmp = ByteArray(n)
        output.readByteArray(tmp, 0, n)
        for (b in tmp) dest.add(b)
        output.clear()
    }

    @Test
    fun `parser direct - FEXTRA with zero XLEN`() {
        val parser = GzipHeaderParser()
        val header = byteArrayOf(
            0x1F.toByte(), 0x8B.toByte(), 0x08, 0x04, // magic CM FLG=FEXTRA
            0, 0, 0, 0, // MTIME
            0, 0xFF.toByte(), // XFL OS
            0, 0, // XLEN = 0
        )
        val tail = parser.consume(header)
        assertTrue(parser.done, "parser should complete on the FEXTRA-with-XLEN-0 case")
        assertNotNull(tail)
        assertEquals(0, tail.size)
    }

    @Test
    fun `parser direct - rejects invalid magic`() {
        val parser = GzipHeaderParser()
        try {
            parser.consume(byteArrayOf(0x42, 0x8B.toByte()))
            fail("expected DecompressionException")
        } catch (e: DecompressionException) {
            assertTrue(e.message!!.contains("ID1") || e.message!!.contains("magic"), e.message)
        }
    }

    @Test
    fun `parser direct - rejects reserved FLG bits`() {
        val parser = GzipHeaderParser()
        try {
            parser.consume(byteArrayOf(0x1F.toByte(), 0x8B.toByte(), 0x08, 0xE0.toByte()))
            fail("expected DecompressionException")
        } catch (e: DecompressionException) {
            assertTrue(e.message!!.contains("reserved"), e.message)
        }
    }

    @Test
    fun `parser direct - returns null when chunk too short`() {
        val parser = GzipHeaderParser()
        // Only 5 bytes — header needs at least 10 for the fixed portion.
        val tail = parser.consume(byteArrayOf(0x1F.toByte(), 0x8B.toByte(), 0x08, 0, 0))
        assertNull(tail)
        assertEquals(false, parser.done)
    }

    // ---- helpers ----

    private fun decodeAll(payload: ByteArray): ByteArray {
        val session = GzipDecoder.newSession(DefaultAllocator, DecoderOptions())
        return decodeWith(payload, session)
    }

    private fun decodeWith(payload: ByteArray, session: DecoderSession): ByteArray {
        val src = DefaultAllocator.allocate(payload.size).apply { writeByteArray(payload, 0, payload.size) }
        val output = DefaultAllocator.allocate(256)
        val collected = mutableListOf<Byte>()
        var done = false
        while (!done) {
            when (session.update(src, output)) {
                CodecStatus.NEED_OUTPUT -> drainOutput(output, collected)
                CodecStatus.NEED_INPUT -> done = true
                CodecStatus.FINISHED -> error("update should not return FINISHED")
            }
        }
        drainOutput(output, collected)
        var finishing = true
        while (finishing) {
            when (session.finish(output)) {
                CodecStatus.NEED_OUTPUT -> drainOutput(output, collected)
                CodecStatus.NEED_INPUT, CodecStatus.FINISHED -> {
                    drainOutput(output, collected)
                    finishing = false
                }
            }
        }
        output.release()
        src.release()
        session.close()
        return collected.toByteArray()
    }

    private fun MutableList<Byte>.toByteArray(): ByteArray {
        val out = ByteArray(size)
        for (i in indices) out[i] = this[i]
        return out
    }

    /**
     * Build a gzip stream with the requested optional fields. Uses
     * `Deflater(nowrap=true)` to produce the deflate body and writes
     * the gzip header / trailer manually so we can control FLG bits
     * exactly. (`GZIPOutputStream` only emits FLG=0.)
     */
    private fun makeGzipWithFlags(
        data: ByteArray,
        fname: String? = null,
        fcomment: String? = null,
        fextra: ByteArray? = null,
        fhcrc: Boolean = false,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        var flg = 0
        if (fhcrc) flg = flg or 0x02
        if (fextra != null) flg = flg or 0x04
        if (fname != null) flg = flg or 0x08
        if (fcomment != null) flg = flg or 0x10

        // Build the to-be-CRC'd header bytes first (excluding the FHCRC
        // bytes themselves, per RFC 1952 §2.3.1.2).
        val headerBuf = ByteArrayOutputStream()
        headerBuf.write(byteArrayOf(0x1F.toByte(), 0x8B.toByte(), 0x08, flg.toByte(), 0, 0, 0, 0, 0, 0xFF.toByte()))
        if (fextra != null) {
            headerBuf.write(fextra.size and 0xFF)
            headerBuf.write((fextra.size ushr 8) and 0xFF)
            headerBuf.write(fextra)
        }
        if (fname != null) {
            headerBuf.write(fname.toByteArray())
            headerBuf.write(0)
        }
        if (fcomment != null) {
            headerBuf.write(fcomment.toByteArray())
            headerBuf.write(0)
        }
        out.write(headerBuf.toByteArray())
        if (fhcrc) {
            // 16-bit CRC32 (LE) of the header preceding the FHCRC bytes.
            val crc = java.util.zip.CRC32().apply { update(headerBuf.toByteArray()) }.value.toInt() and 0xFFFF
            out.write(crc and 0xFF)
            out.write((crc ushr 8) and 0xFF)
        }
        // Deflate body.
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        deflater.setInput(data)
        deflater.finish()
        val scratch = ByteArray(data.size + 64)
        val n = deflater.deflate(scratch)
        deflater.end()
        out.write(scratch, 0, n)
        // Trailer: CRC32(LE) + ISIZE(LE).
        val crc = java.util.zip.CRC32().apply { update(data) }.value
        out.write((crc and 0xFFL).toInt())
        out.write(((crc shr 8) and 0xFFL).toInt())
        out.write(((crc shr 16) and 0xFFL).toInt())
        out.write(((crc shr 24) and 0xFFL).toInt())
        val isize = data.size.toLong() and 0xFFFFFFFFL
        out.write((isize and 0xFFL).toInt())
        out.write(((isize shr 8) and 0xFFL).toInt())
        out.write(((isize shr 16) and 0xFFL).toInt())
        out.write(((isize shr 24) and 0xFFL).toInt())
        return out.toByteArray()
    }

    @Suppress("unused")
    private val unusedGzipStream = GZIPOutputStream::class // keep import for sanity check
}
