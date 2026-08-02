package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.DefaultAllocator
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * Tests for the handler's configuration: what it accepts, and what it refuses
 * to be constructed with.
 */
internal class HttpRequestDecompressionConfigTest : HttpRequestDecompressionFixture() {

    // --- config validation ---

    @Test
    fun `rejects a non-positive decompressionLimit at construction`() {
        // Pre-fix: a value < 1 (other than the Long.MAX_VALUE opt-out) trips the
        // size gate on the first decoded byte, silently rejecting every
        // compressed body. Post-fix: fails loudly at construction.
        assertFailsWith<IllegalArgumentException> {
            HttpRequestDecompressionHandler(registryWithLower, DefaultAllocator, decompressionLimit = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            HttpRequestDecompressionHandler(registryWithLower, DefaultAllocator, decompressionLimit = -1)
        }
        // The documented opt-out stays valid.
        HttpRequestDecompressionHandler(registryWithLower, DefaultAllocator, decompressionLimit = Long.MAX_VALUE)
    }

    @Test
    fun `rejects a non-positive ratioLimit at construction`() {
        assertFailsWith<IllegalArgumentException> {
            HttpRequestDecompressionHandler(registryWithLower, DefaultAllocator, ratioLimit = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            HttpRequestDecompressionHandler(registryWithLower, DefaultAllocator, ratioLimit = -5)
        }
        // The documented opt-out stays valid.
        HttpRequestDecompressionHandler(registryWithLower, DefaultAllocator, ratioLimit = Int.MAX_VALUE)
    }

    @Test
    fun `rejects a negative ratioBurst and non-positive scratchCapacity at construction`() {
        assertFailsWith<IllegalArgumentException> {
            HttpRequestDecompressionHandler(registryWithLower, DefaultAllocator, ratioBurst = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            HttpRequestDecompressionHandler(registryWithLower, DefaultAllocator, scratchCapacity = 0)
        }
        // Zero burst (single-shot trip) is the documented safe default.
        HttpRequestDecompressionHandler(registryWithLower, DefaultAllocator, ratioBurst = 0)
    }

    // --- passthrough ---

    @Test
    fun `no Content-Encoding header passes through`() {
        val state = ChainState()
        val handler = HttpRequestDecompressionHandler(registryWithLower, DefaultAllocator)
        val ctx = TestCtx(state)
        val head = HttpRequestHead(HttpMethod.POST, "/upload")

        handler.onRead(ctx, head)

        assertSame(head, state.reads.single())
    }

    @Test
    fun `Content-Encoding identity passes through without session`() {
        val state = ChainState()
        val handler = HttpRequestDecompressionHandler(registryWithLower, DefaultAllocator)
        val ctx = TestCtx(state)
        val head = HttpRequestHead(
            HttpMethod.POST,
            "/upload",
            headers = HttpHeaders().apply { add("Content-Encoding", "identity") },
        )

        handler.onRead(ctx, head)

        // Forwarded as-is — handler does not touch identity-encoded bodies.
        assertSame(head, state.reads.single())
    }
}
