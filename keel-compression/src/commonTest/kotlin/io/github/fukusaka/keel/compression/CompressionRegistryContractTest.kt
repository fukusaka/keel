package io.github.fukusaka.keel.compression

import io.github.fukusaka.keel.buf.BufferAllocator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
 *  - encoder / decoder lookup is case-insensitive
 *  - [CompressionRegistry.registeredEncoders] exposes the registered set
 *
 * HTTP `Accept-Encoding` negotiation moved to `keel-codec-http`
 * (`ContentEncodingNegotiationTest`); the registry itself is
 * transport-agnostic.
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
    fun `registeredEncoders exposes the registered encoders with priority`() {
        val r = CompressionRegistry()
        r.registerEncoder(StubEncoder("gzip"), priority = 1)
        r.registerEncoder(StubEncoder("br"), priority = 10)

        val byName = r.registeredEncoders().associateBy { it.encoder.name }
        assertEquals(2, byName.size)
        assertEquals(1, byName["gzip"]?.priority)
        assertEquals(10, byName["br"]?.priority)
    }

    @Test
    fun `register after seal throws and lookups stay open`() {
        // S-D1 (4th deep-review): the backing maps are unsynchronized, so a
        // late register() racing per-request lookups would corrupt them.
        // seal() makes that misuse fail fast — register* throws, lookups
        // keep working.
        val r = CompressionRegistry()
        r.registerEncoder(StubEncoder("gzip"))
        r.registerDecoder(StubDecoder("gzip"))
        r.seal()

        // Lookups remain open after seal.
        assertNotNull(r.findEncoder("gzip"))
        assertNotNull(r.findDecoder("gzip"))
        assertEquals(1, r.registeredEncoders().size)

        // Any registration after seal throws.
        assertFailsWith<IllegalStateException> { r.registerEncoder(StubEncoder("br")) }
        assertFailsWith<IllegalStateException> { r.registerDecoder(StubDecoder("br")) }

        // seal() is idempotent.
        r.seal()
        assertNull(r.findEncoder("br"), "the rejected registration left no entry")
    }
}
