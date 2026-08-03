package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.compression.CodecStatus
import io.github.fukusaka.keel.compression.CompressionRegistry
import io.github.fukusaka.keel.compression.Encoder
import io.github.fukusaka.keel.compression.EncoderOptions
import io.github.fukusaka.keel.compression.EncoderSession
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behaviour tests for [CompressionHandler] using a stub [Encoder] that
 * uppercases the bytes — verifies header rewrite, session lifecycle,
 * and Accept-Encoding negotiation without depending on the zlib backend.
 *
 * The real `keel-compression-zlib` round-trip lives in that module's
 * own tests; here we focus on handler logic.
 */
class CompressionHandlerTest {

    private val registry = CompressionRegistry().apply {
        registerEncoder(UpperEncoder)
    }

    @Test
    fun `negotiates and rewrites response headers`() {
        val state = ChainState()
        val handler = CompressionHandler(registry, DefaultAllocator)
        val ctx = TestCtx(state)

        // Inbound HttpRequestHead with Accept-Encoding.
        val req = HttpRequestHead(
            method = HttpMethod.GET,
            uri = "/x",
            version = HttpVersion.HTTP_1_1,
            headers = HttpHeaders().apply { add("Accept-Encoding", "upper") },
        )
        handler.onRead(ctx, req)

        // Outbound response head + body + end.
        val head = HttpResponseHead(
            status = HttpStatus(200),
            headers = HttpHeaders().apply {
                add("Content-Length", "5")
                add("Content-Type", "text/plain")
            },
        )
        handler.onWrite(ctx, head)

        val body = HttpBody(bufOf("hello"))
        handler.onWrite(ctx, body)

        val end = HttpBodyEnd.EMPTY
        handler.onWrite(ctx, end)

        // Verify mutated head: Content-Encoding=upper, Content-Length removed,
        // Vary present.
        val emittedHead = state.writes.filterIsInstance<HttpResponseHead>().single()
        assertEquals("upper", emittedHead.headers.getString("Content-Encoding"))
        assertNull(emittedHead.headers.getString("Content-Length"))
        assertEquals("Accept-Encoding", emittedHead.headers.getString("Vary"))

        // Verify body got encoded.
        val bodies = state.writes.filterIsInstance<HttpBody>().filter { it !is HttpBodyEnd }
        val encodedBytes = bodies.joinToString("") { ioBufAsString(it.content) }
        assertTrue(encodedBytes.startsWith("HELLO"), "expected uppercased body, got: $encodedBytes")

        // HttpBodyEnd at tail.
        assertNotNull(state.writes.lastOrNull() as? HttpBodyEnd)
    }

    @Test
    fun `handleBody reads content synchronously so a reused HttpBody wrapper is safe`() {
        // keel-server-http's Http1ResponseBodySink (L5-b) reuses one HttpBody
        // wrapper across every chunk of a streamed response instead of
        // allocating a fresh one per write, relying on handleBody reading
        // `.content` synchronously within onWrite and never retaining the
        // wrapper itself. Drive the SAME wrapper instance through two
        // onWrite calls with different content in between — the encoded
        // output for each call must reflect only that call's bytes, proving
        // handleBody doesn't alias a later reassignment into an earlier
        // chunk's encoded output.
        val state = ChainState()
        val handler = CompressionHandler(registry, DefaultAllocator)
        val ctx = TestCtx(state)

        handler.onRead(
            ctx,
            HttpRequestHead(
                HttpMethod.GET,
                "/x",
                HttpVersion.HTTP_1_1,
                HttpHeaders().apply { add("Accept-Encoding", "upper") },
            ),
        )
        handler.onWrite(
            ctx,
            HttpResponseHead(
                status = HttpStatus(200),
                headers = HttpHeaders().apply { add("Content-Type", "text/plain") },
            ),
        )

        val wrapper = MutableTestHttpBody(bufOf("uno"))
        handler.onWrite(ctx, wrapper)
        val afterFirst = state.writes.filterIsInstance<HttpBody>().filter { it !is HttpBodyEnd }
        val firstEncoded = afterFirst.joinToString("") { ioBufAsString(it.content) }

        wrapper.content = bufOf("dos")
        handler.onWrite(ctx, wrapper)
        val afterSecond = state.writes.filterIsInstance<HttpBody>().filter { it !is HttpBodyEnd }
        val secondEncoded = afterSecond.drop(afterFirst.size).joinToString("") { ioBufAsString(it.content) }

        handler.onWrite(ctx, HttpBodyEnd.EMPTY)

        assertTrue(firstEncoded.startsWith("UNO"), "first chunk must encode 'uno', got: $firstEncoded")
        assertTrue(
            secondEncoded.startsWith("DOS"),
            "second chunk must encode 'dos' (not aliased to the first), got: $secondEncoded",
        )
    }

    @Test
    fun `an aggregated compressed response is emitted as a chunked stream`() {
        // A compressed aggregated HttpResponse is converted to the streaming
        // chunked path (matches nginx / Netty / Ktor dynamic compression):
        // a HttpResponseHead with Content-Encoding + Transfer-Encoding: chunked
        // (Content-Length dropped, since the compressed size is no longer
        // materialised), then HttpBody chunks, then HttpBodyEnd — no aggregated
        // HttpResponse is emitted, and the chunks decode to the body.
        val state = ChainState()
        val handler = CompressionHandler(registry, DefaultAllocator)
        val ctx = TestCtx(state)

        handler.onRead(
            ctx,
            HttpRequestHead(
                HttpMethod.GET,
                "/x",
                HttpVersion.HTTP_1_1,
                HttpHeaders().apply { add("Accept-Encoding", "upper") },
            ),
        )

        val body = "hello aggregated world".encodeToByteArray()
        handler.onWrite(
            ctx,
            HttpResponse(
                status = HttpStatus(200),
                headers = HttpHeaders().apply {
                    add("Content-Length", body.size.toString())
                    add("Content-Type", "text/plain")
                },
                body = body,
            ),
        )

        val head = state.writes.filterIsInstance<HttpResponseHead>().single()
        assertEquals("upper", head.headers.getString("Content-Encoding"))
        assertNull(head.headers.getString("Content-Length"), "Content-Length is dropped for the chunked stream")
        assertEquals("chunked", head.headers.getString("Transfer-Encoding"))
        assertTrue(state.writes.none { it is HttpResponse }, "must not emit an aggregated HttpResponse")

        val bodies = state.writes.filterIsInstance<HttpBody>().filter { it !is HttpBodyEnd }
        val encoded = bodies.joinToString("") { ioBufAsString(it.content) }
        assertEquals(body.decodeToString().uppercase(), encoded)
        assertNotNull(state.writes.lastOrNull() as? HttpBodyEnd)
    }

    @Test
    fun `streaming response sets Transfer-Encoding chunked when Content-Length stripped`() {
        // Regression test for the Native ktor-keel-* compression wiring bug:
        // when the streaming HttpResponseHead path strips Content-Length,
        // it must add Transfer-Encoding: chunked, otherwise HttpResponseEncoder
        // throws "must declare either Content-Length or Transfer-Encoding: chunked"
        // and the connection closes mid-response (curl: "Empty reply from server").
        //
        // Pre-fix (PR #494 commit 6fafe524): rewriteHeaders stripped CL but did
        // not add TE. This test would have failed with `null` for the TE header.
        val state = ChainState()
        val handler = CompressionHandler(registry, DefaultAllocator)
        val ctx = TestCtx(state)

        handler.onRead(
            ctx,
            HttpRequestHead(
                method = HttpMethod.GET,
                uri = "/streaming",
                version = HttpVersion.HTTP_1_1,
                headers = HttpHeaders().apply { add("Accept-Encoding", "upper") },
            ),
        )

        // Streaming HttpResponseHead — has Content-Length: 5 (full body size
        // before compression), but the streaming code path does NOT know the
        // post-compression size, so the handler must transition to chunked.
        val head = HttpResponseHead(
            status = HttpStatus(200),
            headers = HttpHeaders().apply {
                add("Content-Length", "5")
                add("Content-Type", "text/plain")
            },
        )
        handler.onWrite(ctx, head)

        val emittedHead = state.writes.filterIsInstance<HttpResponseHead>().single()
        // Compressed: CE set, CL stripped, TE: chunked added.
        assertEquals("upper", emittedHead.headers.getString("Content-Encoding"))
        assertNull(emittedHead.headers.getString("Content-Length"))
        assertEquals("chunked", emittedHead.headers.getString("Transfer-Encoding"))
        assertEquals("Accept-Encoding", emittedHead.headers.getString("Vary"))
    }

    @Test
    fun `streaming response strips pre-existing Transfer-Encoding before re-setting chunked`() {
        // Belt-and-suspenders: a caller that pre-sets Transfer-Encoding: chunked
        // (e.g. an upstream handler that already decided streaming) should still
        // see exactly one Transfer-Encoding value (no duplicates) after rewrite.
        // RFC 9112 §6.1 forbids both Content-Length and Transfer-Encoding;
        // duplicate Transfer-Encoding fields would also be invalid.
        val state = ChainState()
        val handler = CompressionHandler(registry, DefaultAllocator)
        val ctx = TestCtx(state)

        handler.onRead(
            ctx,
            HttpRequestHead(
                HttpMethod.GET,
                "/x",
                HttpVersion.HTTP_1_1,
                HttpHeaders().apply { add("Accept-Encoding", "upper") },
            ),
        )

        val head = HttpResponseHead(
            status = HttpStatus(200),
            headers = HttpHeaders().apply {
                add("Transfer-Encoding", "chunked")
                add("Content-Type", "text/plain")
            },
        )
        handler.onWrite(ctx, head)

        val emittedHead = state.writes.filterIsInstance<HttpResponseHead>().single()
        assertEquals("upper", emittedHead.headers.getString("Content-Encoding"))
        assertNull(emittedHead.headers.getString("Content-Length"))
        // Exactly one Transfer-Encoding header value, set to chunked.
        assertEquals("chunked", emittedHead.headers.getString("Transfer-Encoding"))
    }

    @Test
    fun `passes through when client does not accept any registered encoding`() {
        val state = ChainState()
        val handler = CompressionHandler(registry, DefaultAllocator)
        val ctx = TestCtx(state)

        handler.onRead(
            ctx,
            HttpRequestHead(
                HttpMethod.GET,
                "/",
                HttpVersion.HTTP_1_1,
                HttpHeaders().apply {
                    add("Accept-Encoding", "br;q=1.0")
                },
            ),
        )
        val head = HttpResponseHead(
            HttpStatus(200),
            headers = HttpHeaders().apply { add("Content-Type", "text/plain") },
        )
        handler.onWrite(ctx, head)
        handler.onWrite(ctx, HttpBody(bufOf("hello")))
        handler.onWrite(ctx, HttpBodyEnd.EMPTY)

        val emittedHead = state.writes.filterIsInstance<HttpResponseHead>().single()
        assertNull(emittedHead.headers.getString("Content-Encoding"))
        // Body bytes unchanged
        val bodies = state.writes.filterIsInstance<HttpBody>().filter { it !is HttpBodyEnd }
        assertEquals("hello", bodies.joinToString("") { ioBufAsString(it.content) })
    }

    @Test
    fun `skips compression for 204 No Content`() {
        val state = ChainState()
        val handler = CompressionHandler(registry, DefaultAllocator)
        val ctx = TestCtx(state)

        handler.onRead(
            ctx,
            HttpRequestHead(
                HttpMethod.GET,
                "/",
                HttpVersion.HTTP_1_1,
                HttpHeaders().apply {
                    add("Accept-Encoding", "upper")
                },
            ),
        )
        handler.onWrite(ctx, HttpResponseHead(HttpStatus(204), headers = HttpHeaders()))
        handler.onWrite(ctx, HttpBodyEnd.EMPTY)

        val emittedHead = state.writes.filterIsInstance<HttpResponseHead>().single()
        assertNull(emittedHead.headers.getString("Content-Encoding"))
    }

    @Test
    fun `pipelined requests beyond the pending cap fail the connection`() {
        // S-C2 (4th deep-review): acceptQueue grew without limit, so a
        // client pipelining request heads without reading responses was a
        // slowloris-style resource-exhaustion vector. The handler now caps
        // pending responses; the head that would exceed the cap throws.
        val state = ChainState()
        val cap = 3
        val handler = CompressionHandler(registry, DefaultAllocator, maxPendingResponses = cap)
        val ctx = TestCtx(state)
        fun req() = HttpRequestHead(
            HttpMethod.GET,
            "/",
            HttpVersion.HTTP_1_1,
            HttpHeaders().apply { add("Accept-Encoding", "upper") },
        )
        // `cap` heads with no responses fill the queue exactly.
        repeat(cap) { handler.onRead(ctx, req()) }
        // The next head exceeds the cap → fail-fast.
        val ex = assertFailsWith<IllegalStateException> { handler.onRead(ctx, req()) }
        assertTrue(
            ex.message?.contains("too many pipelined requests") == true,
            "expected pending-cap message, got: ${ex.message}",
        )
    }

    // --- config validation (5th deep-review, lens G) ---

    @Test
    fun `rejects a non-positive maxPendingResponses at construction`() {
        // Pre-fix: maxPendingResponses = 0 makes `acceptQueue.size < 0` fail on
        // the very first request, bricking every connection. Post-fix: rejected
        // loudly at construction.
        assertFailsWith<IllegalArgumentException> {
            CompressionHandler(registry, DefaultAllocator, maxPendingResponses = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            CompressionHandler(registry, DefaultAllocator, maxPendingResponses = -1)
        }
    }

    @Test
    fun `rejects a non-positive scratchCapacity at construction`() {
        assertFailsWith<IllegalArgumentException> {
            CompressionHandler(registry, DefaultAllocator, scratchCapacity = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            CompressionHandler(registry, DefaultAllocator, scratchCapacity = -8)
        }
    }

    @Test
    fun `skips compression for already-encoded response`() {
        val state = ChainState()
        val handler = CompressionHandler(registry, DefaultAllocator)
        val ctx = TestCtx(state)

        handler.onRead(
            ctx,
            HttpRequestHead(
                HttpMethod.GET,
                "/",
                HttpVersion.HTTP_1_1,
                HttpHeaders().apply {
                    add("Accept-Encoding", "upper")
                },
            ),
        )
        val head = HttpResponseHead(
            status = HttpStatus(200),
            headers = HttpHeaders().apply { add("Content-Encoding", "br") },
        )
        handler.onWrite(ctx, head)
        handler.onWrite(ctx, HttpBodyEnd.EMPTY)

        val emittedHead = state.writes.filterIsInstance<HttpResponseHead>().single()
        // Pre-existing CE preserved (handler skipped).
        assertEquals("br", emittedHead.headers.getString("Content-Encoding"))
    }

    @Test
    fun `handlerRemoved mid-response releases the working buffer and closes the session`() {
        // I-2 (4th deep-review): the connection can be torn down mid-response
        // (peer reset before HttpBodyEnd), firing handlerRemoved while
        // activeSession != null and the pooled working buffer is still held
        // (handleResponseHead allocates both: newSession + ensureWorking).
        // handlerRemoved must release the working buffer and close the open
        // session — otherwise a peer that resets mid-response leaks one pooled
        // buffer + one EncoderSession per connection. Characterization: the
        // existing cleanup already does this; this pins it (no prior test
        // exercised the mid-stream handlerRemoved path).
        val tracker = TrackingAllocator(DefaultAllocator)
        val encoder = CountingUpperEncoder()
        val registry = CompressionRegistry().apply { registerEncoder(encoder) }
        val handler = CompressionHandler(registry, tracker)
        val ctx = TestCtx(ChainState())

        handler.onRead(ctx, reqUpper("/a"))
        handler.onWrite(ctx, head200()) // streaming head → newSession + ensureWorking (both live)
        // No HttpBodyEnd — the connection is torn down here.
        handler.handlerRemoved(ctx)

        tracker.assertNoLeaks("working buffer leaked on mid-response handlerRemoved")
        assertEquals(0, encoder.openSessions, "encoder session must be closed on mid-response handlerRemoved")
    }

    @Test
    fun `a mid-stream failure does not leak the encoder session into the next response`() {
        // A response that aborts mid-stream (here: an emit allocation fails)
        // must not leak its EncoderSession. The handler is per-connection and
        // reused for every response, so a leaked session accumulates on each
        // keep-alive request. Pre-fix: handleResponseHead overwrote activeSession
        // for response B without closing response A's, leaking it.
        val encoder = CountingUpperEncoder()
        val registry = CompressionRegistry().apply { registerEncoder(encoder) }
        val state = ChainState()
        val handler = CompressionHandler(registry, DefaultAllocator)
        // body A "hello" -> "HELLO" is handed downstream, where the chain
        // rejects the body chunk (throws) — aborting response A mid-stream.
        val ctx = ctxAbortingFirstBody(state)

        handler.onRead(ctx, reqUpper("/a"))
        handler.onWrite(ctx, head200())
        assertFailsWith<IllegalStateException>("expected the injected downstream rejection to abort response A") {
            handler.onWrite(ctx, HttpBody(bufOf("hello")))
        }

        // Response B completes normally on the same handler (keep-alive reuse).
        handler.onRead(ctx, reqUpper("/b"))
        handler.onWrite(ctx, head200())
        handler.onWrite(ctx, HttpBody(bufOf("worldwide")))
        handler.onWrite(ctx, HttpBodyEnd.EMPTY)

        assertEquals(0, encoder.openSessions, "response A's encoder session must be closed, not leaked")
    }

    @Test
    fun `a mid-stream encoder failure releases the held working buffer`() {
        // When the abort comes from inside the codec (session.update throws)
        // the handler still holds the working buffer it acquired for that
        // update — it was not handed off. discardPendingResponse must release
        // it (the old persistent-scratch shape only cleared it, but the new
        // per-emit buffer must be freed or it leaks one pooled buffer per
        // aborted response on a keep-alive connection). TrackingAllocator's
        // outstandingCount surfaces the leak.
        val tracker = TrackingAllocator(DefaultAllocator)
        val registry = CompressionRegistry().apply { registerEncoder(ThrowOnUpdateEncoder()) }
        val handler = CompressionHandler(registry, tracker)
        val ctx = TestCtx(ChainState())

        handler.onRead(ctx, reqUpper("/a"))
        handler.onWrite(ctx, head200()) // ensureWorking allocates the working buffer
        assertFailsWith<IllegalStateException>("expected the encoder update failure to abort the response") {
            handler.onWrite(ctx, HttpBody(bufOf("hello")))
        }
        // The working buffer acquired in handleResponseHead must have been
        // released by discardPendingResponse — nothing left outstanding.
        tracker.assertNoLeaks("working buffer leaked on mid-update abort")
    }

    @Test
    fun `emitWorking releases the working buffer when propagateWrite throws`() {
        // M2 (4-th deep-review): `emitWorking` hands the pooled working
        // buffer downstream via `ctx.propagateWrite(HttpBody(buf))`. The
        // pipeline contract is that ownership transfers only when the call
        // returns normally; a synchronous throw from a downstream handler
        // leaves `buf` orphaned — `working` is already null so
        // `discardPendingResponse` cannot find it. Before the fix the
        // propagateWrite call was outside any try, leaking one pooled
        // buffer per aborted chunk. Pinned by Red-Green:
        // TrackingAllocator.outstandingCount reports nonzero on the
        // pre-fix handler and zero after the wrap.
        val tracker = TrackingAllocator(DefaultAllocator)
        val registry = CompressionRegistry().apply { registerEncoder(UpperEncoder) }
        val handler = CompressionHandler(registry, tracker)
        val ctx = ctxAbortingFirstBodyKeepingOwnership(ChainState(), allocator = tracker)

        handler.onRead(ctx, reqUpper("/a"))
        handler.onWrite(ctx, head200())
        assertFailsWith<IllegalStateException>("expected the propagateWrite throw to surface from onWrite") {
            handler.onWrite(ctx, HttpBody(bufOf("hello")))
        }
        // handlerRemoved releases any state the handler still owns; the
        // only outstanding allocation that could remain is the working
        // buffer if the fix were absent.
        handler.handlerRemoved(ctx)
        tracker.assertNoLeaks("working buffer leaked on propagateWrite throw")
    }

    @Test
    fun `a mid-stream failure does not bleed leftover bytes into the next response`() {
        // When a response aborts mid-stream the handler must not carry any
        // partially-compressed bytes into the next response on the same
        // keep-alive connection. The streaming path hands the working buffer
        // straight downstream and `discardPendingResponse` releases + nulls it
        // on abort, so the next response acquires a fresh buffer — response B's
        // body must decode to exactly its own bytes.
        val encoder = CountingUpperEncoder()
        val registry = CompressionRegistry().apply { registerEncoder(encoder) }
        val state = ChainState()
        val handler = CompressionHandler(registry, DefaultAllocator)
        val ctx = ctxAbortingFirstBody(state)

        handler.onRead(ctx, reqUpper("/a"))
        handler.onWrite(ctx, head200())
        assertFailsWith<IllegalStateException>("expected response A to abort") {
            handler.onWrite(ctx, HttpBody(bufOf("hello")))
        }

        handler.onRead(ctx, reqUpper("/b"))
        handler.onWrite(ctx, head200())
        handler.onWrite(ctx, HttpBody(bufOf("worldwide")))
        handler.onWrite(ctx, HttpBodyEnd.EMPTY)

        val bodies = state.writes.filterIsInstance<HttpBody>().filter { it !is HttpBodyEnd }
        val decoded = bodies.joinToString("") { ioBufAsString(it.content) }
        assertEquals("WORLDWIDE", decoded, "response B must not carry response A's leftover bytes")
    }

    @Test
    fun `aggregated response that fails after session creation does not leak the session`() {
        // Regression for the deep-review MUST: handleAggregatedResponse
        // creates the EncoderSession before ensureScratch / propagateWrite,
        // so a throw from either window would orphan the session. Inject the
        // failure via the scratch IoBuf allocate (size = SCRATCH_CAPACITY)
        // and confirm the encoder's open-session counter is back to zero on
        // the next response.
        val encoder = CountingUpperEncoder()
        val registry = CompressionRegistry().apply { registerEncoder(encoder) }
        val state = ChainState()
        val handler = CompressionHandler(
            registry,
            FailOnSizeAllocator(failSize = CompressionHandler.SCRATCH_CAPACITY),
        )
        val ctx = TestCtx(state)

        handler.onRead(ctx, reqUpper("/a"))
        val message = "scratch allocation failure must propagate out of handleAggregatedResponse"
        assertFailsWith<IllegalStateException>(message) {
            handler.onWrite(
                ctx,
                HttpResponse(
                    status = HttpStatus(200),
                    headers = HttpHeaders().apply {
                        add("Content-Length", "5")
                        add("Content-Type", "text/plain")
                    },
                    body = "hello".encodeToByteArray(),
                ),
            )
        }
        assertEquals(
            0,
            encoder.openSessions,
            "the EncoderSession created before ensureScratch must be closed on the failure path",
        )
    }

    @Test
    fun `streaming response head that fails after session creation does not leak the session`() {
        // Symmetric to the aggregated test above, for handleResponseHead.
        val encoder = CountingUpperEncoder()
        val registry = CompressionRegistry().apply { registerEncoder(encoder) }
        val state = ChainState()
        val handler = CompressionHandler(
            registry,
            FailOnSizeAllocator(failSize = CompressionHandler.SCRATCH_CAPACITY),
        )
        val ctx = TestCtx(state)

        handler.onRead(ctx, reqUpper("/a"))
        assertFailsWith<IllegalStateException>("scratch allocation failure must propagate out of handleResponseHead") {
            handler.onWrite(ctx, head200())
        }
        assertEquals(
            0,
            encoder.openSessions,
            "the EncoderSession created before ensureScratch must be closed on the failure path",
        )
    }

    @Test
    fun `multiple Vary entries on the source response are preserved`() {
        // Regression for the rewriteHeaders deep-review finding: a response
        // carrying multiple `Vary` lines (e.g. `Vary: User-Agent` and
        // `Vary: Cookie`) was dropping every entry past the first because
        // the rewrite used `getString("Vary")`, which only returns the first
        // header value. With `getCombined`, all entries survive and the
        // `this["Vary"] = …` set then collapses them into one merged line
        // that also includes `Accept-Encoding`.
        val state = ChainState()
        val handler = CompressionHandler(registry, DefaultAllocator)
        val ctx = TestCtx(state)

        handler.onRead(ctx, reqUpper("/x"))
        handler.onWrite(
            ctx,
            HttpResponseHead(
                status = HttpStatus(200),
                headers = HttpHeaders().apply {
                    add("Content-Type", "text/plain")
                    add("Vary", "User-Agent")
                    add("Vary", "Cookie")
                },
            ),
        )

        val emittedHead = state.writes.filterIsInstance<HttpResponseHead>().single()
        val vary = emittedHead.headers.getCombined("Vary")
        assertNotNull(vary, "Vary header must be present after compression")
        // Order is "the existing entries (in their input order) + Accept-Encoding".
        // Combining repeated header lines yields a comma-joined value.
        for (expected in listOf("User-Agent", "Cookie", "Accept-Encoding")) {
            assertTrue(expected in vary, "Vary must keep '$expected' (got: $vary)")
        }
    }

    @Test
    fun `aggregated response uses the allocator's wrapBytes view for the codec input when supported`() {
        // Pins the #670 zero-copy view path: when the allocator returns a
        // non-null `wrapBytes`, CompressionHandler feeds the codec that view
        // instead of allocating + copying. Up to now every test exercised the
        // null-returning DefaultAllocator only, so the view path itself was
        // untested. A stub allocator records every `wrapBytes` call and the
        // assertions confirm the handler took the view path.
        val tracker = WrapBytesTrackingAllocator()
        val state = ChainState()
        val handler = CompressionHandler(registry, tracker)
        val ctx = TestCtx(state)

        handler.onRead(ctx, reqUpper("/x"))
        val body = "wrap-bytes view test".encodeToByteArray()
        handler.onWrite(
            ctx,
            HttpResponse(
                status = HttpStatus(200),
                headers = HttpHeaders().apply {
                    add("Content-Length", body.size.toString())
                    add("Content-Type", "text/plain")
                },
                body = body,
            ),
        )

        assertEquals(1, tracker.wrapBytesCalls, "the aggregated path must feed the codec a wrapBytes view")
        // The view must show the full body, not a pre-truncated slice.
        assertEquals(body.size, tracker.lastWrapBytesLength)
        // Output bytes are still produced correctly via the chunked stream.
        val bodies = state.writes.filterIsInstance<HttpBody>().filter { it !is HttpBodyEnd }
        val encoded = bodies.joinToString("") { ioBufAsString(it.content) }
        assertEquals(body.decodeToString().uppercase(), encoded)
    }

    /**
     * Allocator that delegates to [DefaultAllocator] but records every
     * `wrapBytes` call so a test can confirm the handler took the view path
     * rather than the allocate-and-copy fallback.
     */
    private class WrapBytesTrackingAllocator : BufferAllocator {
        var wrapBytesCalls: Int = 0
            private set
        var lastWrapBytesLength: Int = -1
            private set

        override fun allocate(capacity: Int): IoBuf = DefaultAllocator.allocate(capacity)
        override fun slice(source: IoBuf, offset: Int, length: Int): IoBuf =
            DefaultAllocator.slice(source, offset, length)

        override fun wrapBytes(bytes: ByteArray, offset: Int, length: Int): IoBuf {
            wrapBytesCalls++
            lastWrapBytesLength = length
            // Hand back a real heap-backed buffer so the codec can read from
            // it; the view aliasing the underlying ByteArray is not required
            // for the assertion (which only checks that wrapBytes was called).
            return DefaultAllocator.allocate(length).apply { writeByteArray(bytes, offset, length) }
        }
    }

    // ---- status-code exemptions (RFC 9110 §15) ----

    @Test
    fun `compression is skipped on 206 Partial Content responses`() {
        // Compressing a 206 body invalidates Content-Range: the header points
        // at a byte range of the *unencoded* representation, so post-encode
        // the body is no longer the bytes the client asked for. nginx,
        // Apache mod_deflate, and major CDNs all skip 206 for this reason.
        val state = ChainState()
        val handler = CompressionHandler(registry, DefaultAllocator)
        val ctx = TestCtx(state)
        handler.onRead(ctx, reqUpper("/range"))

        val head = HttpResponseHead(
            status = HttpStatus(206),
            headers = HttpHeaders().apply {
                add("Content-Length", "5")
                add("Content-Range", "bytes 0-4/100")
                add("Content-Type", "text/plain")
            },
        )
        handler.onWrite(ctx, head)
        handler.onWrite(ctx, HttpBody(bufOf("hello")))
        handler.onWrite(ctx, HttpBodyEnd.EMPTY)

        val emittedHead = state.writes.filterIsInstance<HttpResponseHead>().single()
        assertNull(emittedHead.headers.getString("Content-Encoding"), "206 must not be re-encoded")
        assertEquals("bytes 0-4/100", emittedHead.headers.getString("Content-Range"))
        assertEquals("5", emittedHead.headers.getString("Content-Length"))

        // Body is passed through verbatim (no codec involvement).
        val emittedBodies = state.writes.filterIsInstance<HttpBody>().filter { it !is HttpBodyEnd }
        assertEquals("hello", emittedBodies.joinToString("") { ioBufAsString(it.content) })
    }

    @Test
    fun `compression is skipped on aggregated 206 Partial Content responses`() {
        // Same skip applies on the aggregated branch (handleAggregatedResponse).
        val state = ChainState()
        val handler = CompressionHandler(registry, DefaultAllocator)
        val ctx = TestCtx(state)
        handler.onRead(ctx, reqUpper("/range"))

        val response = HttpResponse(
            status = HttpStatus(206),
            version = HttpVersion.HTTP_1_1,
            headers = HttpHeaders().apply {
                add("Content-Length", "5")
                add("Content-Range", "bytes 0-4/100")
                add("Content-Type", "text/plain")
            },
            body = "hello".encodeToByteArray(),
        )
        handler.onWrite(ctx, response)

        // 206 should remain as a single aggregated HttpResponse — no
        // chunked conversion, no Content-Encoding.
        val emitted = state.writes.single() as HttpResponse
        assertEquals(206, emitted.status.code)
        assertNull(emitted.headers.getString("Content-Encoding"), "206 must not be re-encoded")
        assertEquals("bytes 0-4/100", emitted.headers.getString("Content-Range"))
        assertContentEquals("hello".encodeToByteArray(), emitted.body)
    }

    // ---- helpers ----

    private fun reqUpper(uri: String): HttpRequestHead = HttpRequestHead(
        HttpMethod.GET,
        uri,
        HttpVersion.HTTP_1_1,
        HttpHeaders().apply { add("Accept-Encoding", "upper") },
    )

    private fun head200(): HttpResponseHead = HttpResponseHead(
        status = HttpStatus(200),
        headers = HttpHeaders().apply { add("Content-Type", "text/plain") },
    )

    /**
     * Wraps [DefaultAllocator] but throws on any [allocate] whose capacity
     * equals [failSize], to simulate an allocation failure during a
     * response's mid-stream emit. All other sizes delegate normally.
     */
    private class FailOnSizeAllocator(private val failSize: Int) : BufferAllocator {
        override fun allocate(capacity: Int): IoBuf {
            if (capacity == failSize) {
                throw IllegalStateException("simulated allocation failure (size=$capacity)")
            }
            return DefaultAllocator.allocate(capacity)
        }

        override fun wrapBytes(bytes: ByteArray, offset: Int, length: Int): IoBuf? = null

        override fun slice(source: IoBuf, offset: Int, length: Int): IoBuf =
            DefaultAllocator.slice(source, offset, length)
    }

    /**
     * Uppercasing encoder (same streaming logic as [UpperEncoder]) that also
     * tracks how many sessions are currently open, so a leak across responses
     * is observable as a non-zero count.
     */
    /**
     * Encoder whose session throws from [EncoderSession.update] — simulates a
     * codec-internal failure mid-stream so the handler aborts while still
     * holding the working buffer it acquired for that update.
     */
    private class ThrowOnUpdateEncoder : Encoder {
        override val name: String = "upper"
        override fun newSession(allocator: BufferAllocator, options: EncoderOptions): EncoderSession =
            object : EncoderSession {
                override fun update(input: IoBuf, output: IoBuf): CodecStatus {
                    input.readByteArray(ByteArray(input.readableBytes), 0, input.readableBytes)
                    throw IllegalStateException("simulated codec-internal failure")
                }

                override fun finish(output: IoBuf): CodecStatus = CodecStatus.FINISHED
                override fun reset() {}
                override fun close() {}
            }
    }

    private class CountingUpperEncoder : Encoder {
        var openSessions: Int = 0
            private set

        override val name: String = "upper"

        override fun newSession(allocator: BufferAllocator, options: EncoderOptions): EncoderSession {
            openSessions++
            return object : EncoderSession {
                private var pending: ByteArray = ByteArray(0)
                private var finished: Boolean = false
                private var closed: Boolean = false

                override fun update(input: IoBuf, output: IoBuf): CodecStatus {
                    val n = input.readableBytes
                    if (n > 0) {
                        val tmp = ByteArray(n)
                        input.readByteArray(tmp, 0, n)
                        pending = pending + tmp.decodeToString().uppercase().encodeToByteArray()
                    }
                    return drain(output, isFinish = false)
                }

                override fun finish(output: IoBuf): CodecStatus = drain(output, isFinish = true)

                private fun drain(output: IoBuf, isFinish: Boolean): CodecStatus {
                    if (pending.isEmpty()) {
                        if (isFinish) finished = true
                        return if (isFinish) CodecStatus.FINISHED else CodecStatus.NEED_INPUT
                    }
                    val toWrite = minOf(pending.size, output.writableBytes)
                    if (toWrite > 0) {
                        output.writeByteArray(pending, 0, toWrite)
                        pending = pending.copyOfRange(toWrite, pending.size)
                    }
                    return when {
                        pending.isNotEmpty() -> CodecStatus.NEED_OUTPUT
                        isFinish -> {
                            finished = true
                            CodecStatus.FINISHED
                        }
                        else -> CodecStatus.NEED_INPUT
                    }
                }

                override fun reset() {
                    pending = ByteArray(0)
                    finished = false
                }

                override fun close() {
                    if (!closed) {
                        closed = true
                        openSessions--
                    }
                }
            }
        }
    }

    /** Stub encoder: uppercases ASCII bytes via the streaming SPI. */
    private object UpperEncoder : Encoder {
        override val name: String = "upper"
        override fun newSession(
            allocator: io.github.fukusaka.keel.buf.BufferAllocator,
            options: EncoderOptions,
        ): EncoderSession = object : EncoderSession {
            private var pending: ByteArray = ByteArray(0)
            private var finished: Boolean = false
            override fun update(input: IoBuf, output: IoBuf): io.github.fukusaka.keel.compression.CodecStatus {
                val n = input.readableBytes
                if (n > 0) {
                    val tmp = ByteArray(n)
                    input.readByteArray(tmp, 0, n)
                    val upper = tmp.decodeToString().uppercase().encodeToByteArray()
                    pending = pending + upper
                }
                return drain(output, isFinish = false)
            }
            override fun finish(output: IoBuf): io.github.fukusaka.keel.compression.CodecStatus {
                return drain(output, isFinish = true)
            }
            private fun drain(
                output: IoBuf,
                isFinish: Boolean,
            ): io.github.fukusaka.keel.compression.CodecStatus {
                if (pending.isEmpty()) {
                    return if (isFinish && !finished) {
                        finished = true
                        io.github.fukusaka.keel.compression.CodecStatus.FINISHED
                    } else if (isFinish) {
                        io.github.fukusaka.keel.compression.CodecStatus.FINISHED
                    } else {
                        io.github.fukusaka.keel.compression.CodecStatus.NEED_INPUT
                    }
                }
                val toWrite = minOf(pending.size, output.writableBytes)
                if (toWrite > 0) {
                    output.writeByteArray(pending, 0, toWrite)
                    pending = pending.copyOfRange(toWrite, pending.size)
                }
                return if (pending.isNotEmpty()) {
                    io.github.fukusaka.keel.compression.CodecStatus.NEED_OUTPUT
                } else if (isFinish) {
                    finished = true
                    io.github.fukusaka.keel.compression.CodecStatus.FINISHED
                } else {
                    io.github.fukusaka.keel.compression.CodecStatus.NEED_INPUT
                }
            }
            override fun reset() {
                pending = ByteArray(0)
                finished = false
            }
            override fun close() {}
        }
    }

    /** [HttpBody] with a mutable [content], mirroring keel-server-http's `ReusableHttpBody`. */
    private class MutableTestHttpBody(initial: IoBuf) : HttpBody(initial) {
        override var content: IoBuf = initial
    }

    private class ChainState {
        val writes: MutableList<Any> = mutableListOf()
        val reads: MutableList<Any> = mutableListOf()
    }

    private class TestCtx(
        val state: ChainState,
        // Injects a mid-stream failure: invoked before each outbound message is
        // recorded, so returning normally records the write and throwing aborts
        // it (simulating a downstream handler rejecting the body). Used by the
        // mid-stream-recovery tests now that the streaming path hands the
        // working buffer straight downstream (no exact-size emit allocation to
        // target via the allocator).
        private val beforeWrite: ((Any) -> Unit)? = null,
        override val allocator: io.github.fukusaka.keel.buf.BufferAllocator = DefaultAllocator,
    ) : io.github.fukusaka.keel.pipeline.PipelineHandlerContext {
        override val name: String get() = "test"
        override val pipeline: io.github.fukusaka.keel.pipeline.Pipeline
            get() = error("not used")
        override val channel: io.github.fukusaka.keel.pipeline.PipelinedChannel
            get() = error("not used")
        override val handler: io.github.fukusaka.keel.pipeline.PipelineHandler
            get() = error("not used")
        override fun propagateRead(msg: Any) { state.reads.add(msg) }
        override fun propagateActive() {}
        override fun propagateInactive() {}
        override fun propagateReadComplete() {}
        override fun propagateError(cause: Throwable) {}
        override fun propagateUserEvent(event: Any) {}
        override fun propagateWritabilityChanged(isWritable: Boolean) {}
        override fun propagateWrite(msg: Any) {
            beforeWrite?.invoke(msg)
            state.writes.add(msg)
        }
        override fun propagateFlush() {}
        override fun propagateClose() {}
    }

    /**
     * Builds a [TestCtx] that throws once on the first [HttpBody] (non-end)
     * write — aborting a response mid-stream after its head was emitted. The
     * thrown [HttpBody]'s buffer is released here so the test does not leak it
     * (production hands the buffer downstream where the transport releases it
     * after `writev`; in-test there is no transport).
     */
    private fun ctxAbortingFirstBody(state: ChainState): TestCtx {
        var thrown = false
        return TestCtx(state, beforeWrite = { msg ->
            if (!thrown && msg is HttpBody && msg !is HttpBodyEnd) {
                thrown = true
                msg.content.release()
                throw IllegalStateException("simulated downstream rejection of body chunk")
            }
        })
    }

    /**
     * Builds a [TestCtx] that throws once on the first [HttpBody] (non-end)
     * write **without releasing the buffer first**.
     *
     * The pipeline contract is "ownership transfers only when propagate
     * returns normally", so a downstream throw leaves the buffer with the
     * source. Releasing here would mask the very leak the test is
     * designed to catch (M2: `emitWorking` ownership-on-throw).
     */
    private fun ctxAbortingFirstBodyKeepingOwnership(
        state: ChainState,
        allocator: io.github.fukusaka.keel.buf.BufferAllocator,
    ): TestCtx {
        var thrown = false
        return TestCtx(state, beforeWrite = { msg ->
            if (!thrown && msg is HttpBody && msg !is HttpBodyEnd) {
                thrown = true
                throw IllegalStateException("simulated downstream rejection of body chunk")
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
