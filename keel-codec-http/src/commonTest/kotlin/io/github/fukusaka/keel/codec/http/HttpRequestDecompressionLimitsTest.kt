package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.compression.CompressionRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tests for the limits: the unknown-encoding policy, the absolute and ratio
 * caps, and their reset between requests.
 */
internal class HttpRequestDecompressionLimitsTest : HttpRequestDecompressionFixture() {

    // -------------------------------------------------------------- unknown encoding policy

    @Test
    fun `UnsupportedMediaType policy throws on unknown encoding`() {
        val state = ChainState()
        val handler = HttpRequestDecompressionHandler(
            registryWithLower,
            DefaultAllocator,
            unknownEncodingPolicy = UnknownEncodingPolicy.UnsupportedMediaType,
        )
        val ctx = TestCtx(state)
        val ex = assertFailsWith<UnsupportedContentEncodingException> {
            handler.onRead(
                ctx,
                HttpRequestHead(
                    HttpMethod.POST,
                    "/upload",
                    headers = HttpHeaders().apply { add("Content-Encoding", "br") },
                ),
            )
        }
        assertEquals("br", ex.encoding)
        assertEquals(UnknownEncodingPolicy.UnsupportedMediaType, ex.policy)
        assertTrue(state.reads.isEmpty(), "head should not propagate when rejected")
    }

    @Test
    fun `BadRequest policy throws on unknown encoding`() {
        val state = ChainState()
        val handler = HttpRequestDecompressionHandler(
            registryWithLower,
            DefaultAllocator,
            unknownEncodingPolicy = UnknownEncodingPolicy.BadRequest,
        )
        val ctx = TestCtx(state)
        val ex = assertFailsWith<UnsupportedContentEncodingException> {
            handler.onRead(
                ctx,
                HttpRequestHead(
                    HttpMethod.POST,
                    "/upload",
                    headers = HttpHeaders().apply { add("Content-Encoding", "zstd") },
                ),
            )
        }
        assertEquals(UnknownEncodingPolicy.BadRequest, ex.policy)
    }

    @Test
    fun `multi-token Content-Encoding rejection covers common lexical variants`() {
        // The Netty-style "split on comma, take the first token" shortcut would
        // accept every one of these as `lower` and silently misinterpret the
        // request body. keel intentionally rejects any value whose verbatim
        // string is not a single registered codec name — verbatim lookup means
        // whitespace, parameters (`;q=`), and stray separators all defeat the
        // match. Pin the policy against a representative set of lexical
        // variants so a future refactor that tries to "normalise" the lookup
        // (which would inadvertently re-enable the Netty shortcut) trips here.
        val variants = listOf(
            "lower, gzip", // canonical multi-token with a space
            "lower,gzip", // no whitespace
            "lower , gzip", // leading space before the comma
            "lower ,gzip", // trailing space after the first token
            "lower;q=1", // accept-encoding-style q-value parameter (illegal here)
            "lower\tgzip", // tab as separator (not legal but plausible mistake)
        )
        for (encoding in variants) {
            val state = ChainState()
            val handler = HttpRequestDecompressionHandler(
                registryWithLower,
                DefaultAllocator,
                unknownEncodingPolicy = UnknownEncodingPolicy.UnsupportedMediaType,
            )
            val ctx = TestCtx(state)
            val ex = assertFailsWith<UnsupportedContentEncodingException>(
                "expected to reject `$encoding`, but it slipped through",
            ) {
                handler.onRead(
                    ctx,
                    HttpRequestHead(
                        HttpMethod.POST,
                        "/upload",
                        headers = HttpHeaders().apply { add("Content-Encoding", encoding) },
                    ),
                )
            }
            // Header values fed through `getString(...).lowercase()`, so the
            // exception's `encoding` reports the lower-cased form of `encoding`.
            assertEquals(encoding.lowercase(), ex.encoding, "wrong rejected encoding for `$encoding`")
            assertTrue(state.reads.isEmpty(), "head must not propagate when `$encoding` is rejected")
        }
    }

    @Test
    fun `multi-token Content-Encoding is rejected by the unknown-encoding policy`() {
        // RFC 9110 §8.4.1 lets a request stack encodings (e.g. "deflate, gzip"
        // = deflate-then-gzip, decoded in reverse). keel intentionally does not
        // implement chained inbound decoding: the full header value is looked
        // up as a single codec name, so anything other than one registered
        // token falls through to unknownEncodingPolicy. This pins the
        // documented behaviour — a multi-token header is *not* silently
        // accepted with the first token (the Netty approach) and must trip the
        // configured rejection policy (415 by default).
        val state = ChainState()
        val handler = HttpRequestDecompressionHandler(
            registryWithLower,
            DefaultAllocator,
            unknownEncodingPolicy = UnknownEncodingPolicy.UnsupportedMediaType,
        )
        val ctx = TestCtx(state)
        val ex = assertFailsWith<UnsupportedContentEncodingException> {
            handler.onRead(
                ctx,
                HttpRequestHead(
                    HttpMethod.POST,
                    "/upload",
                    // "lower" alone is registered; with another token it must be rejected.
                    headers = HttpHeaders().apply { add("Content-Encoding", "lower, gzip") },
                ),
            )
        }
        assertEquals("lower, gzip", ex.encoding)
        assertEquals(UnknownEncodingPolicy.UnsupportedMediaType, ex.policy)
        assertTrue(state.reads.isEmpty(), "multi-token head must not propagate when rejected")
    }

    @Test
    fun `Passthrough policy forwards unknown encoding unchanged`() {
        val state = ChainState()
        val handler = HttpRequestDecompressionHandler(
            registryWithLower,
            DefaultAllocator,
            unknownEncodingPolicy = UnknownEncodingPolicy.Passthrough,
        )
        val ctx = TestCtx(state)
        val head = HttpRequestHead(
            HttpMethod.POST,
            "/upload",
            headers = HttpHeaders().apply { add("Content-Encoding", "br") },
        )
        handler.onRead(ctx, head)

        // Head propagates as-is, including the original Content-Encoding.
        assertSame(head, state.reads.single())
    }

    // -------------------------------------------------------------- absolute cap

    @Test
    fun `absolute cap exceeded throws AbsoluteSizeExceeded`() {
        val registry = CompressionRegistry().apply { registerDecoder(MultiplyDecoder(factor = 4)) }
        val state = ChainState()
        // 4 byte input → 16 byte output; cap at 10 bytes triggers.
        val handler = HttpRequestDecompressionHandler(
            registry,
            DefaultAllocator,
            decompressionLimit = 10L,
            ratioLimit = Int.MAX_VALUE,
        )
        val ctx = TestCtx(state)
        handler.onRead(
            ctx,
            HttpRequestHead(
                HttpMethod.POST,
                "/upload",
                headers = HttpHeaders().apply { add("Content-Encoding", "x4") },
            ),
        )
        val ex = assertFailsWith<RequestDecompressionLimitException> {
            handler.onRead(ctx, HttpBody(bufOf("AAAA")))
        }
        assertEquals(RequestDecompressionLimitException.Reason.AbsoluteSizeExceeded, ex.reason)
        assertEquals(16L, ex.bytesDecoded)
        assertEquals(4L, ex.bytesIn)
        // 5th deep-review S1: the tripped codec is named, since the handler
        // strips Content-Encoding before a downstream mapper sees it.
        assertEquals("x4", ex.encoding)
        assertTrue(ex.message?.contains("encoding=x4") == true, "message must name the codec: ${ex.message}")
    }

    @Test
    fun `aggregated decode releases the held chunks when the absolute cap is exceeded`() {
        val tracker = TrackingAllocator(DefaultAllocator)
        val registry = CompressionRegistry().apply { registerDecoder(MultiplyDecoder(factor = 4)) }
        val state = ChainState()
        // 4-byte input -> 16-byte decoded; cap at 10 trips in sealAndCount after
        // the accumulator already committed the decoded chunk. The held pooled
        // chunk must be released on the abort path, not leaked.
        val handler = HttpRequestDecompressionHandler(
            registry,
            tracker,
            decompressionLimit = 10L,
            ratioLimit = Int.MAX_VALUE,
        )
        val ctx = TestCtx(state)
        val request = HttpRequest(
            method = HttpMethod.POST,
            uri = "/upload",
            headers = HttpHeaders().apply {
                add("Content-Encoding", "x4")
                add("Content-Length", "4")
            },
            body = "AAAA".encodeToByteArray(),
        )

        val ex = assertFailsWith<RequestDecompressionLimitException> {
            handler.onRead(ctx, request)
        }
        assertEquals(RequestDecompressionLimitException.Reason.AbsoluteSizeExceeded, ex.reason)
        tracker.assertNoLeaks("aggregated decode must release the held chunks when the cap trips")
    }

    @Test
    fun `absolute cap opt-out via Long_MAX_VALUE allows large output`() {
        val registry = CompressionRegistry().apply { registerDecoder(MultiplyDecoder(factor = 4)) }
        val state = ChainState()
        val handler = HttpRequestDecompressionHandler(
            registry,
            DefaultAllocator,
            decompressionLimit = Long.MAX_VALUE,
            ratioLimit = Int.MAX_VALUE,
        )
        val ctx = TestCtx(state)
        handler.onRead(
            ctx,
            HttpRequestHead(
                HttpMethod.POST,
                "/upload",
                headers = HttpHeaders().apply { add("Content-Encoding", "x4") },
            ),
        )
        // 1024 byte input → 4096 byte output, no throw expected.
        handler.onRead(ctx, HttpBody(bufOf("A".repeat(1024))))
        handler.onRead(ctx, HttpBodyEnd.EMPTY)
        val total = state.reads.filterIsInstance<HttpBody>()
            .filter { it !is HttpBodyEnd }
            .sumOf { it.content.readableBytes }
        assertEquals(4096, total)
    }

    // -------------------------------------------------------------- ratio cap + burst

    @Test
    fun `ratio cap exceeded with burst exhausted throws RatioExceeded`() {
        val registry = CompressionRegistry().apply { registerDecoder(MultiplyDecoder(factor = 200)) }
        val state = ChainState()
        // 200:1 ratio (way above 100:1 cap). Burst = 2 → 3rd violation should throw.
        val handler = HttpRequestDecompressionHandler(
            registry,
            DefaultAllocator,
            decompressionLimit = Long.MAX_VALUE,
            ratioLimit = 100,
            ratioBurst = 2,
        )
        val ctx = TestCtx(state)
        handler.onRead(
            ctx,
            HttpRequestHead(
                HttpMethod.POST,
                "/upload",
                headers = HttpHeaders().apply { add("Content-Encoding", "x200") },
            ),
        )
        // 1st chunk: 1 byte → 200 bytes (ratio 200:1, violation 1, burst 2 left → continue)
        handler.onRead(ctx, HttpBody(bufOf("A")))
        // 2nd chunk: 1 byte → ratio still 200:1, violation 2, burst 1 → continue.
        handler.onRead(ctx, HttpBody(bufOf("A")))
        // 3rd chunk: 1 byte → ratio still 200:1, violation 3, burst exhausted → throw.
        val ex = assertFailsWith<RequestDecompressionLimitException> {
            handler.onRead(ctx, HttpBody(bufOf("A")))
        }
        assertEquals(RequestDecompressionLimitException.Reason.RatioExceeded, ex.reason)
    }

    @Test
    fun `ratio violations within burst tolerance pass through`() {
        val registry = CompressionRegistry().apply { registerDecoder(MultiplyDecoder(factor = 200)) }
        val state = ChainState()
        val handler = HttpRequestDecompressionHandler(
            registry,
            DefaultAllocator,
            decompressionLimit = Long.MAX_VALUE,
            ratioLimit = 100,
            ratioBurst = 3,
        )
        val ctx = TestCtx(state)
        handler.onRead(
            ctx,
            HttpRequestHead(
                HttpMethod.POST,
                "/upload",
                headers = HttpHeaders().apply { add("Content-Encoding", "x200") },
            ),
        )
        // 3 violations within burst → no throw; 4th would throw.
        handler.onRead(ctx, HttpBody(bufOf("A")))
        handler.onRead(ctx, HttpBody(bufOf("A")))
        handler.onRead(ctx, HttpBody(bufOf("A")))
        // No exception raised; verify decoded body propagated.
        val total = state.reads.filterIsInstance<HttpBody>()
            .filter { it !is HttpBodyEnd }
            .sumOf { it.content.readableBytes }
        assertEquals(600, total) // 3 × 200
    }

    @Test
    fun `ratio cap opt-out via Int_MAX_VALUE allows arbitrary ratio`() {
        val registry = CompressionRegistry().apply { registerDecoder(MultiplyDecoder(factor = 1000)) }
        val state = ChainState()
        val handler = HttpRequestDecompressionHandler(
            registry,
            DefaultAllocator,
            decompressionLimit = Long.MAX_VALUE,
            ratioLimit = Int.MAX_VALUE,
        )
        val ctx = TestCtx(state)
        handler.onRead(
            ctx,
            HttpRequestHead(
                HttpMethod.POST,
                "/upload",
                headers = HttpHeaders().apply { add("Content-Encoding", "x1000") },
            ),
        )
        handler.onRead(ctx, HttpBody(bufOf("A")))
        // Should not throw despite 1000:1 ratio.
        val total = state.reads.filterIsInstance<HttpBody>()
            .filter { it !is HttpBodyEnd }
            .sumOf { it.content.readableBytes }
        assertEquals(1000, total)
    }

    // -------------------------------------------------------------- limits reset between requests

    @Test
    fun `limits reset between pipelined requests`() {
        val registry = CompressionRegistry().apply { registerDecoder(MultiplyDecoder(factor = 4)) }
        val state = ChainState()
        val handler = HttpRequestDecompressionHandler(
            registry,
            DefaultAllocator,
            decompressionLimit = 16L,
            ratioLimit = Int.MAX_VALUE,
        )
        val ctx = TestCtx(state)
        // Request 1: 4 byte → 16 byte (at cap, no throw — strictly greater triggers).
        handler.onRead(
            ctx,
            HttpRequestHead(
                HttpMethod.POST,
                "/r1",
                headers = HttpHeaders().apply { add("Content-Encoding", "x4") },
            ),
        )
        handler.onRead(ctx, HttpBody(bufOf("AAAA")))
        handler.onRead(ctx, HttpBodyEnd.EMPTY)
        // Request 2 should start with fresh counters; 4 byte → 16 byte again, no throw.
        handler.onRead(
            ctx,
            HttpRequestHead(
                HttpMethod.POST,
                "/r2",
                headers = HttpHeaders().apply { add("Content-Encoding", "x4") },
            ),
        )
        handler.onRead(ctx, HttpBody(bufOf("BBBB")))
        handler.onRead(ctx, HttpBodyEnd.EMPTY)

        // Both requests' bodies emitted (16 bytes each = 32 bytes total decoded across HttpBody).
        val total = state.reads.filterIsInstance<HttpBody>()
            .filter { it !is HttpBodyEnd }
            .sumOf { it.content.readableBytes }
        assertEquals(32, total)
    }
}
