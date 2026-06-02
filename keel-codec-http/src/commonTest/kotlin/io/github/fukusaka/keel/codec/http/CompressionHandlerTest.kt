package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.compression.CodecStatus
import io.github.fukusaka.keel.compression.CompressionRegistry
import io.github.fukusaka.keel.compression.Encoder
import io.github.fukusaka.keel.compression.EncoderOptions
import io.github.fukusaka.keel.compression.EncoderSession
import kotlin.test.Test
import kotlin.test.assertEquals
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
            HttpRequestHead(HttpMethod.GET, "/x", HttpVersion.HTTP_1_1, HttpHeaders().apply { add("Accept-Encoding", "upper") }),
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

        handler.onRead(ctx, HttpRequestHead(HttpMethod.GET, "/", HttpVersion.HTTP_1_1, HttpHeaders().apply { add("Accept-Encoding", "br;q=1.0") }))
        val head = HttpResponseHead(HttpStatus(200), headers = HttpHeaders().apply { add("Content-Type", "text/plain") })
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

        handler.onRead(ctx, HttpRequestHead(HttpMethod.GET, "/", HttpVersion.HTTP_1_1, HttpHeaders().apply { add("Accept-Encoding", "upper") }))
        handler.onWrite(ctx, HttpResponseHead(HttpStatus(204), headers = HttpHeaders()))
        handler.onWrite(ctx, HttpBodyEnd.EMPTY)

        val emittedHead = state.writes.filterIsInstance<HttpResponseHead>().single()
        assertNull(emittedHead.headers.getString("Content-Encoding"))
    }

    @Test
    fun `skips compression for already-encoded response`() {
        val state = ChainState()
        val handler = CompressionHandler(registry, DefaultAllocator)
        val ctx = TestCtx(state)

        handler.onRead(ctx, HttpRequestHead(HttpMethod.GET, "/", HttpVersion.HTTP_1_1, HttpHeaders().apply { add("Accept-Encoding", "upper") }))
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
    fun `a mid-stream failure does not leak the encoder session into the next response`() {
        // A response that aborts mid-stream (here: an emit allocation fails)
        // must not leak its EncoderSession. The handler is per-connection and
        // reused for every response, so a leaked session accumulates on each
        // keep-alive request. Pre-fix: handleResponseHead overwrote activeSession
        // for response B without closing response A's, leaking it.
        val encoder = CountingUpperEncoder()
        val registry = CompressionRegistry().apply { registerEncoder(encoder) }
        val state = ChainState()
        // body A "hello" -> "HELLO" emits 5 bytes; the allocator fails that emit.
        val handler = CompressionHandler(registry, FailOnSizeAllocator(failSize = 5))
        val ctx = TestCtx(state)

        handler.onRead(ctx, reqUpper("/a"))
        handler.onWrite(ctx, head200())
        var aborted = false
        try {
            handler.onWrite(ctx, HttpBody(bufOf("hello")))
        } catch (e: IllegalStateException) {
            aborted = true
        }
        assertTrue(aborted, "expected the injected allocation failure to abort response A")

        // Response B completes normally on the same handler (keep-alive reuse).
        handler.onRead(ctx, reqUpper("/b"))
        handler.onWrite(ctx, head200())
        handler.onWrite(ctx, HttpBody(bufOf("worldwide")))
        handler.onWrite(ctx, HttpBodyEnd.EMPTY)

        assertEquals(0, encoder.openSessions, "response A's encoder session must be closed, not leaked")
    }

    @Test
    fun `a mid-stream failure does not bleed leftover bytes into the next response`() {
        // The per-channel scratch buffer is reused across responses. When a
        // response aborts mid-emit, scratch can retain partially compressed
        // bytes. Pre-fix: the next response appended its output to that
        // leftover, so response B's body started with response A's bytes.
        val encoder = CountingUpperEncoder()
        val registry = CompressionRegistry().apply { registerEncoder(encoder) }
        val state = ChainState()
        val handler = CompressionHandler(registry, FailOnSizeAllocator(failSize = 5))
        val ctx = TestCtx(state)

        handler.onRead(ctx, reqUpper("/a"))
        handler.onWrite(ctx, head200())
        var aborted = false
        try {
            handler.onWrite(ctx, HttpBody(bufOf("hello")))
        } catch (e: IllegalStateException) {
            aborted = true
        }
        assertTrue(aborted, "expected response A to abort")

        handler.onRead(ctx, reqUpper("/b"))
        handler.onWrite(ctx, head200())
        handler.onWrite(ctx, HttpBody(bufOf("worldwide")))
        handler.onWrite(ctx, HttpBodyEnd.EMPTY)

        val bodies = state.writes.filterIsInstance<HttpBody>().filter { it !is HttpBodyEnd }
        val decoded = bodies.joinToString("") { ioBufAsString(it.content) }
        assertEquals("WORLDWIDE", decoded, "response B must not carry response A's leftover scratch bytes")
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
                        isFinish -> { finished = true; CodecStatus.FINISHED }
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

    private class ChainState {
        val writes: MutableList<Any> = mutableListOf()
        val reads: MutableList<Any> = mutableListOf()
    }

    private class TestCtx(val state: ChainState) : io.github.fukusaka.keel.pipeline.PipelineHandlerContext {
        override val name: String get() = "test"
        override val pipeline: io.github.fukusaka.keel.pipeline.Pipeline
            get() = error("not used")
        override val channel: io.github.fukusaka.keel.pipeline.PipelinedChannel
            get() = error("not used")
        override val handler: io.github.fukusaka.keel.pipeline.PipelineHandler
            get() = error("not used")
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
