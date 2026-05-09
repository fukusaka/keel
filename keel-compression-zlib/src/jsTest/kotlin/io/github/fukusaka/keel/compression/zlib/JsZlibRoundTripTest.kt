package io.github.fukusaka.keel.compression.zlib

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.compression.DecoderOptions
import io.github.fukusaka.keel.compression.DecoderSession
import io.github.fukusaka.keel.compression.EncoderOptions
import io.github.fukusaka.keel.compression.EncoderSession
import io.github.fukusaka.keel.compression.WrapFormat
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * JS (Node) zlib backend round-trip tests.
 *
 * Closure tests only — same shape as the Native tests. The wire format
 * is platform-agnostic so JVM-encode-JS-decode equivalence holds
 * transitively.
 */
class JsZlibRoundTripTest {

    private val allocator: BufferAllocator = DefaultAllocator

    @Test
    fun `gzip round-trip`() {
        val payload = "Hello, JS compression. ".repeat(64).encodeToByteArray()
        val compressed = encodeAll(payload, GzipEncoder.newSession(allocator, EncoderOptions()))
        // gzip magic
        assertEquals(0x1F.toByte(), compressed[0])
        assertEquals(0x8B.toByte(), compressed[1])
        val decoded = decodeAll(compressed, GzipDecoder.newSession(allocator, DecoderOptions()))
        assertContentEquals(payload, decoded)
    }

    @Test
    fun `deflate round-trip`() {
        val payload = "x".repeat(4096).encodeToByteArray()
        val compressed = encodeAll(payload, DeflateEncoder.newSession(allocator, EncoderOptions()))
        val decoded = decodeAll(compressed, DeflateDecoder.newSession(allocator, DecoderOptions()))
        assertContentEquals(payload, decoded)
    }

    @Test
    fun `raw deflate round-trip`() {
        val payload = "raw".repeat(64).encodeToByteArray()
        val encOpts = EncoderOptions(wrapFormat = WrapFormat.Raw)
        val decOpts = DecoderOptions(wrapFormat = WrapFormat.Raw)
        val compressed = encodeAll(payload, GzipEncoder.newSession(allocator, encOpts))
        val decoded = decodeAll(compressed, GzipDecoder.newSession(allocator, decOpts))
        assertContentEquals(payload, decoded)
    }

    private fun encodeAll(payload: ByteArray, session: EncoderSession): ByteArray {
        val src = allocator.allocate(payload.size.coerceAtLeast(64)).apply { writeByteArray(payload, 0, payload.size) }
        session.update(src).release()
        val end = session.finish()
        val n = end.readableBytes
        val out = ByteArray(n)
        if (n > 0) end.readByteArray(out, 0, n)
        end.release()
        session.close()
        return out
    }

    private fun decodeAll(payload: ByteArray, session: DecoderSession): ByteArray {
        val src = allocator.allocate(payload.size.coerceAtLeast(64)).apply { writeByteArray(payload, 0, payload.size) }
        session.update(src).release()
        val end = session.finish()
        val n = end.readableBytes
        val out = ByteArray(n)
        if (n > 0) end.readByteArray(out, 0, n)
        end.release()
        session.close()
        return out
    }
}
