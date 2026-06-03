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
 * 4. **Enforces** a dual-gate zip-bomb defence (Apache `mod_deflate`
 *    pattern + Nginx `client_max_body_size` integration):
 *    - **Absolute byte cap** ([decompressionLimit], default 1 MiB):
 *      `RequestDecompressionLimitException(AbsoluteSizeExceeded)` when
 *      cumulative decoded bytes per request exceed the cap.
 *    - **Decoded:input ratio cap** ([ratioLimit], default 100):
 *      `RequestDecompressionLimitException(RatioExceeded)` after the
 *      ratio cap has been exceeded a cumulative [ratioBurst] + 1 times
 *      across the request (default 3, i.e. abort on the 4th violation).
 *      The burst budget is set once per request and decremented on each
 *      violation — it is *not* reset when a chunk respects the ratio
 *      again. A handful of incidental high-ratio chunks (gzip header
 *      parse, dictionary hits, short high-entropy blocks) are tolerated,
 *      but a stream that keeps producing high-ratio chunks aborts even
 *      if they are interspersed with low-ratio ones. (Looser, true
 *      consecutive-only burst semantics — Apache's
 *      `DeflateInflateRatioBurst` — would require a reset-on-recovery
 *      counter; that variation is tracked as a future design judgement.)
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
 *   session update. Apache `mod_deflate` pattern. Default 100
 *   ([DEFAULT_RATIO_LIMIT]); Apache uses 200, keel is more
 *   conservative. Set to [Int.MAX_VALUE] to opt out.
 * @param ratioBurst cumulative ratio-violations tolerated before
 *   aborting; the request is rejected on the `ratioBurst + 1`-th
 *   violation. Default 3 ([DEFAULT_RATIO_BURST]). The budget is set
 *   once per request and decremented per violation — it is not reset
 *   when a chunk respects the ratio again, so a stream that repeatedly
 *   violates (even with low-ratio chunks in between) still aborts.
 *   Allows a handful of legitimate high-ratio chunks (gzip header /
 *   dictionary hits / short high-entropy blocks).
 * @param unknownEncodingPolicy behaviour when `Content-Encoding` is not
 *   registered. Default [UnknownEncodingPolicy.UnsupportedMediaType]
 *   (HTTP 415).
 * @param scratchCapacity per-channel scratch IoBuf size for decoded
 *   output. Higher = fewer emit cycles + larger emit size, lower =
 *   bounded peak. Default 8 KiB matches `CompressionHandler` /
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
        var result = ByteArray((src.size * INITIAL_DECODE_RATIO_GUESS).coerceAtLeast(MIN_AGGREGATED_BUF))
        var resultLen = 0
        try {
            bytesIn = src.size.toLong()
            // Drain update loop.
            while (true) {
                when (session.update(input, out)) {
                    CodecStatus.NEED_OUTPUT -> {
                        val (newResult, newLen) = drainTo(out, result, resultLen)
                        result = newResult
                        resultLen = newLen
                    }
                    CodecStatus.NEED_INPUT -> break
                    CodecStatus.FINISHED -> error("update should not return FINISHED")
                }
            }
            if (out.readableBytes > 0) {
                val (newResult, newLen) = drainTo(out, result, resultLen)
                result = newResult
                resultLen = newLen
            }
            // Drive finish.
            var finishing = true
            while (finishing) {
                when (session.finish(out)) {
                    CodecStatus.NEED_OUTPUT -> {
                        val (newResult, newLen) = drainTo(out, result, resultLen)
                        result = newResult
                        resultLen = newLen
                    }
                    CodecStatus.NEED_INPUT, CodecStatus.FINISHED -> {
                        if (out.readableBytes > 0) {
                            val (newResult, newLen) = drainTo(out, result, resultLen)
                            result = newResult
                            resultLen = newLen
                        }
                        finishing = false
                    }
                }
            }
        } finally {
            session.close()
            input.release()
        }
        return if (resultLen == result.size) result else result.copyOf(resultLen)
    }

    /**
     * Resize [dst] (if necessary) to fit [out]'s readable bytes plus
     * the existing [dstOffset], copy them in, advance [bytesOut], and
     * enforce limits.
     *
     * Returns `(possiblyResizedDst, newDstOffset)`. Pair allocation per
     * drain is cheap relative to the decompression itself; the
     * aggregated path runs a bounded number of times per request.
     */
    private fun drainTo(out: IoBuf, dst: ByteArray, dstOffset: Int): Pair<ByteArray, Int> {
        val n = out.readableBytes
        if (n == 0) return dst to dstOffset
        bytesOut += n
        checkLimits()
        val needed = dstOffset + n
        val resized = if (needed > dst.size) {
            dst.copyOf(needed.coerceAtLeast(dst.size * 2))
        } else {
            dst
        }
        out.readByteArray(resized, dstOffset, n)
        return resized to needed
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
        ctx.propagateRead(HttpBody(emit))
    }

    // ---- Common ----

    private fun ensureScratch() {
        if (scratch == null) {
            scratch = allocator.allocate(scratchCapacity)
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
         * Default decoded:input ratio cap — 100, more conservative than
         * Apache `mod_deflate`'s 200. Typical gzip ratios for text /
         * JSON are ≤ 50:1, so 100:1 leaves comfortable headroom while
         * rejecting clear zip-bomb signatures.
         */
        public const val DEFAULT_RATIO_LIMIT: Int = 100

        /**
         * Default ratio-violation burst tolerance — 3 (i.e. abort on the
         * 4th violation). Cumulative across the request, not "in a row";
         * see the [HttpRequestDecompressionHandler] class KDoc for the
         * exact semantics and the rationale for not matching Apache's
         * consecutive-only `DeflateInflateRatioBurst`.
         */
        public const val DEFAULT_RATIO_BURST: Int = 3

        /** `Content-Encoding: identity` (RFC 9110 §8.4.1) — no transformation. */
        private const val ENCODING_IDENTITY: String = "identity"

        /** Initial guess for aggregated-decode result buffer: 4× input size (~typical gzip ratio for text). */
        private const val INITIAL_DECODE_RATIO_GUESS: Int = 4

        /** Floor for the aggregated-decode result buffer to avoid pathological tiny allocations. */
        private const val MIN_AGGREGATED_BUF: Int = 256
    }
}
