package io.github.fukusaka.keel.compression.zlib

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
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
import kotlin.test.fail

/**
 * Native (Kotlin/Native) zlib backend round-trip tests.
 *
 * Closure tests only — Native doesn't have a JDK to interop against on
 * the test side. The JVM `JvmZlibRoundTripTest` already verifies our
 * gzip output decodes via JDK `GZIPInputStream`, and the wire format is
 * platform-agnostic, so JVM-decode-Native-encode equivalence holds
 * transitively if both backends individually round-trip themselves.
 */
class NativeZlibRoundTripTest {

    private val allocator: BufferAllocator = DefaultAllocator

    @Test
    fun `gzip round-trip`() {
        val payload = "Hello, native compression. ".repeat(64).encodeToByteArray()
        val compressed = encodeAll(payload, GzipEncoder.newSession(allocator, EncoderOptions(flushMode = FlushMode.NoFlush)))

        // gzip magic
        assertEquals(0x1F.toByte(), compressed[0])
        assertEquals(0x8B.toByte(), compressed[1])

        val decoded = decodeAll(compressed, GzipDecoder.newSession(allocator, DecoderOptions()))
        assertContentEquals(payload, decoded)
    }

    @Test
    fun `deflate round-trip`() {
        val payload = "x".repeat(4096).encodeToByteArray()
        val compressed = encodeAll(payload, DeflateEncoder.newSession(allocator, EncoderOptions(flushMode = FlushMode.NoFlush)))
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
    fun `decoder rejects oversize via maxOutputSize`() {
        val payload = "x".repeat(10_000).encodeToByteArray()
        val compressed = encodeAll(payload, GzipEncoder.newSession(allocator, EncoderOptions(flushMode = FlushMode.NoFlush)))

        val session = GzipDecoder.newSession(allocator, DecoderOptions(maxOutputSize = 100L))
        val src = allocator.allocate(compressed.size).apply { writeByteArray(compressed, 0, compressed.size) }
        try {
            session.update(src)
            fail("expected DecompressionLimitException")
        } catch (_: DecompressionLimitException) {
            // expected
        } finally {
            session.close()
        }
    }

    @Test
    fun `streaming SyncFlush across multiple updates`() {
        val payload1 = "first chunk".encodeToByteArray()
        val payload2 = "second chunk".encodeToByteArray()
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

        val decoded = decodeAll(ByteArray(total.size) { i -> total[i] }, GzipDecoder.newSession(allocator, DecoderOptions()))
        assertContentEquals(payload1 + payload2, decoded)
    }

    // ---- helpers ----

    private fun encodeAll(payload: ByteArray, session: EncoderSession): ByteArray {
        val total = mutableListOf<Byte>()
        val src = allocator.allocate(payload.size.coerceAtLeast(64)).apply { writeByteArray(payload, 0, payload.size) }
        val mid = session.update(src)
        total.addAll(mid.toByteList())
        mid.release()
        val end = session.finish()
        total.addAll(end.toByteList())
        end.release()
        session.close()
        return ByteArray(total.size) { i -> total[i] }
    }

    private fun decodeAll(payload: ByteArray, session: DecoderSession): ByteArray {
        val total = mutableListOf<Byte>()
        val src = allocator.allocate(payload.size.coerceAtLeast(64)).apply { writeByteArray(payload, 0, payload.size) }
        val mid = session.update(src)
        total.addAll(mid.toByteList())
        mid.release()
        val end = session.finish()
        total.addAll(end.toByteList())
        end.release()
        session.close()
        return ByteArray(total.size) { i -> total[i] }
    }

    private fun IoBuf.toByteList(): List<Byte> {
        val n = readableBytes
        if (n == 0) return emptyList()
        val tmp = ByteArray(n)
        readByteArray(tmp, 0, n)
        return tmp.toList()
    }
}
