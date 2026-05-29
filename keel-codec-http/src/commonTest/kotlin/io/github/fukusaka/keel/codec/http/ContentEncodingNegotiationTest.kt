package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.compression.CompressionRegistry
import io.github.fukusaka.keel.compression.Encoder
import io.github.fukusaka.keel.compression.EncoderOptions
import io.github.fukusaka.keel.compression.EncoderSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for the HTTP `Accept-Encoding` content negotiation
 * (RFC 9110 §12.5.3) lifted into `keel-codec-http`: [negotiateContentEncoding]
 * selection, [parseAcceptEncoding] grammar, and the [encodingQuality]
 * wildcard / implicit-identity rules. The q-value parse defers to the
 * shared [weightMillisOf]. Pure synchronous logic — no timeout needed.
 */
class ContentEncodingNegotiationTest {

    private class StubEncoder(override val name: String) : Encoder {
        override fun newSession(allocator: BufferAllocator, options: EncoderOptions): EncoderSession =
            error("not used by negotiation tests")
    }

    private fun registryOf(vararg encoders: Pair<String, Int>): CompressionRegistry {
        val r = CompressionRegistry()
        for ((name, priority) in encoders) r.registerEncoder(StubEncoder(name), priority)
        return r
    }

    @Test
    fun `negotiate returns null when no encoders are registered`() {
        assertNull(negotiateContentEncoding(CompressionRegistry(), "gzip"))
    }

    @Test
    fun `negotiate blank Accept-Encoding has no compressed match`() {
        val r = registryOf("gzip" to 0)
        // Blank / whitespace header is treated as identity-only → no
        // registered (compressed) encoder is acceptable.
        assertNull(negotiateContentEncoding(r, ""))
        assertNull(negotiateContentEncoding(r, "   "))
    }

    @Test
    fun `negotiate explicit identity rejection with no other match returns null`() {
        val r = registryOf("gzip" to 0)
        assertNull(negotiateContentEncoding(r, "gzip;q=0, identity;q=0"))
    }

    @Test
    fun `negotiate q-zero on wildcard excludes unlisted encodings`() {
        val r = registryOf("gzip" to 0)
        assertNull(negotiateContentEncoding(r, "*;q=0, br;q=1.0"))
    }

    @Test
    fun `negotiate accepts encoding with explicit q equals 1`() {
        val r = registryOf("gzip" to 0)
        assertEquals("gzip", negotiateContentEncoding(r, "gzip;q=1.0")?.name)
    }

    @Test
    fun `negotiate picks highest q on a multi-entry header`() {
        val r = registryOf("gzip" to 0, "deflate" to 0, "br" to 0)
        assertEquals("deflate", negotiateContentEncoding(r, "gzip;q=0.3, deflate;q=0.9, br;q=0.6")?.name)
    }

    @Test
    fun `negotiate priority breaks a q-tie regardless of registration order`() {
        // Register low-priority first, high-priority second — priority,
        // not insertion order, must win.
        val r = registryOf("gzip" to 1, "br" to 10)
        assertEquals("br", negotiateContentEncoding(r, "gzip, br")?.name)
    }

    @Test
    fun `negotiate token match is case-insensitive`() {
        val r = registryOf("gzip" to 0)
        assertEquals("gzip", negotiateContentEncoding(r, "GZIP")?.name)
    }

    @Test
    fun `negotiate ignores unregistered tokens`() {
        val r = registryOf("gzip" to 0)
        // zstd is unregistered → the registered gzip wins despite its lower q.
        assertEquals("gzip", negotiateContentEncoding(r, "zstd;q=1.0, gzip;q=0.5")?.name)
    }

    @Test
    fun `negotiate wildcard makes an unlisted registered encoding acceptable`() {
        val r = registryOf("gzip" to 0)
        // "anything except br" — gzip is unlisted but covered by `*;q=0.8`.
        assertEquals("gzip", negotiateContentEncoding(r, "br;q=0, *;q=0.8")?.name)
    }

    @Test
    fun `parseAcceptEncoding handles a trailing comma`() {
        val parsed = parseAcceptEncoding("gzip, br,")
        assertEquals(WEIGHT_MILLI, parsed["gzip"])
        assertEquals(WEIGHT_MILLI, parsed["br"])
    }

    @Test
    fun `parseAcceptEncoding lowercases tokens and scales q to milli`() {
        val parsed = parseAcceptEncoding("GZIP;q=0.5, Br;q=1.0")
        assertEquals(500, parsed["gzip"])
        assertEquals(WEIGHT_MILLI, parsed["br"])
    }

    @Test
    fun `parseAcceptEncoding tolerates blank entries`() {
        val parsed = parseAcceptEncoding("gzip, , br")
        assertEquals(WEIGHT_MILLI, parsed["gzip"])
        assertEquals(WEIGHT_MILLI, parsed["br"])
    }

    @Test
    fun `parseAcceptEncoding default q is 1000 milli when omitted`() {
        assertEquals(WEIGHT_MILLI, parseAcceptEncoding("gzip")["gzip"])
    }

    @Test
    fun `parseAcceptEncoding null returns an empty map`() {
        assertEquals(0, parseAcceptEncoding(null).size)
    }

    @Test
    fun `encodingQuality returns the explicit token q over the wildcard`() {
        val accepted = mapOf("gzip" to 500, "*" to 900)
        assertEquals(500, encodingQuality("gzip", accepted))
    }

    @Test
    fun `encodingQuality falls back to the wildcard for an unlisted token`() {
        val accepted = mapOf("gzip" to 500, "*" to 900)
        assertEquals(900, encodingQuality("br", accepted))
    }

    @Test
    fun `encodingQuality defaults identity to 1000 milli when unlisted and no wildcard`() {
        assertEquals(WEIGHT_MILLI, encodingQuality("identity", mapOf("gzip" to 500)))
    }

    @Test
    fun `encodingQuality honours an explicit identity q-zero`() {
        assertEquals(0, encodingQuality("identity", mapOf("identity" to 0)))
    }

    @Test
    fun `encodingQuality returns zero for an unlisted token with no wildcard`() {
        assertEquals(0, encodingQuality("br", mapOf("gzip" to WEIGHT_MILLI)))
    }

    @Test
    fun `encodingQuality identity match is case-insensitive`() {
        assertEquals(WEIGHT_MILLI, encodingQuality("IDENTITY", emptyMap()))
        assertEquals(WEIGHT_MILLI, encodingQuality("Identity", emptyMap()))
    }
}
