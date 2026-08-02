package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.compression.CompressionRegistry
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tests for the handler's lifecycle and header handling — cleanup on removal,
 * multi-value `Content-Encoding`, the `Content-Length` pre-reject, and the
 * regressions found reviewing them.
 */
internal class HttpRequestDecompressionLifecycleTest : HttpRequestDecompressionFixture() {

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
                HttpMethod.POST,
                "/upload",
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
            registry,
            DefaultAllocator,
            decompressionLimit = 50L,
            ratioLimit = 100,
            ratioBurst = 0,
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
            registry,
            DefaultAllocator,
            decompressionLimit = 10L,
        )
        val ctx = TestCtx(state)
        val req = HttpRequest(
            HttpMethod.POST,
            "/upload",
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
            registry,
            DefaultAllocator,
            decompressionLimit = 10L,
        )
        val ctx = TestCtx(state)
        val head = HttpRequestHead(
            HttpMethod.POST,
            "/upload",
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
            registry,
            DefaultAllocator,
            decompressionLimit = 10L,
        )
        val ctx = TestCtx(state)
        handler.onRead(
            ctx,
            HttpRequest(
                HttpMethod.POST,
                "/upload",
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
            registry,
            DefaultAllocator,
            decompressionLimit = 10L,
        )
        val ctx = TestCtx(state)
        handler.onRead(
            ctx,
            HttpRequestHead(
                HttpMethod.POST,
                "/upload",
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
            registry,
            DefaultAllocator,
            decompressionLimit = Long.MAX_VALUE,
        )
        val ctx = TestCtx(state)
        handler.onRead(
            ctx,
            HttpRequestHead(
                HttpMethod.POST,
                "/upload",
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
            registry,
            DefaultAllocator,
            decompressionLimit = Long.MAX_VALUE,
            ratioLimit = 100,
            // ratioBurst left at default (= 0).
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
            registry,
            DefaultAllocator,
            decompressionLimit = Long.MAX_VALUE,
            ratioLimit = 100,
            ratioBurst = 0,
        )
        val ctx = TestCtx(state)

        // Request A: x200 decoder + ratio cap 100 → first chunk trips.
        handler.onRead(
            ctx,
            HttpRequestHead(
                HttpMethod.POST,
                "/a",
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
                HttpMethod.POST,
                "/b",
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
            registry,
            DefaultAllocator,
            decompressionLimit = Long.MAX_VALUE,
            ratioLimit = 100,
            ratioBurst = 0,
        )
        val ctx = TestCtx(state)

        handler.onRead(
            ctx,
            HttpRequestHead(
                HttpMethod.POST,
                "/a",
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
                HttpMethod.POST,
                "/b",
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
                HttpMethod.POST,
                "/a",
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
                HttpMethod.POST,
                "/b",
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
                HttpMethod.POST,
                "/upload",
                headers = HttpHeaders().apply { add("Content-Encoding", "lower") },
            ),
        )
        // First decoded body chunk: ctx.propagateRead throws, the handler
        // must release `emit` and rethrow rather than orphan the buffer.
        assertFailsWith<IllegalStateException>("expected the propagateRead throw to surface from onRead") {
            handler.onRead(ctx, HttpBody(bufOf("HELLO")))
        }
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
}
