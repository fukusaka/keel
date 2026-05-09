package io.github.fukusaka.keel.compression

import io.github.fukusaka.keel.buf.BufferAllocator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Stub encoder used only to exercise registry / negotiation logic. */
private class StubEncoder(override val name: String) : Encoder {
    override fun newSession(allocator: BufferAllocator, options: EncoderOptions): EncoderSession =
        error("not used by registry tests")
}

class CompressionRegistryTest {

    @Test
    fun `negotiate picks highest q-value`() {
        val r = CompressionRegistry()
        r.registerEncoder(StubEncoder("gzip"))
        r.registerEncoder(StubEncoder("br"))

        val pick = r.negotiate("gzip;q=0.5, br;q=1.0")
        assertEquals("br", pick?.name)
    }

    @Test
    fun `negotiate respects priority on q-tie`() {
        val r = CompressionRegistry()
        r.registerEncoder(StubEncoder("gzip"), priority = 10)
        r.registerEncoder(StubEncoder("br"), priority = 5)

        val pick = r.negotiate("gzip, br")
        assertEquals("gzip", pick?.name)
    }

    @Test
    fun `negotiate skips q=0 entries`() {
        val r = CompressionRegistry()
        r.registerEncoder(StubEncoder("gzip"))

        val pick = r.negotiate("gzip;q=0, identity;q=1")
        assertNull(pick) // gzip rejected, identity preferred
    }

    @Test
    fun `negotiate returns null for identity preferred`() {
        val r = CompressionRegistry()
        r.registerEncoder(StubEncoder("gzip"))

        val pick = r.negotiate("identity")
        assertNull(pick)
    }

    @Test
    fun `negotiate null Accept-Encoding picks identity`() {
        val r = CompressionRegistry()
        r.registerEncoder(StubEncoder("gzip"))

        assertNull(r.negotiate(null))
    }

    @Test
    fun `negotiate ignores unknown tokens`() {
        val r = CompressionRegistry()
        r.registerEncoder(StubEncoder("gzip"))

        val pick = r.negotiate("zstd;q=1.0, gzip;q=0.5")
        assertEquals("gzip", pick?.name)
    }

    @Test
    fun `negotiate parses wildcard`() {
        val r = CompressionRegistry()
        r.registerEncoder(StubEncoder("gzip"))

        // Client says "anything except br". gzip not listed, but `*` covers it.
        val pick = r.negotiate("br;q=0, *;q=0.8")
        assertEquals("gzip", pick?.name)
    }

    @Test
    fun `parseAcceptEncoding tolerates malformed q`() {
        val parsed = parseAcceptEncoding("gzip;q=abc, br;q=0.5")
        assertEquals(1.0, parsed["gzip"]) // malformed q falls back to default
        assertEquals(0.5, parsed["br"])
    }

    @Test
    fun `parseAcceptEncoding clamps q to 0_0__1_0`() {
        val parsed = parseAcceptEncoding("gzip;q=2.0, br;q=-1.0")
        assertEquals(1.0, parsed["gzip"])
        assertEquals(0.0, parsed["br"])
    }

    @Test
    fun `findEncoder is case-insensitive`() {
        val r = CompressionRegistry()
        r.registerEncoder(StubEncoder("gzip"))
        assertEquals("gzip", r.findEncoder("GZIP")?.name)
    }

    @Suppress("unused")
    private fun unusedAllocator(): BufferAllocator? = null
}
