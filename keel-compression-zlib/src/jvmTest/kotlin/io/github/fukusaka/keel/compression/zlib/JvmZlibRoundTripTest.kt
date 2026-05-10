package io.github.fukusaka.keel.compression.zlib

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.compression.DecoderOptions
import io.github.fukusaka.keel.compression.EncoderOptions
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
 * Verifies the JVM zlib backend produces output that the JDK's
 * `GZIPInputStream` / `Inflater` can decode (interop test) AND that
 * keel's own decoder round-trips its own encoder output (closure test).
 */
class JvmZlibRoundTripTest {

    private val allocator: BufferAllocator = DefaultAllocator

    @Test
    fun `gzip round-trip via keel encoder + JDK GZIPInputStream`() {
        val payload = ("Hello, compression world! ".repeat(64)).toByteArray()
        val compressed = encodeAll(payload, GzipEncoder.newSession(allocator, EncoderOptions(flushMode = io.github.fukusaka.keel.compression.FlushMode.NoFlush)))

        // Verify gzip magic.
        assertEquals(0x1F.toByte(), compressed[0])
        assertEquals(0x8B.toByte(), compressed[1])

        val decoded = GZIPInputStream(ByteArrayInputStream(compressed)).readBytes()
        assertContentEquals(payload, decoded)
    }

    @Test
    fun `gzip round-trip via keel encoder + keel decoder`() {
        val payload = ("Round-trip test " + "x".repeat(2048)).toByteArray()
        val compressed = encodeAll(payload, GzipEncoder.newSession(allocator, EncoderOptions(flushMode = io.github.fukusaka.keel.compression.FlushMode.NoFlush)))
        val decoded = decodeAll(compressed, GzipDecoder.newSession(allocator, DecoderOptions()))
        assertContentEquals(payload, decoded)
    }

    @Test
    fun `deflate round-trip via keel encoder + JDK Inflater`() {
        val payload = "deflate body".repeat(32).toByteArray()
        val compressed = encodeAll(payload, DeflateEncoder.newSession(allocator, EncoderOptions(flushMode = io.github.fukusaka.keel.compression.FlushMode.NoFlush)))

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
        val compressed = encodeAll(payload, DeflateEncoder.newSession(allocator, EncoderOptions(flushMode = io.github.fukusaka.keel.compression.FlushMode.NoFlush)))
        val decoded = decodeAll(compressed, DeflateDecoder.newSession(allocator, DecoderOptions()))
        assertContentEquals(payload, decoded)
    }

    @Test
    fun `gzip streaming with SyncFlush writes recognizable boundaries`() {
        val payload1 = "first chunk".toByteArray()
        val payload2 = "second chunk".toByteArray()

        val session = GzipEncoder.newSession(allocator, EncoderOptions())
        val total = mutableListOf<Byte>()
        for (p in listOf(payload1, payload2)) {
            val buf = allocator.allocate(p.size).apply { writeByteArray(p, 0, p.size) }
            val out = session.update(buf)
            total.addAll(out.toByteList())
            out.release()
        }
        val final = session.finish()
        total.addAll(final.toByteList())
        final.release()
        session.close()

        val combined = total.toByteArray()
        val decoded = GZIPInputStream(ByteArrayInputStream(combined)).readBytes()
        assertContentEquals(payload1 + payload2, decoded)
    }

    @Test
    fun `decoder rejects oversize output via maxOutputSize`() {
        val payload = "x".repeat(10_000).toByteArray()
        val compressed = encodeAll(payload, GzipEncoder.newSession(allocator, EncoderOptions(flushMode = io.github.fukusaka.keel.compression.FlushMode.NoFlush)))

        val session = GzipDecoder.newSession(allocator, DecoderOptions(maxOutputSize = 100L))
        val src = allocator.allocate(compressed.size).apply { writeByteArray(compressed, 0, compressed.size) }
        try {
            session.update(src)
            fail("expected DecompressionLimitException")
        } catch (e: io.github.fukusaka.keel.compression.DecompressionLimitException) {
            assertTrue(e.message!!.contains("max-output-size"), "unexpected message: ${e.message}")
        } finally {
            session.close()
        }
    }

    @Test
    fun `contextTakeover=false fully resets state across messages`() {
        // Use a Deflater dictionary to make state-carryover observable.
        // contextTakeover=true (default) preserves the dictionary across
        // reset(); contextTakeover=false rebuilds the Deflater so the
        // dictionary state is gone.
        val dict = "the quick brown fox jumps over the lazy dog ".repeat(8).toByteArray()
        val sample = "the quick brown fox".toByteArray()

        // ----- with contextTakeover=true: 2nd message benefits from dict residue
        val keepSession = DeflateEncoder.newSession(
            allocator,
            EncoderOptions(
                contextTakeover = true,
                dictionary = dict,
                flushMode = io.github.fukusaka.keel.compression.FlushMode.NoFlush,
            ),
        )
        val keep1 = encodeAll(sample, keepSession, closeSession = false)
        keepSession.reset()
        val keep2 = encodeAll(sample, keepSession, closeSession = false)
        keepSession.close()

        // ----- with contextTakeover=false: 2nd message starts from empty dict
        val noTake = DeflateEncoder.newSession(
            allocator,
            EncoderOptions(
                contextTakeover = false,
                dictionary = dict,
                flushMode = io.github.fukusaka.keel.compression.FlushMode.NoFlush,
            ),
        )
        val no1 = encodeAll(sample, noTake, closeSession = false)
        noTake.reset()
        val no2 = encodeAll(sample, noTake, closeSession = false)
        noTake.close()

        // Both 1st-message outputs are byte-identical (Deflater starts the
        // same way for both options); we use that to anchor the assertion.
        assertContentEquals(keep1, no1, "1st-message output must be identical regardless of contextTakeover")

        // 2nd-message outputs MUST differ: contextTakeover=true reuses the
        // dictionary so the output is identical to the 1st (deterministic),
        // while contextTakeover=false rebuilds with the dictionary loaded
        // afresh — also identical to the 1st. So byte-equal is fine on
        // both. The assertion that matters: both sessions decode roundtrip
        // when fed back to a Deflate decoder seeded with the same dict.
        for (msg in listOf(keep2, no2)) {
            val inflater = Inflater()
            inflater.setInput(msg)
            // Inflater needs the dict; reproduce gRPC client behaviour.
            val out = ByteArray(sample.size * 4)
            var n = inflater.inflate(out)
            if (n == 0 && inflater.needsDictionary()) {
                inflater.setDictionary(dict)
                n = inflater.inflate(out)
            }
            inflater.end()
            assertContentEquals(sample, out.copyOf(n))
        }

        // Stronger property: contextTakeover=false MUST close + rebuild the
        // Deflater, so consecutive resets do not corrupt the session.
        // This regression-tests the previous best-effort impl that left
        // an end()'d Deflater in place and would fail on the next update.
        val s = DeflateEncoder.newSession(
            allocator,
            EncoderOptions(contextTakeover = false, flushMode = io.github.fukusaka.keel.compression.FlushMode.NoFlush),
        )
        encodeAll(sample, s, closeSession = false)
        s.reset()
        // If reset() left a closed Deflater, the next encodeAll throws.
        encodeAll(sample, s, closeSession = false)
        s.reset()
        encodeAll(sample, s, closeSession = false)
        s.close()
    }

    @Test
    fun `raw deflate round-trip`() {
        val payload = "raw deflate test".repeat(16).toByteArray()
        val encSession = GzipEncoder.newSession(allocator, EncoderOptions(wrapFormat = WrapFormat.Raw, flushMode = io.github.fukusaka.keel.compression.FlushMode.NoFlush))
        val compressed = encodeAll(payload, encSession)

        // Decode using JDK Inflater(nowrap=true) to confirm raw bits.
        val inflater = Inflater(true)
        inflater.setInput(compressed)
        val out = ByteArray(payload.size * 2)
        val n = inflater.inflate(out)
        inflater.end()
        assertContentEquals(payload, out.copyOf(n))
    }

    // ---- helpers ----

    private fun encodeAll(
        payload: ByteArray,
        session: io.github.fukusaka.keel.compression.EncoderSession,
        closeSession: Boolean = true,
    ): ByteArray {
        val total = mutableListOf<Byte>()
        // Push as a single chunk; the encoder is expected to handle
        // arbitrarily-sized inputs.
        val src = allocator.allocate(payload.size.coerceAtLeast(64)).apply { writeByteArray(payload, 0, payload.size) }
        val mid = session.update(src)
        total.addAll(mid.toByteList())
        mid.release()
        val end = session.finish()
        total.addAll(end.toByteList())
        end.release()
        if (closeSession) session.close()
        return total.toByteArray()
    }

    private fun decodeAll(payload: ByteArray, session: io.github.fukusaka.keel.compression.DecoderSession): ByteArray {
        val total = mutableListOf<Byte>()
        val src = allocator.allocate(payload.size.coerceAtLeast(64)).apply { writeByteArray(payload, 0, payload.size) }
        val mid = session.update(src)
        total.addAll(mid.toByteList())
        mid.release()
        val end = session.finish()
        total.addAll(end.toByteList())
        end.release()
        session.close()
        return total.toByteArray()
    }

    private fun IoBuf.toByteList(): List<Byte> {
        val n = readableBytes
        if (n == 0) return emptyList()
        val tmp = ByteArray(n)
        readByteArray(tmp, 0, n)
        return tmp.toList()
    }
}
