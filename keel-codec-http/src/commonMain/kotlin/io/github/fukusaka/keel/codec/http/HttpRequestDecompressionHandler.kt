package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.compression.CodecStatus
import io.github.fukusaka.keel.compression.CompressionRegistry
import io.github.fukusaka.keel.compression.Decoder
import io.github.fukusaka.keel.compression.DecoderOptions
import io.github.fukusaka.keel.compression.DecoderSession
import io.github.fukusaka.keel.pipeline.DuplexHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext

/**
 * Server-side HTTP request body decompression handler (`Content-Encoding`).
 *
 * Place this **after** [HttpRequestDecoder] (closer to the application
 * handler) on the inbound chain. The handler:
 *
 * 1. **Reads** [HttpRequestHead] / [HttpRequest] (aggregated form),
 *    inspects `Content-Encoding`, and looks up a [Decoder] in
 *    [registry].
 * 2. **Decodes** subsequent [HttpBody] / [HttpBodyEnd] chunks through
 *    the streaming SPI ([DecoderSession.update]), emitting decoded
 *    [HttpBody] messages downstream. The aggregated [HttpRequest] path
 *    decodes the whole body in one pass.
 * 3. **Strips** `Content-Encoding` and `Content-Length` from the
 *    request head — the downstream handler sees the request as if the
 *    client had sent it uncompressed (decoded length is unknown until
 *    [DecoderSession.finish] reports `FINISHED`).
 * 4. **Enforces** layered zip-bomb defence — five independent gates,
 *    each cheap and each closing a different escape:
 *
 *    - **L1: `Content-Length` pre-reject** at handler entry. If the
 *      advertised compressed size exceeds [decompressionLimit], throw
 *      `CompressedSizeExceeded` before any decoder is instantiated.
 *      Closes: honest clients sending bodies that can only ever exceed
 *      the decoded cap — rejected with zero inflate cost.
 *    - **L2: absolute decoded byte cap** ([decompressionLimit], default
 *      1 MiB): `AbsoluteSizeExceeded` when cumulative decoded bytes per
 *      request exceed the cap. Closes: bombs that are valid compressed
 *      data and slip past L1 (chunked transfer-encoding has no
 *      `Content-Length`, or an attacker omits the header).
 *    - **L3: single-shot ratio trip** ([ratioLimit], default 100, with
 *      [ratioBurst] default 0): `RatioExceeded` on the first chunk
 *      whose cumulative decoded:input ratio exceeds the cap. Closes:
 *      the "front-load benign / back-load bomb" shape — a single
 *      high-ratio chunk aborts immediately, no time for the decoder to
 *      do meaningful work past the trip point.
 *    - **L4: per-update output ceiling**, mechanical via
 *      [scratchCapacity] (default 8 KiB). Every `DecoderSession.update`
 *      writes into the shared scratch IoBuf, so one inflate call
 *      cannot produce more than [scratchCapacity] bytes before the
 *      handler drains and re-checks L2 + L3. Closes: backend-side "one
 *      inflate call producing N MiB into the caller's buffer" — the
 *      buffer is bounded by the handler, not by the decoder.
 *    - **L5: chained-encoding rejected**. `Content-Encoding: gzip, gzip,
 *      …` is treated as one unregistered token → 415 by default (see
 *      the *Multi-token* note below). Closes: multi-stage decompression
 *      bombs whose small outer body, after the first inflate, expands
 *      into a body the second inflate then makes massive.
 *
 *    The gates are independent: L1 fires on header parse only, L2 / L3
 *    fire on every drain, L4 is a property of the buffer the decoder is
 *    handed, L5 is a property of the codec-lookup step. A defender does
 *    not need to know which one will trip to be safe.
 * 5. **Applies** [unknownEncodingPolicy] when the encoding is not
 *    registered in [registry] — default
 *    [UnknownEncodingPolicy.UnsupportedMediaType] (HTTP 415).
 *
 * **Multi-token `Content-Encoding` (chained encodings)**: RFC 9110
 * §8.4.1 defines `Content-Encoding` as a list and permits stacking
 * (e.g. `Content-Encoding: deflate, gzip` = deflate-then-gzip, decoded
 * in reverse). keel **does not** implement chained decoding: the full
 * header value is looked up as a single codec name, so anything other
 * than one registered token falls through to [unknownEncodingPolicy]
 * (HTTP 415 by default). This is intentional — major HTTP servers and
 * frameworks (Netty, Go `net/http`, nginx, Apache `mod_deflate`,
 * Node.js `zlib`) likewise do not support chained inbound decoding,
 * clients do not send stacked encodings in practice, and stacked
 * `Content-Encoding` headers seen on the wire are typically the result
 * of a CDN / proxy double-compression bug rather than a legitimate
 * request. Rejecting them outright follows the modern "strict in what
 * you accept + safe defaults" convention.
 *
 * The handler does **not** itself emit HTTP status responses on
 * failure. Limit / unknown-encoding exceptions propagate through the
 * pipeline; callers (typically a Ktor plugin or application-level
 * exception mapper) convert them to HTTP 413 / 415 / 400. This
 * separation keeps the codec layer wire-only.
 *
 * **Per-channel scratch buffer**: a single output [IoBuf] of
 * [SCRATCH_CAPACITY] bytes is allocated once per channel attach and
 * reused across every emit (mirrors `CompressionHandler` lifecycle).
 *
 * **Pipelining**: this handler tracks one in-flight request body at a
 * time (HTTP/1.1 is strictly sequential per connection).
 *
 * **Threading**: pipeline handlers are single-threaded (run on the
 * EventLoop pinned to the channel), so internal state needs no
 * synchronization.
 *
 * @param registry decoder registry — caller pre-registers codecs they
 *   accept (`registry.register(GzipCodec)` etc.)
 * @param allocator allocator for emitted decoded body chunks + scratch
 * @param decompressionLimit absolute decoded byte cap per request.
 *   Default 1 MiB ([DEFAULT_DECOMPRESSION_LIMIT], aligns with Nginx
 *   `client_max_body_size`). Set to [Long.MAX_VALUE] to opt out (e.g.
 *   for large uploads with separate chunk-level validation).
 * @param ratioLimit decoded:input ratio cap evaluated after each
 *   session update. Default 100 ([DEFAULT_RATIO_LIMIT]). Set to
 *   [Int.MAX_VALUE] to opt out.
 * @param ratioBurst ratio-violation tolerance. Default 0
 *   ([DEFAULT_RATIO_BURST], single-shot trip): the first chunk that
 *   exceeds [ratioLimit] aborts the request — the safest baseline and
 *   the convention used by the majority of HTTP server implementations.
 *   A positive value tolerates `ratioBurst + 1` cumulative violations
 *   before aborting; the budget is set once per request and decremented
 *   per violation (not reset when a chunk respects the ratio again).
 *   Increase only if you knowingly need to accept dictionary-heavy /
 *   header-only-first-chunk streams whose initial chunks exceed
 *   [ratioLimit].
 * @param unknownEncodingPolicy behaviour when `Content-Encoding` is not
 *   registered. Default [UnknownEncodingPolicy.UnsupportedMediaType]
 *   (HTTP 415).
 * @param scratchCapacity per-channel scratch IoBuf size for decoded
 *   output. This also acts as the **per-update output ceiling** (L4 in
 *   the layered defense table above): the decoder cannot produce more
 *   bytes in a single `update` call than the scratch can hold, which
 *   bounds how much work a misbehaving / hostile codec can do between
 *   limit checks. Higher = fewer emit cycles + larger emit size, lower
 *   = tighter per-call cap. Default 8 KiB matches `CompressionHandler` /
 *   Netty `JdkZlibEncoder` emit chunk size.
 */
public class HttpRequestDecompressionHandler(
    private val registry: CompressionRegistry,
    private val allocator: BufferAllocator,
    private val decompressionLimit: Long = DEFAULT_DECOMPRESSION_LIMIT,
    private val ratioLimit: Int = DEFAULT_RATIO_LIMIT,
    private val ratioBurst: Int = DEFAULT_RATIO_BURST,
    private val unknownEncodingPolicy: UnknownEncodingPolicy = UnknownEncodingPolicy.UnsupportedMediaType,
    private val scratchCapacity: Int = SCRATCH_CAPACITY,
) : DuplexHandler {

    /** Active decoder session for the in-flight request body, or `null`. */
    private var activeSession: DecoderSession? = null

    /**
     * Original (decoder-sourced) headers of the in-flight *streaming* request,
     * stashed by [handleRequestHead] and released by [handleBodyEnd].
     *
     * These headers retain the recv buffer behind the head's zero-copy views
     * (see [HttpRequestHead]'s buffer-lifetime contract). This handler rewrites
     * the head with a buffer-free copy, so nothing downstream will release the
     * original — without releasing it here, one recv buffer leaks per request,
     * which on io_uring's fixed-slot provided-buffer ring wedges the EventLoop
     * (the decompression recv-buffer leak). The release is deferred to end-of-request because the streaming
     * body chunks that follow may alias the same recv buffer; freeing it at
     * [handleRequestHead] would recycle bytes the decoder has not yet decoded.
     * Null between requests.
     */
    private var pendingRequestHeaders: HttpHeaders? = null

    /** Per-channel reusable output scratch — allocated lazily on first decoded request. */
    private var scratch: IoBuf? = null

    // Per-request counters for limit enforcement; reset on each request head.
    private var bytesIn: Long = 0
    private var bytesOut: Long = 0
    private var ratioBurstRemaining: Int = 0

    // ---- Inbound: decode request body ----

    override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
        when (msg) {
            is HttpRequest -> handleAggregatedRequest(ctx, msg)
            is HttpRequestHead -> handleRequestHead(ctx, msg)
            is HttpBodyEnd -> handleBodyEnd(ctx, msg)
            is HttpBody -> handleBody(ctx, msg) // must come AFTER HttpBodyEnd (subclass).
            else -> ctx.propagateRead(msg)
        }
    }

    override fun onWrite(ctx: PipelineHandlerContext, msg: Any) {
        ctx.propagateWrite(msg)
    }

    override fun handlerRemoved(ctx: PipelineHandlerContext) {
        scratch?.release()
        scratch = null
        activeSession?.close()
        activeSession = null
        // Release a head whose request never reached HttpBodyEnd (connection
        // closed mid-request) so its retained recv buffer is not leaked.
        pendingRequestHeaders?.release()
        pendingRequestHeaders = null
    }

    // ---- Aggregated ----

    private fun handleAggregatedRequest(ctx: PipelineHandlerContext, request: HttpRequest) {
        val encoding = request.headers.getString(HttpHeaderName.CONTENT_ENCODING)?.lowercase()
        if (encoding == null || encoding == ENCODING_IDENTITY) {
            ctx.propagateRead(request)
            return
        }
        // L1: reject on advertised compressed size before any decoder runs.
        // `decodeAggregated` would also enforce L2 / L3 on cumulative output,
        // but doing that costs an inflate pass; this short-circuit doesn't.
        rejectIfAdvertisedTooLarge(request.headers)
        val decoder = registry.findDecoder(encoding)
        if (decoder == null) {
            applyUnknownEncodingPolicy(ctx, request, encoding)
            return
        }
        val body = request.body
        if (body == null || body.isEmpty()) {
            ctx.propagateRead(request.copy(headers = rewriteAndReleaseHeaders(request.headers), body = body))
            return
        }
        val decoded = decodeAggregated(decoder, body)
        ctx.propagateRead(
            request.copy(
                headers = rewriteAndReleaseHeaders(request.headers),
                body = decoded,
            ),
        )
    }

    private fun decodeAggregated(decoder: Decoder, src: ByteArray): ByteArray {
        val session = decoder.newSession(allocator, DecoderOptions())
        // Feed the decoder a zero-copy view of `src` when the allocator
        // supports it (NIO heap-ByteBuffer / Native pinned-pointer); the
        // codec consumes the view synchronously inside this function — the
        // caller's `src` is never read after `decodeAggregated` returns —
        // so the view is safe even though `src` is caller-owned. Allocators
        // without zero-copy wrap (DefaultAllocator / Netty) return null and
        // we fall back to the owned-copy path. Symmetric to the outbound
        // `CompressionHandler` wrapBytes view (#670).
        val input = allocator.wrapBytes(src, 0, src.size)
            ?: allocator.allocate(src.size).apply { writeByteArray(src, 0, src.size) }
        ensureScratch()
        val out = scratch!!
        bytesIn = 0
        bytesOut = 0
        ratioBurstRemaining = ratioBurst
        // Mutable holder so `drainTo` updates `buf` / `len` in place
        // instead of returning a `Pair<ByteArray, Int>` per drain. A
        // long compressed body produces many NEED_OUTPUT rounds; the
        // per-drain Pair allocation showed up as residual GC pressure
        // in the codec-http alloc backlog.
        val sink = AggregatedDecodeSink(
            initialCapacity = (src.size * INITIAL_DECODE_RATIO_GUESS).coerceAtLeast(MIN_AGGREGATED_BUF),
        )
        try {
            bytesIn = src.size.toLong()
            // Drain update loop.
            while (true) {
                when (session.update(input, out)) {
                    CodecStatus.NEED_OUTPUT -> drainTo(out, sink)
                    CodecStatus.NEED_INPUT -> break
                    CodecStatus.FINISHED -> error("update should not return FINISHED")
                }
            }
            if (out.readableBytes > 0) drainTo(out, sink)
            // Drive finish.
            var finishing = true
            while (finishing) {
                when (session.finish(out)) {
                    CodecStatus.NEED_OUTPUT -> drainTo(out, sink)
                    CodecStatus.NEED_INPUT, CodecStatus.FINISHED -> {
                        if (out.readableBytes > 0) drainTo(out, sink)
                        finishing = false
                    }
                }
            }
        } finally {
            session.close()
            input.release()
        }
        return if (sink.len == sink.buf.size) sink.buf else sink.buf.copyOf(sink.len)
    }

    /**
     * Per-call mutable buffer state for [decodeAggregated]'s drain loop.
     * Replaces a `var result: ByteArray; var resultLen: Int` plus a
     * `Pair<ByteArray, Int>` return on every drain — the holder is
     * allocated once per aggregated request and mutated in place across
     * the (potentially many) update / finish rounds. The handler reuses
     * its per-channel scratch [IoBuf]; this holder is per-request only
     * because the result buffer is the request's decoded body and is
     * handed off to the next handler at the end of the call.
     */
    private class AggregatedDecodeSink(initialCapacity: Int) {
        var buf: ByteArray = ByteArray(initialCapacity)
        var len: Int = 0
    }

    /**
     * Resize [sink].buf (if necessary) to fit [out]'s readable bytes
     * plus the existing [sink].len, copy them in, advance [bytesOut],
     * and enforce limits. Mutates [sink] in place — see
     * [AggregatedDecodeSink] for why this is not a Pair-returning function.
     *
     * **Critical**: clears [out] after the drain. `readByteArray` only
     * advances `readerIndex`; without resetting `writerIndex` via
     * [IoBuf.clear], the scratch buffer accumulates writes across drains
     * until `writableBytes == 0`. Once that happens the next
     * `session.update` cannot make progress (its internal drain loop
     * exits on `writableBytes == 0`), returns NEED_OUTPUT, this drain
     * sees `readableBytes == 0`, and the `while (true)` loop in
     * [decodeAggregated] spins. Triggered by any decoded body that
     * exceeds [SCRATCH_CAPACITY] (8 KiB by default) in one update round
     * — i.e. a routine compressed POST > 8 KiB. Regression-pinned by
     * `aggregated decode drains output beyond a single scratch fill
     * without spinning`.
     */
    private fun drainTo(out: IoBuf, sink: AggregatedDecodeSink) {
        val n = out.readableBytes
        if (n == 0) return
        bytesOut += n
        checkLimits()
        val needed = sink.len + n
        if (needed > sink.buf.size) {
            sink.buf = sink.buf.copyOf(needed.coerceAtLeast(sink.buf.size * 2))
        }
        out.readByteArray(sink.buf, sink.len, n)
        out.clear()
        sink.len = needed
    }

    // ---- Streaming ----

    private fun handleRequestHead(ctx: PipelineHandlerContext, head: HttpRequestHead) {
        // Discard any state left over from a previous request that aborted
        // mid-body. Without this, the prior session would leak and — more
        // importantly — the scratch buffer could still hold un-emitted
        // decoded bytes from the aborted request, which the next
        // `emitDecodedChunk` would silently prepend to this new request's
        // first decoded chunk (symmetric to the CompressionHandler
        // cross-response bleed fix).
        discardPendingRequestState()
        val encoding = head.headers.getString(HttpHeaderName.CONTENT_ENCODING)?.lowercase()
        if (encoding == null || encoding == ENCODING_IDENTITY) {
            ctx.propagateRead(head)
            return
        }
        // L1: reject on advertised compressed size before opening a session
        // (same rationale as the aggregated path; symmetry matters because
        // chunked transfer-encoding requests with no Content-Length will
        // still fall through to L2 / L3 during streaming).
        rejectIfAdvertisedTooLarge(head.headers)
        val decoder = registry.findDecoder(encoding)
        if (decoder == null) {
            applyUnknownEncodingPolicy(ctx, head, encoding)
            return
        }
        // Open a fresh session for this request.
        activeSession = decoder.newSession(allocator, DecoderOptions())
        bytesIn = 0
        bytesOut = 0
        ratioBurstRemaining = ratioBurst
        ensureScratch()
        // Stash the original headers (which retain the recv buffer) and release
        // them at HttpBodyEnd — see [pendingRequestHeaders].
        val rewritten = stripDecodedHeaders(head.headers)
        pendingRequestHeaders = head.headers
        ctx.propagateRead(head.copy(headers = rewritten))
    }

    /**
     * Releases the active decoder session, clears the scratch (so leftover
     * decoded bytes from an aborted body never bleed into the next request),
     * and releases the stashed request headers. Called at the start of every
     * new streaming request and in the catch path of [handleBody] /
     * [handleBodyEnd] so a mid-stream failure cannot leave half-decoded
     * state behind for the next request to inherit.
     */
    private fun discardPendingRequestState() {
        activeSession?.close()
        activeSession = null
        scratch?.clear()
        pendingRequestHeaders?.release()
        pendingRequestHeaders = null
    }

    /**
     * Builds the decoded-header view ([stripDecodedHeaders]) and then releases
     * the original [src] immediately.
     *
     * Used by the **aggregated** path only, where the body is a self-contained
     * `ByteArray` that no longer references the recv buffer — so the buffer the
     * headers retain can be freed as soon as the values are copied out. The
     * streaming path must instead defer the release to end-of-request (see
     * [pendingRequestHeaders]) because the body chunks may still alias the
     * buffer.
     *
     * Without this release the recv buffer leaks one per decoded request (see
     * [HttpRequestHead]'s buffer-lifetime contract). Release is safe because
     * [stripDecodedHeaders] copies every retained value into a freshly
     * allocated `String` ([HttpHeaders.valueAt] returns a copy), so the
     * rewritten headers no longer alias [src]'s buffer.
     */
    private fun rewriteAndReleaseHeaders(src: HttpHeaders): HttpHeaders {
        val rewritten = stripDecodedHeaders(src)
        src.release()
        return rewritten
    }

    private fun handleBody(ctx: PipelineHandlerContext, body: HttpBody) {
        val session = activeSession
        if (session == null) {
            ctx.propagateRead(body)
            return
        }
        val src = body.content
        val out = scratch!!
        try {
            bytesIn += src.readableBytes
            while (true) {
                when (session.update(src, out)) {
                    CodecStatus.NEED_OUTPUT -> emitDecodedChunk(ctx, out)
                    CodecStatus.NEED_INPUT -> break
                    CodecStatus.FINISHED -> error("update should not return FINISHED")
                }
            }
            if (out.readableBytes > 0) emitDecodedChunk(ctx, out)
        } catch (t: Throwable) {
            // Decoder / limit / downstream failure mid-body: drop the session
            // and any partially-decoded scratch state so the next request
            // does not inherit a broken decoder or leftover bytes. The src
            // release happens in finally so we run it on the abort path too.
            discardPendingRequestState()
            throw t
        } finally {
            src.release()
        }
    }

    private fun handleBodyEnd(ctx: PipelineHandlerContext, end: HttpBodyEnd) {
        val session = activeSession
        if (session == null) {
            ctx.propagateRead(end)
            return
        }
        val out = scratch!!

        try {
            drainTerminalBody(ctx, session, end, out)
            driveFinish(ctx, session, out)
            session.close()
            activeSession = null
            // The body is fully consumed — the recv buffer the head's headers
            // retained is no longer aliased by any pending body chunk, so
            // release it now (see [pendingRequestHeaders]).
            pendingRequestHeaders?.release()
            pendingRequestHeaders = null
        } catch (t: Throwable) {
            // Finish / limit / downstream failure: same hygiene as handleBody.
            discardPendingRequestState()
            throw t
        }
        ctx.propagateRead(HttpBodyEnd.EMPTY)
    }

    /**
     * Drain whatever input the terminal [HttpBodyEnd] carries through the
     * decoder. Split out of [handleBodyEnd] so that method does not exceed
     * detekt's nested-block-depth limit and so the inner `try/finally` that
     * pairs the input release with its consumption stays local.
     */
    private fun drainTerminalBody(
        ctx: PipelineHandlerContext,
        session: DecoderSession,
        end: HttpBodyEnd,
        out: IoBuf,
    ) {
        if (end.content.readableBytes == 0) {
            end.content.release()
            return
        }
        try {
            bytesIn += end.content.readableBytes
            while (true) {
                when (session.update(end.content, out)) {
                    CodecStatus.NEED_OUTPUT -> emitDecodedChunk(ctx, out)
                    CodecStatus.NEED_INPUT -> break
                    CodecStatus.FINISHED -> error("update should not return FINISHED")
                }
            }
            if (out.readableBytes > 0) emitDecodedChunk(ctx, out)
        } finally {
            end.content.release()
        }
    }

    /**
     * Drive `session.finish()` to completion, emitting any final decoded
     * bytes. Split out of [handleBodyEnd] for the same reason as
     * [drainTerminalBody].
     */
    private fun driveFinish(ctx: PipelineHandlerContext, session: DecoderSession, out: IoBuf) {
        while (true) {
            when (session.finish(out)) {
                CodecStatus.NEED_OUTPUT -> emitDecodedChunk(ctx, out)
                CodecStatus.NEED_INPUT, CodecStatus.FINISHED -> {
                    if (out.readableBytes > 0) emitDecodedChunk(ctx, out)
                    return
                }
            }
        }
    }

    private fun emitDecodedChunk(ctx: PipelineHandlerContext, scratchBuf: IoBuf) {
        val n = scratchBuf.readableBytes
        if (n == 0) return
        // Drain the scratch into `emit` *first*, then update the counter and
        // run checkLimits. If `checkLimits` then throws (zip-bomb defence,
        // RatioExceeded / AbsoluteSizeExceeded), the scratch is already
        // cleared — otherwise the un-drained bytes would survive into the
        // next request and bleed into its first decoded chunk.
        val emit = allocator.allocate(n)
        try {
            scratchBuf.copyTo(emit, n)
        } catch (t: Throwable) {
            emit.release()
            throw t
        }
        scratchBuf.clear()
        bytesOut += n
        try {
            checkLimits()
        } catch (t: Throwable) {
            emit.release()
            throw t
        }
        // Pipeline ownership semantics: `propagateRead` accepts the message
        // only when it returns normally. A synchronous throw from a
        // downstream handler leaves `emit` orphaned — the outer
        // `handleBody` catch (line ~427) only releases scratch / session /
        // pendingRequestHeaders and does not know about this in-flight
        // buffer. Symmetric to `CompressionHandler.emitWorking`'s
        // outbound-side fix.
        try {
            ctx.propagateRead(HttpBody(emit))
        } catch (t: Throwable) {
            emit.release()
            throw t
        }
    }

    // ---- Common ----

    private fun ensureScratch() {
        if (scratch == null) {
            scratch = allocator.allocate(scratchCapacity)
        }
    }

    /**
     * L1: short-circuit at handler entry when the request advertises a
     * compressed body larger than [decompressionLimit].
     *
     * Compressed bytes are a lower bound on what the decoded body would
     * have to weigh — even at a 1:1 ratio the decoded result still
     * exceeds the cap, so there is no way for the request to succeed
     * past L2 / L3. Rejecting here saves the decoder allocation, the
     * pending-header retention, and (depending on the engine) the recv
     * buffer churn of streaming the compressed body off the wire.
     *
     * Missing or malformed `Content-Length` is a no-op — chunked
     * transfer-encoding has no advertised size, and L2 / L3 still catch
     * the bomb during streaming. Negative values are likewise ignored;
     * the decoder is the wrong layer to enforce header-syntax rules.
     */
    private fun rejectIfAdvertisedTooLarge(headers: HttpHeaders) {
        if (decompressionLimit == Long.MAX_VALUE) return
        val advertised = headers.contentLength ?: return
        if (advertised < 0) return
        if (advertised > decompressionLimit) {
            throw RequestDecompressionLimitException(
                RequestDecompressionLimitException.Reason.CompressedSizeExceeded,
                bytesDecoded = 0,
                bytesIn = advertised,
            )
        }
    }

    /**
     * Enforce absolute byte cap + decoded:input ratio cap with burst
     * tolerance. Throws [RequestDecompressionLimitException] when a
     * gate fires.
     *
     * Absolute cap is checked first so a single huge chunk that breaks
     * both gates reports `AbsoluteSizeExceeded` deterministically.
     */
    private fun checkLimits() {
        if (decompressionLimit != Long.MAX_VALUE && bytesOut > decompressionLimit) {
            throw RequestDecompressionLimitException(
                RequestDecompressionLimitException.Reason.AbsoluteSizeExceeded,
                bytesDecoded = bytesOut,
                bytesIn = bytesIn,
            )
        }
        if (ratioLimit != Int.MAX_VALUE && bytesIn > 0) {
            // Integer comparison: bytesOut > ratioLimit * bytesIn (avoids float).
            if (bytesOut > ratioLimit.toLong() * bytesIn) {
                ratioBurstRemaining--
                if (ratioBurstRemaining < 0) {
                    throw RequestDecompressionLimitException(
                        RequestDecompressionLimitException.Reason.RatioExceeded,
                        bytesDecoded = bytesOut,
                        bytesIn = bytesIn,
                    )
                }
            }
        }
    }

    private fun applyUnknownEncodingPolicy(ctx: PipelineHandlerContext, msg: Any, encoding: String) {
        when (unknownEncodingPolicy) {
            UnknownEncodingPolicy.Passthrough -> ctx.propagateRead(msg)
            UnknownEncodingPolicy.UnsupportedMediaType,
            UnknownEncodingPolicy.BadRequest,
            -> throw UnsupportedContentEncodingException(encoding, unknownEncodingPolicy)
        }
    }

    /**
     * Build a fresh [HttpHeaders] from [src] with `Content-Encoding` and
     * `Content-Length` filtered out. Iterates [src] in insertion order so
     * the downstream handler observes original-case names + duplicate
     * preservation for any other headers (`Accept-*` etc.).
     *
     * **Critical**: do NOT use `src.remove(...)` — `HttpHeaders.remove`
     * mutates in place and returns `this`, which would corrupt the
     * upstream [HttpRequestDecoder]'s local `head.headers` reference.
     * The decoder reads `head.headers.contentLength` AFTER
     * `propagateRead(head)` returns to determine body framing
     * (`READ_FIXED_BODY` vs `READ_CHUNK_SIZE`); a stripped
     * Content-Length there would short-circuit the body emit and the
     * compressed bytes would never reach this handler.
     */
    private fun stripDecodedHeaders(src: HttpHeaders): HttpHeaders {
        return HttpHeaders.build {
            for (i in 0 until src.size) {
                val name = src.nameAt(i)
                if (name.equals(HttpHeaderName.CONTENT_ENCODING, ignoreCase = true)) continue
                if (name.equals(HttpHeaderName.CONTENT_LENGTH, ignoreCase = true)) continue
                add(name, src.valueAt(i))
            }
        }
    }

    public companion object {
        /** Default per-channel scratch IoBuf size — 8 KiB, matches `CompressionHandler`. */
        public const val SCRATCH_CAPACITY: Int = 8 * 1024

        /**
         * Default absolute decoded byte cap — 1 MiB, matches Nginx
         * `client_max_body_size`. Most JSON / form / multipart uploads
         * fit; large uploads (file upload, log shipping) must opt out
         * by passing `Long.MAX_VALUE` and validate elsewhere.
         */
        public const val DEFAULT_DECOMPRESSION_LIMIT: Long = 1L * 1024 * 1024

        /**
         * Default decoded:input ratio cap — 100. Typical gzip ratios
         * for text / JSON are ≤ 50:1, so 100:1 leaves comfortable
         * headroom while rejecting clear zip-bomb signatures.
         */
        public const val DEFAULT_RATIO_LIMIT: Int = 100

        /**
         * Default ratio-violation burst tolerance — **0** (single-shot
         * trip). The first chunk whose cumulative decoded:input ratio
         * exceeds [DEFAULT_RATIO_LIMIT] aborts the request: the safest
         * baseline and the convention followed by the majority of HTTP
         * server implementations (single ratio cap, no burst window).
         *
         * **Behaviour change**: earlier keel releases defaulted to 3
         * (abort on the 4th cumulative violation, an Apache-flavoured
         * burst-tolerance variation). The relaxation has been retired
         * because (a) zip-bomb payloads typically violate on every
         * chunk so the burst budget added no real headroom against the
         * threat, and (b) the legitimate streams the budget was meant
         * to protect (dictionary-heavy / gzip-header-in-first-chunk)
         * fit comfortably under a 100:1 ratio in practice. Set
         * [ratioBurst] to a positive value to re-enable burst tolerance
         * when you actively need it.
         */
        public const val DEFAULT_RATIO_BURST: Int = 0

        /** `Content-Encoding: identity` (RFC 9110 §8.4.1) — no transformation. */
        private const val ENCODING_IDENTITY: String = "identity"

        /** Initial guess for aggregated-decode result buffer: 4× input size (~typical gzip ratio for text). */
        private const val INITIAL_DECODE_RATIO_GUESS: Int = 4

        /** Floor for the aggregated-decode result buffer to avoid pathological tiny allocations. */
        private const val MIN_AGGREGATED_BUF: Int = 256
    }
}
