package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
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

    // ------------------------------------------------------------------ chain plumbing

    private class ChainState {
        val writes: MutableList<Any> = mutableListOf()
        val reads: MutableList<Any> = mutableListOf()
    }

    private class TestCtx(val state: ChainState) : PipelineHandlerContext {
        override val name: String get() = "test"
        override val pipeline: PipelineType get() = error("not used")
        override val channel: PipelinedChannel get() = error("not used")
        override val handler: PipelineHandler get() = error("not used")
        override val allocator: io.github.fukusaka.keel.buf.BufferAllocator = DefaultAllocator
        override fun propagateRead(msg: Any) { state.reads.add(msg) }
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
