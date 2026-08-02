package io.github.fukusaka.keel.server.ktor.compression

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for the Native [ContentEncoder] adapters backed by
 * `keel-compression-zlib`. Verifies the channel ↔ EncoderSession bridge
 * produces wire-compatible output that can be round-tripped through the
 * matching decoder.
 */
class KeelContentEncodersTest {

    @Test
    fun `gzip encoder name is gzip`() {
        assertEquals("gzip", KeelGZipEncoder.name)
    }

    @Test
    fun `deflate encoder name is deflate`() {
        assertEquals("deflate", KeelDeflateEncoder.name)
    }

    @Test
    fun `gzip encode then decode round trips small payload`() = runTest(timeout = 15.seconds) {
        val original = "hello, world!".encodeToByteArray()
        val roundTripped = roundTrip(KeelGZipEncoder, original)
        assertContentEquals(original, roundTripped)
    }

    @Test
    fun `deflate encode then decode round trips small payload`() = runTest(timeout = 15.seconds) {
        val original = "hello, deflate!".encodeToByteArray()
        val roundTripped = roundTrip(KeelDeflateEncoder, original)
        assertContentEquals(original, roundTripped)
    }

    @Test
    fun `gzip encode produces gzip magic bytes`() = runTest(timeout = 15.seconds) {
        val original = "compressible text content goes here".encodeToByteArray()
        val source = ByteReadChannel(original)
        val encoded = KeelGZipEncoder.encode(source).readRemaining().readByteArray()

        // RFC 1952 §2.3.1 gzip magic
        assertTrue(encoded.size >= 2, "encoded bytes too short")
        assertEquals(0x1f.toByte(), encoded[0], "gzip ID1 byte")
        assertEquals(0x8b.toByte(), encoded[1], "gzip ID2 byte")
    }

    @Test
    fun `deflate encode produces zlib header bytes`() = runTest(timeout = 15.seconds) {
        val original = "compressible text content".encodeToByteArray()
        val source = ByteReadChannel(original)
        val encoded = KeelDeflateEncoder.encode(source).readRemaining().readByteArray()

        // RFC 1950 §2.2: zlib starts with CMF + FLG. CMF lower nibble = 8 for deflate.
        assertTrue(encoded.size >= 2, "encoded bytes too short")
        assertEquals(0x08, encoded[0].toInt() and 0x0f, "zlib CMF method nibble")
    }

    @Test
    fun `gzip empty payload round trips`() = runTest(timeout = 15.seconds) {
        val empty = ByteArray(0)
        val roundTripped = roundTrip(KeelGZipEncoder, empty)
        assertContentEquals(empty, roundTripped)
    }

    @Test
    fun `gzip large payload round trips`() = runTest(timeout = 15.seconds) {
        // 100 KiB matches /large benchmark endpoint.
        val original = ByteArray(100 * 1024) { (it and 0xff).toByte() }
        val roundTripped = roundTrip(KeelGZipEncoder, original)
        assertContentEquals(original, roundTripped)
    }

    private suspend fun roundTrip(
        encoder: KeelContentEncoder,
        payload: ByteArray,
    ): ByteArray {
        val source = ByteReadChannel(payload)
        val encoded = encoder.encode(source)
        val decoded = encoder.decode(encoded)
        return decoded.readRemaining().readByteArray()
    }
}
