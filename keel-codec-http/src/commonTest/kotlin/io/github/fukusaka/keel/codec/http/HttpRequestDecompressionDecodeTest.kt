package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.compression.CompressionRegistry
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the decode paths — streaming and aggregated — and for the buffers
 * each releases along the way.
 */
internal class HttpRequestDecompressionDecodeTest : HttpRequestDecompressionFixture() {

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
                HttpMethod.POST,
                "/upload",
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
                HttpMethod.POST,
                "/upload",
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
            registry,
            DefaultAllocator,
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
            method = HttpMethod.POST,
            uri = "/upload",
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
                    HttpMethod.POST,
                    "/upload",
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
}
