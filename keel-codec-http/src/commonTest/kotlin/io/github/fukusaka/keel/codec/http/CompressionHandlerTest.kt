package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
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
        assertEquals("upper", emittedHead.headers["Content-Encoding"])
        assertNull(emittedHead.headers["Content-Length"])
        assertEquals("Accept-Encoding", emittedHead.headers["Vary"])

        // Verify body got encoded.
        val bodies = state.writes.filterIsInstance<HttpBody>().filter { it !is HttpBodyEnd }
        val encodedBytes = bodies.joinToString("") { ioBufAsString(it.content) }
        assertTrue(encodedBytes.startsWith("HELLO"), "expected uppercased body, got: $encodedBytes")

        // HttpBodyEnd at tail.
        assertNotNull(state.writes.lastOrNull() as? HttpBodyEnd)
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
        assertNull(emittedHead.headers["Content-Encoding"])
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
        assertNull(emittedHead.headers["Content-Encoding"])
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
        assertEquals("br", emittedHead.headers["Content-Encoding"])
    }

    // ---- helpers ----

    /** Stub encoder: uppercases ASCII bytes. */
    private object UpperEncoder : Encoder {
        override val name: String = "upper"
        override fun newSession(allocator: io.github.fukusaka.keel.buf.BufferAllocator, options: EncoderOptions): EncoderSession =
            object : EncoderSession {
                override fun update(input: IoBuf): IoBuf {
                    val n = input.readableBytes
                    val tmp = ByteArray(n)
                    input.readByteArray(tmp, 0, n)
                    input.release()
                    val upper = String(tmp, Charsets.US_ASCII).uppercase().encodeToByteArray()
                    val out = allocator.allocate(upper.size.coerceAtLeast(64))
                    out.writeByteArray(upper, 0, upper.size)
                    return out
                }
                override fun finish(): IoBuf = allocator.allocate(64)
                override fun reset() {}
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
