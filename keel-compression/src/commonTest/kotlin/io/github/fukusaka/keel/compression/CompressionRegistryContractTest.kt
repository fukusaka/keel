package io.github.fukusaka.keel.compression

import io.github.fukusaka.keel.buf.BufferAllocator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Additional [CompressionRegistry] contract coverage beyond the
 * happy-path tests in [CompressionRegistryTest].
 *
 * Pins:
 *  - bidirectional registration via [CompressionRegistry.register]
 *  - `findEncoder` / `findDecoder` miss returns `null`
 *  - re-registration overwrites
 *  - decoder lookup is case-insensitive
 *  - blank / whitespace `Accept-Encoding` is parsed as identity-only
 *  - `parseAcceptEncoding` handles edge cases (trailing comma, mixed
 *    case tokens, multiple `;` parameters)
 *  - `*` wildcard interacts correctly with explicit `q=0` exclusions
 */
class CompressionRegistryContractTest {

    private class StubEncoder(override val name: String) : Encoder {
        override fun newSession(allocator: BufferAllocator, options: EncoderOptions): EncoderSession =
            error("not used by registry tests")
    }

    private class StubDecoder(override val name: String) : Decoder {
        override fun newSession(allocator: BufferAllocator, options: DecoderOptions): DecoderSession =
            error("not used by registry tests")
    }

    private class StubCodec(
        override val name: String,
        override val encoder: Encoder,
        override val decoder: Decoder,
    ) : CompressionCodec

    @Test
    fun `register installs both encoder and decoder`() {
        val r = CompressionRegistry()
        val enc = StubEncoder("gzip")
        val dec = StubDecoder("gzip")
        r.register(StubCodec("gzip", enc, dec))

        assertSame(enc, r.findEncoder("gzip"))
        assertSame(dec, r.findDecoder("gzip"))
    }

    @Test
    fun `findEncoder returns null for unknown name`() {
        val r = CompressionRegistry()
        assertNull(r.findEncoder("br"))
    }

    @Test
    fun `findDecoder returns null for unknown name`() {
        val r = CompressionRegistry()
        assertNull(r.findDecoder("br"))
    }

    @Test
    fun `findDecoder is case-insensitive`() {
        val r = CompressionRegistry()
        r.registerDecoder(StubDecoder("gzip"))
        assertEquals("gzip", r.findDecoder("GZIP")?.name)
        assertEquals("gzip", r.findDecoder("GzIp")?.name)
    }

    @Test
    fun `re-registering an encoder overwrites the previous instance`() {
        val r = CompressionRegistry()
        val first = StubEncoder("gzip")
        val second = StubEncoder("gzip")
        r.registerEncoder(first)
        r.registerEncoder(second)
        assertSame(second, r.findEncoder("gzip"))
    }

    @Test
    fun `re-registering a decoder overwrites the previous instance`() {
        val r = CompressionRegistry()
        val first = StubDecoder("gzip")
        val second = StubDecoder("gzip")
        r.registerDecoder(first)
        r.registerDecoder(second)
        assertSame(second, r.findDecoder("gzip"))
    }

    @Test
    fun `registerEncoder is case-insensitive on the key`() {
        // RFC 9110: Content-Encoding tokens are case-insensitive.
        val r = CompressionRegistry()
        r.registerEncoder(StubEncoder("GZIP"))
        // Whatever the original case used at registration, lookup must
        // succeed under either case.
        assertNotNull(r.findEncoder("gzip"))
        assertNotNull(r.findEncoder("GZIP"))
    }

    @Test
    fun `negotiate returns null when no encoders are registered`() {
        val r = CompressionRegistry()
        assertNull(r.negotiate("gzip"))
    }

    @Test
    fun `negotiate blank Accept-Encoding picks identity`() {
        val r = CompressionRegistry()
        r.registerEncoder(StubEncoder("gzip"))
        // Blank / whitespace header is treated as identity-only.
        assertNull(r.negotiate(""))
        assertNull(r.negotiate("   "))
    }

    @Test
    fun `negotiate explicit identity rejection with no other match returns null`() {
        val r = CompressionRegistry()
        r.registerEncoder(StubEncoder("gzip"))
        // Client rejects gzip AND identity; per KDoc, surfaces as null
        // so caller can decide 406.
        val pick = r.negotiate("gzip;q=0, identity;q=0")
        assertNull(pick)
    }

    @Test
    fun `negotiate q-zero on wildcard excludes unlisted encodings`() {
        val r = CompressionRegistry()
        r.registerEncoder(StubEncoder("gzip"))
        // `*;q=0, br;q=1.0` — only `br` is acceptable, gzip is not
        // registered for `br` so result is null (identity is also
        // unlisted → q=0 via wildcard, also rejected).
        val pick = r.negotiate("*;q=0, br;q=1.0")
        assertNull(pick)
    }

    @Test
    fun `negotiate accepts encoding with explicit q=1_0`() {
        val r = CompressionRegistry()
        r.registerEncoder(StubEncoder("gzip"))
        assertEquals("gzip", r.negotiate("gzip;q=1.0")?.name)
    }

    @Test
    fun `negotiate picks highest q on multi-entry header`() {
        val r = CompressionRegistry()
        r.registerEncoder(StubEncoder("gzip"))
        r.registerEncoder(StubEncoder("deflate"))
        r.registerEncoder(StubEncoder("br"))

        val pick = r.negotiate("gzip;q=0.3, deflate;q=0.9, br;q=0.6")
        assertEquals("deflate", pick?.name)
    }

    @Test
    fun `negotiate priority breaks q-tie regardless of registration order`() {
        val r = CompressionRegistry()
        // Register low-priority first, high-priority second — priority
        // (not insertion order) must win.
        r.registerEncoder(StubEncoder("gzip"), priority = 1)
        r.registerEncoder(StubEncoder("br"), priority = 10)
        val pick = r.negotiate("gzip, br")
        assertEquals("br", pick?.name)
    }

    @Test
    fun `negotiate token match is case-insensitive`() {
        val r = CompressionRegistry()
        r.registerEncoder(StubEncoder("gzip"))
        // Server registers lowercase, client sends uppercase.
        assertEquals("gzip", r.negotiate("GZIP")?.name)
    }

    @Test
    fun `parseAcceptEncoding handles trailing comma`() {
        val parsed = parseAcceptEncoding("gzip, br,")
        assertEquals(1.0, parsed["gzip"])
        assertEquals(1.0, parsed["br"])
    }

    @Test
    fun `parseAcceptEncoding lowercases tokens`() {
        val parsed = parseAcceptEncoding("GZIP;q=0.5, Br;q=1.0")
        assertEquals(0.5, parsed["gzip"])
        assertEquals(1.0, parsed["br"])
    }

    @Test
    fun `parseAcceptEncoding tolerates blank entries`() {
        val parsed = parseAcceptEncoding("gzip, , br")
        assertEquals(1.0, parsed["gzip"])
        assertEquals(1.0, parsed["br"])
    }

    @Test
    fun `parseAcceptEncoding default q is 1_0 when omitted`() {
        val parsed = parseAcceptEncoding("gzip")
        assertEquals(1.0, parsed["gzip"])
    }

    @Test
    fun `parseAcceptEncoding null returns empty map`() {
        val parsed = parseAcceptEncoding(null)
        assertEquals(0, parsed.size)
    }

    @Test
    fun `quality helper returns explicit token q over wildcard`() {
        val accepted = mapOf("gzip" to 0.5, "*" to 0.9)
        assertEquals(0.5, quality("gzip", accepted))
    }

    @Test
    fun `quality helper falls back to wildcard for unlisted token`() {
        val accepted = mapOf("gzip" to 0.5, "*" to 0.9)
        assertEquals(0.9, quality("br", accepted))
    }

    @Test
    fun `quality helper defaults identity to 1_0 when unlisted and no wildcard`() {
        val accepted = mapOf("gzip" to 0.5)
        assertEquals(1.0, quality("identity", accepted))
    }

    @Test
    fun `quality helper honours explicit identity q-zero`() {
        val accepted = mapOf("identity" to 0.0)
        assertEquals(0.0, quality("identity", accepted))
    }

    @Test
    fun `quality helper returns zero for unlisted token with no wildcard`() {
        val accepted = mapOf("gzip" to 1.0)
        assertEquals(0.0, quality("br", accepted))
    }

    @Test
    fun `quality helper identity match is case-insensitive`() {
        val accepted = emptyMap<String, Double>()
        assertEquals(1.0, quality("IDENTITY", accepted))
        assertEquals(1.0, quality("Identity", accepted))
    }
}
