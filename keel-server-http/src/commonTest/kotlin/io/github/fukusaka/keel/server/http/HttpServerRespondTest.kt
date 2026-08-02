package io.github.fukusaka.keel.server.http

import io.github.fukusaka.keel.codec.http.HttpHeaderName
import io.github.fukusaka.keel.codec.http.HttpHeaders
import io.github.fukusaka.keel.codec.http.HttpMethod
import io.github.fukusaka.keel.codec.http.HttpResponseHead
import io.github.fukusaka.keel.codec.http.HttpStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the response side: `respondText`, the streaming `respondStream`
 * sink with its trailers and backpressure gate, and the read watermark that
 * pairs with it.
 */
internal class HttpServerRespondTest : HttpServerHandlerFixture() {

    @Test
    fun `respondText sends a text plain response`() {
        install(
            Router().apply {
                register(HttpMethod.GET, "/") { call -> call.respondText("plain body") }
            },
        )

        feedGet("/")

        val text = responseText()
        assertTrue(text.startsWith("HTTP/1.1 200"), "status line: $text")
        assertTrue(text.endsWith("plain body"), "body: $text")
        assertTrue(text.contains("text/plain", ignoreCase = true), "content-type: $text")
    }

    @Test
    fun `respondStream emits a chunked streaming response`() {
        install(
            Router().apply {
                register(HttpMethod.GET, "/stream") { call ->
                    call.respondStream(
                        HttpResponseHead(
                            status = HttpStatus.OK,
                            headers = HttpHeaders.of(HttpHeaderName.TRANSFER_ENCODING to "chunked"),
                        ),
                    ) { sink ->
                        sink.write(bufOf("alpha"))
                        sink.write(bufOf("beta"))
                    }
                }
            },
        )

        feedGet("/stream")

        val text = responseText()
        assertTrue(text.startsWith("HTTP/1.1 200"), "status line: $text")
        assertTrue(text.contains("alpha"), "first chunk: $text")
        assertTrue(text.contains("beta"), "second chunk: $text")
    }

    @Test
    fun `respondStream emits many distinct chunks in order under wrapper reuse`() {
        // Http1ResponseBodySink reuses one HttpBody wrapper across every
        // chunk of a response (L5-b) instead of allocating a fresh one per
        // write. Five distinguishable chunks with no shared substrings
        // guard against a reuse bug aliasing/overwriting an earlier
        // chunk's content before the encoder has consumed it.
        install(
            Router().apply {
                register(HttpMethod.GET, "/stream") { call ->
                    call.respondStream(
                        HttpResponseHead(
                            status = HttpStatus.OK,
                            headers = HttpHeaders.of(HttpHeaderName.TRANSFER_ENCODING to "chunked"),
                        ),
                    ) { sink ->
                        sink.write(bufOf("uno"))
                        sink.write(bufOf("dos"))
                        sink.write(bufOf("tres"))
                        sink.write(bufOf("cuatro"))
                        sink.write(bufOf("cinco"))
                    }
                }
            },
        )

        feedGet("/stream")

        val text = responseText()
        val order = listOf("uno", "dos", "tres", "cuatro", "cinco").map { text.indexOf(it) }
        assertTrue(order.all { it >= 0 }, "every chunk must appear: $text")
        assertTrue(order == order.sorted(), "chunks must appear in write order: $text")
    }

    @Test
    fun `respondStream sink trailers are emitted after the terminal chunk when chunked`() {
        install(
            Router().apply {
                register(HttpMethod.GET, "/stream") { call ->
                    call.respondStream(
                        HttpResponseHead(
                            status = HttpStatus.OK,
                            headers = HttpHeaders.of(HttpHeaderName.TRANSFER_ENCODING to "chunked"),
                        ),
                    ) { sink ->
                        sink.write(bufOf("alpha"))
                        sink.trailers = HttpHeaders.of("X-Checksum" to "abc123")
                    }
                }
            },
        )

        feedGet("/stream")

        val text = responseText()
        assertTrue(text.contains("alpha"), "chunk body: $text")
        // Terminal "0\r\n" chunk followed by the trailer field, then the
        // final CRLF that ends the message (RFC 7230 §4.1.2).
        assertTrue(
            text.contains("0\r\nX-Checksum: abc123\r\n\r\n"),
            "trailer must follow the terminal zero-length chunk: $text",
        )
    }

    @Test
    fun `respondStream sink trailers default does not alias the shared HttpHeaders EMPTY singleton`() {
        // sink.trailers is a mutable var backed by HttpHeaders.add()/set().
        // If the default were the shared HttpHeaders.EMPTY singleton, a
        // caller mutating in place instead of reassigning (idiomatic
        // elsewhere in this codebase) would corrupt "no headers" for every
        // other call site relying on HttpHeaders.EMPTY.
        install(
            Router().apply {
                register(HttpMethod.GET, "/stream") { call ->
                    call.respondStream(
                        HttpResponseHead(
                            status = HttpStatus.OK,
                            headers = HttpHeaders.of(HttpHeaderName.TRANSFER_ENCODING to "chunked"),
                        ),
                    ) { sink ->
                        sink.write(bufOf("alpha"))
                        sink.trailers.add("X-Checksum", "abc123")
                    }
                }
            },
        )

        feedGet("/stream")

        assertTrue(HttpHeaders.EMPTY.isEmpty, "HttpHeaders.EMPTY must stay empty after in-place sink.trailers mutation")
    }

    @Test
    fun `respondStream sink trailers are silently dropped for a Content-Length response`() {
        // RFC 7230 §4.1.2 restricts trailers to chunked encoding — the
        // codec's FIXED-mode encoder path (`encodeContentFixed`) has no
        // trailer framing at all, so setting `trailers` on a
        // Content-Length response must not corrupt or extend the wire
        // output. Pairing a chunked head with trailers is the caller's
        // responsibility (see [HttpResponseBodySink.trailers] KDoc).
        install(
            Router().apply {
                register(HttpMethod.GET, "/stream") { call ->
                    call.respondStream(
                        HttpResponseHead(
                            status = HttpStatus.OK,
                            headers = HttpHeaders.of(HttpHeaderName.CONTENT_LENGTH to "5"),
                        ),
                    ) { sink ->
                        sink.write(bufOf("alpha"))
                        sink.trailers = HttpHeaders.of("X-Checksum" to "abc123")
                    }
                }
            },
        )

        feedGet("/stream")

        val text = responseText()
        assertTrue(text.endsWith("alpha"), "body must end exactly at Content-Length, no trailer leak: $text")
        assertFalse(text.contains("X-Checksum"), "trailer must not appear on a Content-Length response: $text")
    }

    @Test
    fun `respondStream sink gates writes on isWritable backpressure`() {
        // Two chunks; the channel is pinned `not writable` for the whole
        // request. Each `sink.write` call must therefore observe
        // `!isWritable` and call `awaitFlushComplete` — which delegates
        // to the transport's `awaitPendingFlush`. The terminal
        // `HttpBodyEnd.EMPTY` is fired by `respondStream` itself outside
        // the sink so it is NOT gated, matching the design (single
        // small frame, scope of this test is the sink contract).
        install(
            Router().apply {
                register(HttpMethod.GET, "/stream") { call ->
                    call.respondStream(
                        HttpResponseHead(
                            status = HttpStatus.OK,
                            headers = HttpHeaders.of(HttpHeaderName.TRANSFER_ENCODING to "chunked"),
                        ),
                    ) { sink ->
                        sink.write(bufOf("alpha"))
                        sink.write(bufOf("beta"))
                    }
                }
            },
        )

        transport.writableOverride = false
        feedGet("/stream")

        assertEquals(
            2,
            transport.awaitPendingFlushCount,
            "sink.write must call awaitFlushComplete once per chunk while !isWritable",
        )
        // Body chunks still propagate (the gate suspends after the write,
        // not before): the wire shows both chunks plus the terminal 0\r\n.
        val text = responseText()
        assertTrue(text.contains("alpha"), "first chunk: $text")
        assertTrue(text.contains("beta"), "second chunk: $text")
    }

    @Test
    fun `respondStream sink skips await when transport stays writable`() {
        // The default `isWritable = true` path takes the fast path: the
        // sink does not call `awaitFlushComplete`. This pins the cost
        // model so the gate adds zero suspension on the saturating loop
        // workload where pendingBytes never crosses the high-water mark.
        install(
            Router().apply {
                register(HttpMethod.GET, "/stream") { call ->
                    call.respondStream(
                        HttpResponseHead(
                            status = HttpStatus.OK,
                            headers = HttpHeaders.of(HttpHeaderName.TRANSFER_ENCODING to "chunked"),
                        ),
                    ) { sink ->
                        sink.write(bufOf("alpha"))
                        sink.write(bufOf("beta"))
                    }
                }
            },
        )

        // writableOverride defaults to null → falls through to super.isWritable
        // which starts true (pendingBytes 0). Verify the gate stays closed.
        feedGet("/stream")

        assertEquals(
            0,
            transport.awaitPendingFlushCount,
            "writable transport must not trigger the backpressure gate",
        )
    }

    @Test
    fun `receiveChunk pauses reads when pending bytes cross the high watermark`() {
        // The handler hangs on a `CompletableDeferred` so it is alive
        // when the body chunks arrive on the decoder's path: the chunks
        // land in `Http1Call.pending` (no waiter to hand off to). A
        // single 70 KiB chunk crosses the 64 KiB high watermark, so
        // `pauseReads` must fire once. When the test releases the
        // deferred the handler drains the chunk and `resumeReads` must
        // fire once.
        val proceed = CompletableDeferred<Unit>()
        install(
            Router().apply {
                register(HttpMethod.POST, "/upload") { call ->
                    proceed.await()
                    while (call.receiveChunk() != null) {
                        // Drain — releases happen inside receiveBytes-style
                        // loops; for this test we just discard.
                    }
                    call.respondText("ok")
                }
            },
        )

        feedPostChunked("/upload", "x".repeat(70_000))

        assertEquals(
            1,
            transport.pauseReadsCount,
            "pending bytes (70 KiB) crossed the high watermark — pauseReads must fire once",
        )
        assertEquals(
            0,
            transport.resumeReadsCount,
            "low watermark not yet reached — resumeReads must not fire while the queue is full",
        )

        proceed.complete(Unit)

        assertEquals(
            1,
            transport.resumeReadsCount,
            "consumer drained the queue below the low watermark — resumeReads must fire once",
        )
    }

    @Test
    fun `receiveChunk direct handoff bypasses the watermark`() {
        // The handler suspends on `receiveChunk()` BEFORE any body chunk
        // arrives, so each incoming chunk is handed straight to the
        // waiting continuation — `Http1Call.pending` stays empty and
        // the watermark accounting never sees the bytes. Even a 70 KiB
        // chunk that would trip the high watermark via the queued path
        // must not trigger `pauseReads` here.
        install(
            Router().apply {
                register(HttpMethod.POST, "/upload") { call ->
                    while (call.receiveChunk() != null) {
                        // Drain inline; each receive matches a waiter slot.
                    }
                    call.respondText("ok")
                }
            },
        )

        feedPostChunked("/upload", "x".repeat(70_000))

        assertEquals(
            0,
            transport.pauseReadsCount,
            "direct hand-off path must not trigger the watermark — pending stays empty",
        )
        assertEquals(
            0,
            transport.resumeReadsCount,
            "no pause was issued, so no resume should follow either",
        )
    }

    @Test
    fun `discardUnconsumedBody clears watermark state without issuing resume`() {
        // The handler responds immediately without reading the body, so
        // the body chunk arrives AFTER the call has finished — by which
        // point `inFlight === call` is false and the body chunk is
        // released by the `inFlight?.onBodyChunk(...) ?: release()`
        // branch. To exercise the path where chunks are queued AND the
        // handler then exits, we suspend the handler on a deferred,
        // assert the pause fires, then complete the deferred WITHOUT
        // draining: the finally block's `discardUnconsumedBody` must
        // release the queued buffer and reset the watermark flag
        // silently — never calling resumeReads on a closing transport.
        val proceed = CompletableDeferred<Unit>()
        install(
            Router().apply {
                register(HttpMethod.POST, "/upload") { call ->
                    proceed.await()
                    // Intentionally do NOT call receiveChunk — let the
                    // finally block clean the queue up.
                    call.respondText("ok")
                }
            },
        )

        feedPostChunked("/upload", "x".repeat(70_000))

        assertEquals(1, transport.pauseReadsCount, "pause must fire after high watermark crossed")

        proceed.complete(Unit)

        assertEquals(
            0,
            transport.resumeReadsCount,
            "discardUnconsumedBody must reset state silently, never resumeReads on close",
        )
    }

    @Test
    fun `a suspended born-parented handler is cancelled when the connection goes inactive`() {
        // The dispatch runs via startCoroutineUninterceptedOrReturn with a
        // completion carrying connectionScope's context, so the handler coroutine
        // is NOT registered as a Job child of connectionScope. This pins that
        // onInactive's connectionScope.cancel() still tears a *suspended* handler
        // down — through the suspension point's own cancellation registration on
        // the context's Job — which is the property the whole technique hinges on.
        val proceed = CompletableDeferred<Unit>()
        var cancelled = false
        var completedNormally = false
        install(
            Router().apply {
                register(HttpMethod.GET, "/hang") { _ ->
                    try {
                        proceed.await() // suspends here, parked on connectionScope's Job
                        completedNormally = true
                    } catch (e: CancellationException) {
                        cancelled = true
                        throw e
                    }
                }
            },
        )

        feedGet("/hang")
        assertFalse(completedNormally, "the handler must still be suspended, not done")
        assertFalse(cancelled, "not yet cancelled while the connection is live")

        // Peer disconnects mid-request: transport.onReadClosed → pipeline.notifyInactive().
        channel.pipeline.notifyInactive()

        assertTrue(
            cancelled,
            "connectionScope.cancel() from onInactive must cancel the suspended born-parented handler",
        )
        assertFalse(completedNormally, "a cancelled handler must not complete normally")
        assertFalse(proceed.isCompleted, "the deferred was never completed — the handler was cancelled, not resumed")
    }

    @Test
    fun `respondStream sink gate flips with writable state mid-stream`() {
        // Pins that the gate's per-write `isWritable` check is genuinely
        // per-call (not a one-shot or memoised decision): writable=true
        // for the first chunk takes the fast path, writable=false for
        // the second chunk gates. Flipping just before the second
        // `sink.write` proves the decision is observed at the call site,
        // not snapshot at sink construction.
        install(
            Router().apply {
                register(HttpMethod.GET, "/stream") { call ->
                    call.respondStream(
                        HttpResponseHead(
                            status = HttpStatus.OK,
                            headers = HttpHeaders.of(HttpHeaderName.TRANSFER_ENCODING to "chunked"),
                        ),
                    ) { sink ->
                        // First chunk: writable=true (fast path)
                        sink.write(bufOf("alpha"))
                        // Flip mid-stream
                        transport.writableOverride = false
                        // Second chunk: writable=false (gates)
                        sink.write(bufOf("beta"))
                    }
                }
            },
        )

        feedGet("/stream")

        assertEquals(
            1,
            transport.awaitPendingFlushCount,
            "gate must fire exactly once for the chunk written while !isWritable",
        )
        val text = responseText()
        assertTrue(text.contains("alpha"), "first chunk: $text")
        assertTrue(text.contains("beta"), "second chunk: $text")
    }
}
