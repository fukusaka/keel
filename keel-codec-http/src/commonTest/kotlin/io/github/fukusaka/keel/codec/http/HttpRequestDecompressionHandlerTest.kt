package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.compression.CodecStatus
import io.github.fukusaka.keel.compression.CompressionRegistry
import io.github.fukusaka.keel.compression.Decoder
import io.github.fukusaka.keel.compression.DecoderOptions
import io.github.fukusaka.keel.compression.DecoderSession
import io.github.fukusaka.keel.pipeline.PipelineHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import io.github.fukusaka.keel.pipeline.Pipeline as PipelineType
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Behaviour tests for [HttpRequestDecompressionHandler] using stub
 * decoders ([LowerDecoder], [MultiplyDecoder]) so we don't depend on
 * the zlib backend. The real round-trip is exercised by
 * `keel-compression-zlib`'s own tests; here we focus on handler logic.
 */
class HttpRequestDecompressionHandlerTest {

    private val registryWithLower = CompressionRegistry().apply {
        registerDecoder(LowerDecoder)
    }

    // -------------------------------------------------------------- passthrough

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

    // -------------------------------------------------------------- streaming decode

    @Test
    fun `streaming request decodes body and strips Content-Encoding plus Content-Length`() {
        val state = ChainState()
        val handler = HttpRequestDecompressionHandler(registryWithLower, DefaultAllocator)
        val ctx = TestCtx(state)
        val head = HttpRequestHead(
            HttpMethod.POST,
            "/upload",
            headers = HttpHeaders().apply {
                add("Content-Encoding", "lower")
                add("Content-Length", "5")
                add("Content-Type", "text/plain")
            },
        )
        handler.onRead(ctx, head)
        handler.onRead(ctx, HttpBody(bufOf("HELLO")))
        handler.onRead(ctx, HttpBodyEnd.EMPTY)

        val emittedHead = state.reads.filterIsInstance<HttpRequestHead>().single()
        assertNull(emittedHead.headers.getString("Content-Encoding"))
        assertNull(emittedHead.headers.getString("Content-Length"))
        assertEquals("text/plain", emittedHead.headers.getString("Content-Type"))

        val decodedBytes = state.reads.filterIsInstance<HttpBody>()
            .filter { it !is HttpBodyEnd }
            .joinToString("") { ioBufAsString(it.content) }
        assertEquals("hello", decodedBytes)

        assertNotNull(state.reads.last() as? HttpBodyEnd)
    }

    // -------------------------------------------------- recv-buffer release

    // Builds a pooled HttpHeaders whose single `Content-Encoding` entry is a
    // zero-copy range view over [backing], mirroring a decoder-sourced head.
    // [backing] is retained by addRange; the headers' `release()` balances it.
    private fun rangeBackedEncodingHeaders(encoding: String, backing: IoBuf): HttpHeaders {
        val name = "Content-Encoding"
        val hash = HttpHeaders.caseInsensitiveHashOfBuf(backing, 0, name.length)
        return HttpHeaders.borrow().addRange(backing, hash, 0, name.length, name.length, encoding.length)
    }

    private fun encodingBuffer(encoding: String): IoBuf {
        val bytes = ("Content-Encoding" + encoding).encodeToByteArray()
        // Power-of-two capacity so HttpHeaders.addRange takes the zero-copy
        // retain path — for a non-power-of-two buffer it materialises the
        // value to a String and retains nothing, which would make the recv
        // buffer release impossible to observe (a vacuous test).
        var cap = 1
        while (cap < bytes.size) cap = cap shl 1
        val buf = DefaultAllocator.allocate(cap)
        buf.writeByteArray(bytes, 0, bytes.size)
        return buf
    }

    @Test
    fun `streaming decode releases the original head headers buffer at body end`() {
        val state = ChainState()
        val handler = HttpRequestDecompressionHandler(registryWithLower, DefaultAllocator)
        val ctx = TestCtx(state)

        // Decoder-sourced head: its headers retain `backing` behind a range
        // view. The handler rewrites the head with a buffer-free copy, so
        // nothing downstream releases the original — the handler must, or one
        // recv buffer leaks per request (io_uring's fixed provided-buffer
        // ring drains and wedges the EventLoop in an -ENOBUFS storm).
        val backing = encodingBuffer("lower")
        val headers = rangeBackedEncodingHeaders("lower", backing) // refCount 2 (alloc + addRange)
        handler.onRead(ctx, HttpRequestHead(HttpMethod.POST, "/upload", headers = headers))
        handler.onRead(ctx, HttpBody(bufOf("HELLO")))

        // Mid-request the buffer must stay retained: streaming body chunks may
        // still alias it, so releasing the head's headers early would recycle
        // bytes the decoder has not yet decoded.
        assertFalse(backing.release(), "head buffer must stay retained until HttpBodyEnd")
        backing.retain() // undo the probe decrement

        handler.onRead(ctx, HttpBodyEnd.EMPTY)

        // After HttpBodyEnd the handler has released the original headers,
        // dropping their retain — the test's own reference is now the last one.
        assertTrue(backing.release(), "head buffer must be released after HttpBodyEnd")
    }

    @Test
    fun `aggregated decode releases the original request headers buffer`() {
        val state = ChainState()
        val handler = HttpRequestDecompressionHandler(registryWithLower, DefaultAllocator)
        val ctx = TestCtx(state)

        val backing = encodingBuffer("lower")
        val headers = rangeBackedEncodingHeaders("lower", backing)
        handler.onRead(
            ctx,
            HttpRequest(HttpMethod.POST, "/upload", headers = headers, body = "HELLO".encodeToByteArray()),
        )

        // The aggregated body is a self-contained ByteArray, so the buffer is
        // released immediately after the rewrite — only the test's ref remains.
        assertTrue(backing.release(), "request buffer must be released after aggregated decode")
    }

    @Test
    fun `multi-chunk body decodes incrementally`() {
        val state = ChainState()
        val handler = HttpRequestDecompressionHandler(registryWithLower, DefaultAllocator)
        val ctx = TestCtx(state)
        handler.onRead(
            ctx,
            HttpRequestHead(
                HttpMethod.POST, "/upload",
                headers = HttpHeaders().apply { add("Content-Encoding", "lower") },
            ),
        )
        handler.onRead(ctx, HttpBody(bufOf("HEL")))
        handler.onRead(ctx, HttpBody(bufOf("LO ")))
        handler.onRead(ctx, HttpBody(bufOf("WORLD")))
        handler.onRead(ctx, HttpBodyEnd.EMPTY)

        val joined = state.reads.filterIsInstance<HttpBody>()
            .filter { it !is HttpBodyEnd }
            .joinToString("") { ioBufAsString(it.content) }
        assertEquals("hello world", joined)
    }

    @Test
    fun `streaming request with empty body forwards stripped head and HttpBodyEnd`() {
        val state = ChainState()
        val handler = HttpRequestDecompressionHandler(registryWithLower, DefaultAllocator)
        val ctx = TestCtx(state)
        handler.onRead(
            ctx,
            HttpRequestHead(
                HttpMethod.POST, "/upload",
                headers = HttpHeaders().apply {
                    add("Content-Encoding", "lower")
                    add("Content-Length", "0")
                },
            ),
        )
        handler.onRead(ctx, HttpBodyEnd.EMPTY)

        val emittedHead = state.reads.filterIsInstance<HttpRequestHead>().single()
        assertNull(emittedHead.headers.getString("Content-Encoding"))
        assertNull(emittedHead.headers.getString("Content-Length"))
        // No HttpBody, just HttpBodyEnd.
        assertEquals(0, state.reads.filterIsInstance<HttpBody>().count { it !is HttpBodyEnd })
        assertNotNull(state.reads.last() as? HttpBodyEnd)
    }

    // -------------------------------------------------------------- aggregated decode

    @Test
    fun `aggregated HttpRequest decodes body in one shot`() {
        val state = ChainState()
        val handler = HttpRequestDecompressionHandler(registryWithLower, DefaultAllocator)
        val ctx = TestCtx(state)
        val request = HttpRequest(
            method = HttpMethod.POST,
            uri = "/upload",
            headers = HttpHeaders().apply {
                add("Content-Encoding", "lower")
                add("Content-Length", "11")
            },
            body = "HELLO WORLD".encodeToByteArray(),
        )

        handler.onRead(ctx, request)

        val out = state.reads.single() as HttpRequest
        assertNull(out.headers.getString("Content-Encoding"))
        assertNull(out.headers.getString("Content-Length"))
        assertContentEquals("hello world".encodeToByteArray(), out.body)
    }

    @Test
    fun `aggregated decode drains output beyond a single scratch fill without spinning`() {
        // Regression: prior to the fix, `decodeAggregated`'s `drainTo` only
        // advanced the scratch IoBuf's readerIndex via `readByteArray`, leaving
        // writerIndex pinned at capacity. Once the decoder produced more than
        // one scratch-worth of output (8 KiB default), the next `session.update`
        // saw `writableBytes == 0`, immediately returned NEED_OUTPUT, drainTo
        // saw `readableBytes == 0`, and the `while (true)` loop spun forever
        // (EventLoop DoS on any aggregated decompressed request > 8 KiB).
        //
        // `BoundedCallMultiplyDecoder` throws once `update` is called more
        // times than the decoded body legitimately needs; on the buggy code
        // path the call count blows that ceiling. On the fixed path it
        // completes in ~3 calls.
        val factor = 200
        val inputBytes = 100 // 100 -> 20 KiB decoded; spans multiple 8 KiB drains
        val callCeiling = 32 // far above the ~3 calls a healthy decode needs
        val bounded = BoundedCallMultiplyDecoder(factor, maxCalls = callCeiling)
        val registry = CompressionRegistry().apply { registerDecoder(bounded) }
        // The scratch-drain spin regression is independent of the ratio
        // gate; disable L3 so the 200× decoder reaches the drain loop
        // (the default single-shot ratio trip would otherwise abort the
        // 200:1 expansion on the first chunk).
        val handler = HttpRequestDecompressionHandler(
            registry, DefaultAllocator,
            ratioLimit = Int.MAX_VALUE,
        )
        val ctx = TestCtx(ChainState())
        val request = HttpRequest(
            method = HttpMethod.POST,
            uri = "/upload",
            headers = HttpHeaders().apply {
                add("Content-Encoding", "x$factor")
                add("Content-Length", inputBytes.toString())
            },
            body = ByteArray(inputBytes) { 'A'.code.toByte() },
        )

        handler.onRead(ctx, request)

        val out = ctx.state.reads.single() as HttpRequest
        assertEquals(inputBytes * factor, out.body!!.size)
    }

    @Test
    fun `aggregated HttpRequest with null body forwards stripped head`() {
        val state = ChainState()
        val handler = HttpRequestDecompressionHandler(registryWithLower, DefaultAllocator)
        val ctx = TestCtx(state)
        val request = HttpRequest(
            method = HttpMethod.POST, uri = "/upload",
            headers = HttpHeaders().apply { add("Content-Encoding", "lower") },
            body = null,
        )

        handler.onRead(ctx, request)

        val out = state.reads.single() as HttpRequest
        assertNull(out.headers.getString("Content-Encoding"))
        assertNull(out.body)
    }

    // -------------------------------------------------------------- case-insensitivity

    @Test
    fun `Content-Encoding lookup is case-insensitive`() {
        for (variant in listOf("LOWER", "Lower", "lower")) {
            val state = ChainState()
            val handler = HttpRequestDecompressionHandler(registryWithLower, DefaultAllocator)
            val ctx = TestCtx(state)
            handler.onRead(
                ctx,
                HttpRequest(
                    HttpMethod.POST, "/upload",
                    headers = HttpHeaders().apply { add("Content-Encoding", variant) },
                    body = "HI".encodeToByteArray(),
                ),
            )
            val out = state.reads.single() as HttpRequest
            assertContentEquals(
                "hi".encodeToByteArray(),
                out.body,
                "case variant '$variant' should resolve to lower decoder",
            )
        }
    }

    // -------------------------------------------------------------- unknown encoding policy

    @Test
    fun `UnsupportedMediaType policy throws on unknown encoding`() {
        val state = ChainState()
        val handler = HttpRequestDecompressionHandler(
            registryWithLower, DefaultAllocator,
            unknownEncodingPolicy = UnknownEncodingPolicy.UnsupportedMediaType,
        )
        val ctx = TestCtx(state)
        val ex = assertFailsWith<UnsupportedContentEncodingException> {
            handler.onRead(
                ctx,
                HttpRequestHead(
                    HttpMethod.POST, "/upload",
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
            registryWithLower, DefaultAllocator,
            unknownEncodingPolicy = UnknownEncodingPolicy.BadRequest,
        )
        val ctx = TestCtx(state)
        val ex = assertFailsWith<UnsupportedContentEncodingException> {
            handler.onRead(
                ctx,
                HttpRequestHead(
                    HttpMethod.POST, "/upload",
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
            "lower, gzip",       // canonical multi-token with a space
            "lower,gzip",        // no whitespace
            "lower , gzip",      // leading space before the comma
            "lower ,gzip",       // trailing space after the first token
            "lower;q=1",         // accept-encoding-style q-value parameter (illegal here)
            "lower\tgzip",       // tab as separator (not legal but plausible mistake)
        )
        for (encoding in variants) {
            val state = ChainState()
            val handler = HttpRequestDecompressionHandler(
                registryWithLower, DefaultAllocator,
                unknownEncodingPolicy = UnknownEncodingPolicy.UnsupportedMediaType,
            )
            val ctx = TestCtx(state)
            val ex = assertFailsWith<UnsupportedContentEncodingException>(
                "expected to reject `$encoding`, but it slipped through",
            ) {
                handler.onRead(
                    ctx,
                    HttpRequestHead(
                        HttpMethod.POST, "/upload",
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
            registryWithLower, DefaultAllocator,
            unknownEncodingPolicy = UnknownEncodingPolicy.UnsupportedMediaType,
        )
        val ctx = TestCtx(state)
        val ex = assertFailsWith<UnsupportedContentEncodingException> {
            handler.onRead(
                ctx,
                HttpRequestHead(
                    HttpMethod.POST, "/upload",
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
            registryWithLower, DefaultAllocator,
            unknownEncodingPolicy = UnknownEncodingPolicy.Passthrough,
        )
        val ctx = TestCtx(state)
        val head = HttpRequestHead(
            HttpMethod.POST, "/upload",
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
            registry, DefaultAllocator,
            decompressionLimit = 10L,
            ratioLimit = Int.MAX_VALUE,
        )
        val ctx = TestCtx(state)
        handler.onRead(
            ctx,
            HttpRequestHead(
                HttpMethod.POST, "/upload",
                headers = HttpHeaders().apply { add("Content-Encoding", "x4") },
            ),
        )
        val ex = assertFailsWith<RequestDecompressionLimitException> {
            handler.onRead(ctx, HttpBody(bufOf("AAAA")))
        }
        assertEquals(RequestDecompressionLimitException.Reason.AbsoluteSizeExceeded, ex.reason)
        assertEquals(16L, ex.bytesDecoded)
        assertEquals(4L, ex.bytesIn)
    }

    @Test
    fun `absolute cap opt-out via Long_MAX_VALUE allows large output`() {
        val registry = CompressionRegistry().apply { registerDecoder(MultiplyDecoder(factor = 4)) }
        val state = ChainState()
        val handler = HttpRequestDecompressionHandler(
            registry, DefaultAllocator,
            decompressionLimit = Long.MAX_VALUE,
            ratioLimit = Int.MAX_VALUE,
        )
        val ctx = TestCtx(state)
        handler.onRead(
            ctx,
            HttpRequestHead(
                HttpMethod.POST, "/upload",
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
            registry, DefaultAllocator,
            decompressionLimit = Long.MAX_VALUE,
            ratioLimit = 100,
            ratioBurst = 2,
        )
        val ctx = TestCtx(state)
        handler.onRead(
            ctx,
            HttpRequestHead(
                HttpMethod.POST, "/upload",
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
            registry, DefaultAllocator,
            decompressionLimit = Long.MAX_VALUE,
            ratioLimit = 100,
            ratioBurst = 3,
        )
        val ctx = TestCtx(state)
        handler.onRead(
            ctx,
            HttpRequestHead(
                HttpMethod.POST, "/upload",
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
            registry, DefaultAllocator,
            decompressionLimit = Long.MAX_VALUE,
            ratioLimit = Int.MAX_VALUE,
        )
        val ctx = TestCtx(state)
        handler.onRead(
            ctx,
            HttpRequestHead(
                HttpMethod.POST, "/upload",
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
            registry, DefaultAllocator,
            decompressionLimit = 16L,
            ratioLimit = Int.MAX_VALUE,
        )
        val ctx = TestCtx(state)
        // Request 1: 4 byte → 16 byte (at cap, no throw — strictly greater triggers).
        handler.onRead(
            ctx,
            HttpRequestHead(
                HttpMethod.POST, "/r1",
                headers = HttpHeaders().apply { add("Content-Encoding", "x4") },
            ),
        )
        handler.onRead(ctx, HttpBody(bufOf("AAAA")))
        handler.onRead(ctx, HttpBodyEnd.EMPTY)
        // Request 2 should start with fresh counters; 4 byte → 16 byte again, no throw.
        handler.onRead(
            ctx,
            HttpRequestHead(
                HttpMethod.POST, "/r2",
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

    // -------------------------------------------------------------- handlerRemoved cleanup

    @Test
    fun `handlerRemoved releases scratch and closes session`() {
        val state = ChainState()
        val handler = HttpRequestDecompressionHandler(registryWithLower, DefaultAllocator)
        val ctx = TestCtx(state)
        // Enter a state with active session + scratch allocated.
        handler.onRead(
            ctx,
            HttpRequestHead(
                HttpMethod.POST, "/upload",
                headers = HttpHeaders().apply { add("Content-Encoding", "lower") },
            ),
        )
        handler.handlerRemoved(ctx)
        // Idempotent — second call should be safe.
        handler.handlerRemoved(ctx)
    }

    // -------------------------------------------------------------- multi-value Content-Encoding

    @Test
    fun `multi-value Content-Encoding is treated as unknown encoding`() {
        // RFC 9110 §8.4 allows comma-separated `Content-Encoding: gzip, br`
        // to chain decoders, but keel's handler currently treats the
        // header as a single token. The full string `gzip, br` does not
        // match any registered decoder so the configured
        // unknownEncodingPolicy fires — this test pins that behaviour
        // so a future multi-value extension is a deliberate change
        // rather than an accidental contract drift.
        val state = ChainState()
        val handler = HttpRequestDecompressionHandler(
            registryWithLower, DefaultAllocator,
            unknownEncodingPolicy = UnknownEncodingPolicy.UnsupportedMediaType,
        )
        val ctx = TestCtx(state)
        val ex = assertFailsWith<UnsupportedContentEncodingException> {
            handler.onRead(
                ctx,
                HttpRequestHead(
                    HttpMethod.POST, "/upload",
                    headers = HttpHeaders().apply { add("Content-Encoding", "lower, br") },
                ),
            )
        }
        assertEquals("lower, br", ex.encoding)
    }

    @Test
    fun `dual-gate simultaneous violation reports AbsoluteSizeExceeded first`() {
        // 1 byte input → 200 bytes output: ratio 200:1 (over 100:1 cap)
        // AND absolute decoded > 50 (over decompressionLimit). The
        // handler checks absolute first, so the reason should be
        // AbsoluteSizeExceeded for determinism.
        val registry = CompressionRegistry().apply { registerDecoder(MultiplyDecoder(factor = 200)) }
        val state = ChainState()
        val handler = HttpRequestDecompressionHandler(
            registry, DefaultAllocator,
            decompressionLimit = 50L,
            ratioLimit = 100,
            ratioBurst = 0,
        )
        val ctx = TestCtx(state)
        handler.onRead(
            ctx,
            HttpRequestHead(
                HttpMethod.POST, "/upload",
                headers = HttpHeaders().apply { add("Content-Encoding", "x200") },
            ),
        )
        val ex = assertFailsWith<RequestDecompressionLimitException> {
            handler.onRead(ctx, HttpBody(bufOf("A")))
        }
        assertEquals(RequestDecompressionLimitException.Reason.AbsoluteSizeExceeded, ex.reason)
    }

    // -------------------------------------------------------------- L1: Content-Length pre-reject

    @Test
    fun `Content-Length exceeding decompressionLimit aborts aggregated request without running decoder`() {
        // L1: the advertised compressed size is a lower bound on what the
        // decoded body would weigh, so even at a 1:1 ratio the request can
        // not succeed past L2. The handler must throw at entry, never
        // instantiating a decoder session.
        val decoder = CountingDecoder(factor = 1)
        val registry = CompressionRegistry().apply { registerDecoder(decoder) }
        val state = ChainState()
        val handler = HttpRequestDecompressionHandler(
            registry, DefaultAllocator,
            decompressionLimit = 10L,
        )
        val ctx = TestCtx(state)
        val req = HttpRequest(
            HttpMethod.POST, "/upload",
            headers = HttpHeaders().apply {
                add("Content-Encoding", "x1")
                add("Content-Length", "100")
            },
            body = ByteArray(100),
        )
        val ex = assertFailsWith<RequestDecompressionLimitException> {
            handler.onRead(ctx, req)
        }
        assertEquals(RequestDecompressionLimitException.Reason.CompressedSizeExceeded, ex.reason)
        assertEquals(100L, ex.bytesIn)
        assertEquals(0L, ex.bytesDecoded)
        assertEquals(0, decoder.sessionsOpened)
        assertTrue(state.reads.isEmpty())
    }

    @Test
    fun `Content-Length exceeding decompressionLimit aborts streaming head before opening a session`() {
        // Streaming variant of the entry-level pre-reject.
        val decoder = CountingDecoder(factor = 1)
        val registry = CompressionRegistry().apply { registerDecoder(decoder) }
        val state = ChainState()
        val handler = HttpRequestDecompressionHandler(
            registry, DefaultAllocator,
            decompressionLimit = 10L,
        )
        val ctx = TestCtx(state)
        val head = HttpRequestHead(
            HttpMethod.POST, "/upload",
            headers = HttpHeaders().apply {
                add("Content-Encoding", "x1")
                add("Content-Length", "100")
            },
        )
        val ex = assertFailsWith<RequestDecompressionLimitException> {
            handler.onRead(ctx, head)
        }
        assertEquals(RequestDecompressionLimitException.Reason.CompressedSizeExceeded, ex.reason)
        assertEquals(100L, ex.bytesIn)
        assertEquals(0, decoder.sessionsOpened)
    }

    @Test
    fun `Content-Length within decompressionLimit passes the entry-level pre-reject`() {
        // 5 byte compressed body ≤ 10 byte cap → L1 must not fire. The
        // request then proceeds through L2 / L3 as before.
        val registry = CompressionRegistry().apply { registerDecoder(LowerDecoder) }
        val state = ChainState()
        val handler = HttpRequestDecompressionHandler(
            registry, DefaultAllocator,
            decompressionLimit = 10L,
        )
        val ctx = TestCtx(state)
        handler.onRead(
            ctx,
            HttpRequest(
                HttpMethod.POST, "/upload",
                headers = HttpHeaders().apply {
                    add("Content-Encoding", "lower")
                    add("Content-Length", "5")
                },
                body = "HELLO".encodeToByteArray(),
            ),
        )
        val emitted = state.reads.single() as HttpRequest
        assertContentEquals("hello".encodeToByteArray(), emitted.body!!)
    }

    @Test
    fun `missing Content-Length lets the request fall through to L2 and L3 during streaming`() {
        // chunked transfer-encoding has no Content-Length; L1 must no-op
        // so the streaming gates (L2 / L3) still get to run.
        val registry = CompressionRegistry().apply { registerDecoder(LowerDecoder) }
        val state = ChainState()
        val handler = HttpRequestDecompressionHandler(
            registry, DefaultAllocator,
            decompressionLimit = 10L,
        )
        val ctx = TestCtx(state)
        handler.onRead(
            ctx,
            HttpRequestHead(
                HttpMethod.POST, "/upload",
                headers = HttpHeaders().apply { add("Content-Encoding", "lower") },
            ),
        )
        // 5-byte chunk fits under the absolute cap, no throw.
        handler.onRead(ctx, HttpBody(bufOf("HELLO")))
        handler.onRead(ctx, HttpBodyEnd.EMPTY)
        val decoded = state.reads.filterIsInstance<HttpBody>()
            .filter { it !is HttpBodyEnd }
            .sumOf { it.content.readableBytes }
        assertEquals(5, decoded)
    }

    @Test
    fun `decompressionLimit opt-out disables the Content-Length pre-reject`() {
        // Long.MAX_VALUE is the documented opt-out for the absolute cap;
        // L1 must respect it too, otherwise opting out of L2 would still
        // leave the request rejected on a huge Content-Length.
        val registry = CompressionRegistry().apply { registerDecoder(LowerDecoder) }
        val state = ChainState()
        val handler = HttpRequestDecompressionHandler(
            registry, DefaultAllocator,
            decompressionLimit = Long.MAX_VALUE,
        )
        val ctx = TestCtx(state)
        handler.onRead(
            ctx,
            HttpRequestHead(
                HttpMethod.POST, "/upload",
                headers = HttpHeaders().apply {
                    add("Content-Encoding", "lower")
                    add("Content-Length", "999999999")
                },
            ),
        )
        // No throw — head propagated stripped.
        assertEquals(1, state.reads.size)
    }

    // -------------------------------------------------------------- L3: single-shot trip default

    @Test
    fun `default ratioBurst aborts on the first ratio violation`() {
        // The class default ratioBurst = 0 means the first chunk whose
        // cumulative decoded:input ratio exceeds ratioLimit aborts —
        // single-shot trip, the safest baseline. This pins that default.
        val registry = CompressionRegistry().apply { registerDecoder(MultiplyDecoder(factor = 200)) }
        val state = ChainState()
        val handler = HttpRequestDecompressionHandler(
            registry, DefaultAllocator,
            decompressionLimit = Long.MAX_VALUE,
            ratioLimit = 100,
            // ratioBurst left at default (= 0).
        )
        val ctx = TestCtx(state)
        handler.onRead(
            ctx,
            HttpRequestHead(
                HttpMethod.POST, "/upload",
                headers = HttpHeaders().apply { add("Content-Encoding", "x200") },
            ),
        )
        // First chunk: 1 byte → 200 byte (ratio 200:1, > 100 cap). Default
        // burst of 0 → trip on this single violation.
        val ex = assertFailsWith<RequestDecompressionLimitException> {
            handler.onRead(ctx, HttpBody(bufOf("A")))
        }
        assertEquals(RequestDecompressionLimitException.Reason.RatioExceeded, ex.reason)
    }

    // -------------------------------------------------------------- header mutation safety

    @Test
    fun `handleRequestHead does not mutate the original headers instance`() {
        // Regression test for the bug where stripDecodedHeaders previously
        // called `HttpHeaders.remove(...)` on the input — `remove()` mutates
        // in place and returns `this`, which corrupted the upstream
        // HttpRequestDecoder's local `head.headers` reference. The decoder
        // reads `head.headers.contentLength` AFTER `propagateRead(head)`
        // returns to choose READ_FIXED_BODY vs READ_CHUNK_SIZE; a stripped
        // Content-Length there short-circuited the body emit so the
        // compressed bytes never reached this handler.
        //
        // Pin the contract: the handler propagates a NEW HttpHeaders
        // instance with the two filtered, the original is untouched.
        val state = ChainState()
        val handler = HttpRequestDecompressionHandler(registryWithLower, DefaultAllocator)
        val ctx = TestCtx(state)
        val originalHeaders = HttpHeaders().apply {
            add("Content-Encoding", "lower")
            add("Content-Length", "5")
            add("Content-Type", "text/plain")
        }
        val head = HttpRequestHead(HttpMethod.POST, "/upload", headers = originalHeaders)

        handler.onRead(ctx, head)

        // Original headers must still carry both filtered fields.
        assertEquals("lower", originalHeaders["Content-Encoding"])
        assertEquals("5", originalHeaders["Content-Length"])
        // Propagated head sees them stripped.
        val emitted = state.reads.single() as HttpRequestHead
        assertNull(emitted.headers.getString("Content-Encoding"))
        assertNull(emitted.headers.getString("Content-Length"))
        assertEquals("text/plain", emitted.headers.getString("Content-Type"))
    }

    // -------------------------------------------------------------- onWrite passthrough

    @Test
    fun `onWrite is passthrough for all messages`() {
        val state = ChainState()
        val handler = HttpRequestDecompressionHandler(registryWithLower, DefaultAllocator)
        val ctx = TestCtx(state)
        val head = HttpResponseHead(
            status = HttpStatus(200),
            headers = HttpHeaders().apply { add("Content-Length", "5") },
        )
        handler.onWrite(ctx, head)
        assertSame(head, state.writes.single())
    }

    // ------------------------------------------------------------------ stubs

    /** Decodes `update`'s ASCII input by lowercasing it. 1:1 byte ratio. */
    private object LowerDecoder : Decoder {
        override val name: String = "lower"
        override fun newSession(
            allocator: io.github.fukusaka.keel.buf.BufferAllocator,
            options: DecoderOptions,
        ): DecoderSession = object : DecoderSession {
            private var pending: ByteArray = ByteArray(0)
            override fun update(input: IoBuf, output: IoBuf): CodecStatus {
                val n = input.readableBytes
                if (n > 0) {
                    val tmp = ByteArray(n)
                    input.readByteArray(tmp, 0, n)
                    pending += tmp.decodeToString().lowercase().encodeToByteArray()
                }
                return drain(output)
            }

            override fun finish(output: IoBuf): CodecStatus {
                if (pending.isEmpty()) return CodecStatus.FINISHED
                return drain(output)
            }

            override fun reset() { pending = ByteArray(0) }
            override fun close() { pending = ByteArray(0) }

            private fun drain(output: IoBuf): CodecStatus {
                if (pending.isEmpty()) return CodecStatus.NEED_INPUT
                val cap = output.writableBytes
                val take = minOf(cap, pending.size)
                output.writeByteArray(pending, 0, take)
                pending = pending.copyOfRange(take, pending.size)
                return if (pending.isEmpty()) CodecStatus.NEED_INPUT else CodecStatus.NEED_OUTPUT
            }
        }
    }

    /**
     * MultiplyDecoder variant that records how many sessions it has
     * been asked to instantiate. Used by the L1 pre-reject tests to
     * assert that the handler short-circuits at entry without calling
     * `newSession` (i.e. no inflate cost paid for an obviously-too-big
     * advertised body).
     */
    private class CountingDecoder(private val factor: Int) : Decoder {
        override val name: String = "x$factor"
        var sessionsOpened: Int = 0
            private set
        override fun newSession(
            allocator: io.github.fukusaka.keel.buf.BufferAllocator,
            options: DecoderOptions,
        ): DecoderSession {
            sessionsOpened++
            return object : DecoderSession {
                override fun update(input: IoBuf, output: IoBuf): CodecStatus = CodecStatus.NEED_INPUT
                override fun finish(output: IoBuf): CodecStatus = CodecStatus.FINISHED
                override fun reset() = Unit
                override fun close() = Unit
            }
        }
    }

    /**
     * Decoder that emits each input byte [factor] times. Useful for
     * exercising ratio-cap + absolute-cap thresholds deterministically:
     * `MultiplyDecoder(200)` produces a 200:1 expansion ratio.
     */
    private class MultiplyDecoder(private val factor: Int) : Decoder {
        override val name: String = "x$factor"
        override fun newSession(
            allocator: io.github.fukusaka.keel.buf.BufferAllocator,
            options: DecoderOptions,
        ): DecoderSession = object : DecoderSession {
            private var pending: ByteArray = ByteArray(0)
            override fun update(input: IoBuf, output: IoBuf): CodecStatus {
                val n = input.readableBytes
                if (n > 0) {
                    val tmp = ByteArray(n)
                    input.readByteArray(tmp, 0, n)
                    val expanded = ByteArray(n * factor)
                    for (i in 0 until n) {
                        for (j in 0 until factor) {
                            expanded[i * factor + j] = tmp[i]
                        }
                    }
                    pending += expanded
                }
                return drain(output)
            }

            override fun finish(output: IoBuf): CodecStatus {
                if (pending.isEmpty()) return CodecStatus.FINISHED
                return drain(output)
            }

            override fun reset() { pending = ByteArray(0) }
            override fun close() { pending = ByteArray(0) }

            private fun drain(output: IoBuf): CodecStatus {
                if (pending.isEmpty()) return CodecStatus.NEED_INPUT
                val cap = output.writableBytes
                val take = minOf(cap, pending.size)
                output.writeByteArray(pending, 0, take)
                pending = pending.copyOfRange(take, pending.size)
                return if (pending.isEmpty()) CodecStatus.NEED_INPUT else CodecStatus.NEED_OUTPUT
            }
        }
    }

    // ------------------------------------------------------------------ deep-review regressions

    @Test
    fun `a mid-stream limit failure does not bleed leftover decoded bytes into the next request`() {
        // Symmetric to the `CompressionHandler` cross-response bleed fix
        // (#665) on the inbound side. A 200× expansion blows the ratio cap
        // mid-body and throws; without the fix the partially-decoded scratch
        // would survive into the next request and prefix the first emit.
        val registry = CompressionRegistry().apply {
            registerDecoder(LowerDecoder)
            registerDecoder(MultiplyDecoder(factor = 200))
        }
        val state = ChainState()
        val handler = HttpRequestDecompressionHandler(
            registry, DefaultAllocator,
            decompressionLimit = Long.MAX_VALUE,
            ratioLimit = 100,
            ratioBurst = 0,
        )
        val ctx = TestCtx(state)

        // Request A: x200 decoder + ratio cap 100 → first chunk trips.
        handler.onRead(
            ctx,
            HttpRequestHead(
                HttpMethod.POST, "/a",
                headers = HttpHeaders().apply { add("Content-Encoding", "x200") },
            ),
        )
        assertFailsWith<RequestDecompressionLimitException> {
            handler.onRead(ctx, HttpBody(bufOf("A")))
        }

        // Request B: lower-cases the body. Without the fix the scratch
        // would still hold "AAA…" from request A and the first emit on
        // request B would be that 200-byte prefix followed by "hello".
        state.reads.clear()
        handler.onRead(
            ctx,
            HttpRequestHead(
                HttpMethod.POST, "/b",
                headers = HttpHeaders().apply { add("Content-Encoding", "lower") },
            ),
        )
        handler.onRead(ctx, HttpBody(bufOf("HELLO")))
        handler.onRead(ctx, HttpBodyEnd.EMPTY)

        val decoded = state.reads.filterIsInstance<HttpBody>()
            .filter { it !is HttpBodyEnd }
            .joinToString("") { ioBufAsString(it.content) }
        assertEquals("hello", decoded, "request B must not carry request A's leftover scratch bytes")
    }

    @Test
    fun `a mid-stream limit failure does not leak the decoder session into the next request`() {
        // Same scenario as above, but observed via the decoder factory's
        // open-session counter so we can pin the session-lifecycle fix
        // separately from the scratch-bleed fix.
        val multiply = CountingMultiplyDecoder(factor = 200)
        val registry = CompressionRegistry().apply {
            registerDecoder(LowerDecoder)
            registerDecoder(multiply)
        }
        val state = ChainState()
        val handler = HttpRequestDecompressionHandler(
            registry, DefaultAllocator,
            decompressionLimit = Long.MAX_VALUE,
            ratioLimit = 100,
            ratioBurst = 0,
        )
        val ctx = TestCtx(state)

        handler.onRead(
            ctx,
            HttpRequestHead(
                HttpMethod.POST, "/a",
                headers = HttpHeaders().apply { add("Content-Encoding", "x200") },
            ),
        )
        assertFailsWith<RequestDecompressionLimitException> {
            handler.onRead(ctx, HttpBody(bufOf("A")))
        }
        // Request B: completes normally.
        handler.onRead(
            ctx,
            HttpRequestHead(
                HttpMethod.POST, "/b",
                headers = HttpHeaders().apply { add("Content-Encoding", "lower") },
            ),
        )
        handler.onRead(ctx, HttpBody(bufOf("HELLO")))
        handler.onRead(ctx, HttpBodyEnd.EMPTY)

        assertEquals(0, multiply.openSessions, "request A's aborted decoder session must be closed, not leaked")
    }

    @Test
    fun `a second request head without an intervening body closes the prior session`() {
        // I-2 (4th deep-review): a pipelined client can send request head A
        // (with Content-Encoding, opening a decoder session) and then head B
        // before any body / HttpBodyEnd for A — e.g. two Content-Length: 0
        // POSTs back to back. handleRequestHead calls discardPendingRequestState
        // at the top, so head B must close A's still-open session rather than
        // overwrite (leak) it. Characterization: the discard already does this;
        // no prior test exercised the head-without-body sequence.
        val multiply = CountingMultiplyDecoder(factor = 200)
        val registry = CompressionRegistry().apply { registerDecoder(multiply) }
        val state = ChainState()
        val handler = HttpRequestDecompressionHandler(registry, DefaultAllocator)
        val ctx = TestCtx(state)

        // Head A opens a session (Content-Encoding present, no body follows).
        handler.onRead(
            ctx,
            HttpRequestHead(
                HttpMethod.POST, "/a",
                headers = HttpHeaders().apply {
                    add("Content-Encoding", "x200")
                    add("Content-Length", "0")
                },
            ),
        )
        // Head B arrives before any body / HttpBodyEnd for A.
        handler.onRead(
            ctx,
            HttpRequestHead(
                HttpMethod.POST, "/b",
                headers = HttpHeaders().apply {
                    add("Content-Encoding", "x200")
                    add("Content-Length", "0")
                },
            ),
        )
        // A's session must have been closed by head B's discard; only B's is open.
        assertEquals(1, multiply.openSessions, "head B must close head A's open session, not leak it")

        // Drain B normally so the test leaves no open session.
        handler.onRead(ctx, HttpBodyEnd.EMPTY)
        assertEquals(0, multiply.openSessions, "head B's session must close on its body end")
    }

    @Test
    fun `emitDecodedChunk releases the emit IoBuf when propagateRead throws`() {
        // M1 (4-th deep-review): `emitDecodedChunk` allocates a fresh `emit`
        // IoBuf and hands it downstream via `ctx.propagateRead(HttpBody(emit))`.
        // The pipeline contract is that ownership transfers only when the
        // call returns normally; a synchronous throw from a downstream
        // handler leaves `emit` orphaned. Before the fix the propagateRead
        // call was outside any try, so a throw at that point leaked the
        // pooled buffer per aborted chunk (TrackingAllocator outstandingCount
        // surfaces it). Pinned by Red-Green: this test fails (outstandingCount > 0)
        // on the pre-fix handler and passes after the propagateRead is
        // wrapped in try / catch / release.
        val tracker = TrackingAllocator(DefaultAllocator)
        val registry = CompressionRegistry().apply { registerDecoder(LowerDecoder) }
        val handler = HttpRequestDecompressionHandler(registry, tracker)
        val state = ChainState()
        val ctx = ctxAbortingFirstDecodedBody(state, allocator = tracker)

        handler.onRead(
            ctx,
            HttpRequestHead(
                HttpMethod.POST, "/upload",
                headers = HttpHeaders().apply { add("Content-Encoding", "lower") },
            ),
        )
        // First decoded body chunk: ctx.propagateRead throws, the handler
        // must release `emit` and rethrow rather than orphan the buffer.
        var aborted = false
        try {
            handler.onRead(ctx, HttpBody(bufOf("HELLO")))
        } catch (e: IllegalStateException) {
            aborted = true
        }
        assertTrue(aborted, "expected the propagateRead throw to surface from onRead")
        // handlerRemoved releases the scratch + any held session resources;
        // the only outstanding allocation that could remain is the emit
        // buffer if the fix were absent.
        handler.handlerRemoved(ctx)
        tracker.assertNoLeaks("emit IoBuf leaked on propagateRead throw")
    }

    /**
     * Counts open sessions for the multiplier decoder so a leak across
     * requests is observable.
     */
    private class CountingMultiplyDecoder(private val factor: Int) : Decoder {
        var openSessions: Int = 0
            private set

        override val name: String = "x$factor"

        override fun newSession(allocator: BufferAllocator, options: DecoderOptions): DecoderSession {
            openSessions++
            val delegate = MultiplyDecoder(factor).newSession(allocator, options)
            return object : DecoderSession by delegate {
                override fun close() {
                    delegate.close()
                    openSessions--
                }
            }
        }
    }

    /**
     * Bounded variant of [MultiplyDecoder] that throws `IllegalStateException`
     * once `update` is called more than [maxCalls] times. Used to detect
     * `decodeAggregated` infinite-loop regressions without relying on a wall-
     * clock timeout (the bug is a synchronous tight loop).
     */
    private class BoundedCallMultiplyDecoder(
        private val factor: Int,
        private val maxCalls: Int,
    ) : Decoder {
        override val name: String = "x$factor"
        override fun newSession(allocator: BufferAllocator, options: DecoderOptions): DecoderSession {
            val delegate = MultiplyDecoder(factor).newSession(allocator, options)
            return object : DecoderSession by delegate {
                private var calls = 0
                override fun update(input: IoBuf, output: IoBuf): CodecStatus {
                    if (++calls > maxCalls) {
                        error("update() exceeded $maxCalls calls — likely infinite loop in decodeAggregated")
                    }
                    return delegate.update(input, output)
                }
            }
        }
    }

    // ------------------------------------------------------------------ chain plumbing

    private class ChainState {
        val writes: MutableList<Any> = mutableListOf()
        val reads: MutableList<Any> = mutableListOf()
    }

    private class TestCtx(
        val state: ChainState,
        // Injects a synchronous failure: invoked before each inbound message
        // is recorded, so returning normally records the read and throwing
        // aborts it (simulating a downstream handler rejecting a decoded
        // chunk). Used to pin the propagateRead ownership-on-throw contract
        // — if `emit` is not released on throw it leaks per aborted chunk.
        private val beforeRead: ((Any) -> Unit)? = null,
        override val allocator: io.github.fukusaka.keel.buf.BufferAllocator = DefaultAllocator,
    ) : PipelineHandlerContext {
        override val name: String get() = "test"
        override val pipeline: PipelineType get() = error("not used")
        override val channel: PipelinedChannel get() = error("not used")
        override val handler: PipelineHandler get() = error("not used")
        override fun propagateRead(msg: Any) {
            beforeRead?.invoke(msg)
            state.reads.add(msg)
        }
        override fun propagateActive() {}
        override fun propagateInactive() {}
        override fun propagateReadComplete() {}
        override fun propagateError(cause: Throwable) {}
        override fun propagateUserEvent(event: Any) {}
        override fun propagateWritabilityChanged(isWritable: Boolean) {}
        override fun propagateWrite(msg: Any) { state.writes.add(msg) }
        override fun propagateFlush() {}
        override fun propagateClose() {}
    }

    /**
     * Builds a [TestCtx] that throws once on the first decoded [HttpBody]
     * (non-end) read — aborting at the moment `emitDecodedChunk` hands the
     * freshly allocated emit IoBuf downstream.
     *
     * Crucially this does NOT release the buffer before throwing: in
     * production the pipeline contract is "ownership transfers only when
     * propagate returns normally", so a downstream throw leaves the
     * buffer with the source. Releasing here would mask the very leak
     * the test is designed to catch.
     */
    private fun ctxAbortingFirstDecodedBody(
        state: ChainState,
        allocator: io.github.fukusaka.keel.buf.BufferAllocator = DefaultAllocator,
    ): TestCtx {
        var thrown = false
        return TestCtx(state, beforeRead = { msg ->
            if (!thrown && msg is HttpBody && msg !is HttpBodyEnd) {
                thrown = true
                throw IllegalStateException("simulated downstream rejection of decoded body chunk")
            }
        }, allocator = allocator)
    }

    private fun bufOf(text: String): IoBuf {
        val bytes = text.encodeToByteArray()
        val buf = DefaultAllocator.allocate(bytes.size)
        buf.writeByteArray(bytes, 0, bytes.size)
        return buf
    }

    private fun ioBufAsString(buf: IoBuf): String {
        val n = buf.readableBytes
        if (n == 0) return ""
        val tmp = ByteArray(n)
        buf.readByteArray(tmp, 0, n)
        return tmp.decodeToString()
    }
}
