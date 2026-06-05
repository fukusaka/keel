package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.compression.CodecStatus
import io.github.fukusaka.keel.compression.CompressionRegistry
import io.github.fukusaka.keel.compression.Encoder
import io.github.fukusaka.keel.compression.EncoderOptions
import io.github.fukusaka.keel.compression.EncoderSession
import io.github.fukusaka.keel.pipeline.DuplexHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext

/**
 * Server-side HTTP response compression handler (`Content-Encoding`).
 *
 * Place this **before** [HttpResponseEncoder] (closer to the application
 * handler) on the outbound chain. The handler:
 *
 * 1. **Reads** request `Accept-Encoding` from each incoming
 *    [HttpRequestHead] (inbound side) and saves it in per-channel state.
 * 2. **Negotiates** an encoder against [registry] when the next
 *    [HttpResponseHead] flows outbound. If accepted, mutates the
 *    response headers (adds `Content-Encoding`, drops `Content-Length`,
 *    appends `Vary: Accept-Encoding`) and starts an [EncoderSession].
 * 3. **Encodes** subsequent [HttpBody] / [HttpBodyEnd] chunks through
 *    the streaming SPI (`update(input, output) → CodecStatus`),
 *    emitting one or more [HttpBody] messages per input chunk
 *    depending on output buffer fill.
 * 4. **Skips compression** when the request did not accept any
 *    registered encoding, the response status is no-body (1xx / 204 /
 *    304), or the optional [condition] rejects.
 * 5. **Converts an aggregated [HttpResponse]** (full-body) into the same
 *    chunked streaming sequence: it emits the head, then drives the
 *    encoder over the body emitting [HttpBody] chunks as they are
 *    produced, then [HttpBodyEnd]. The compressed output is never
 *    buffered into one contiguous `ByteArray`, and the response becomes
 *    `Transfer-Encoding: chunked` (the compressed size is no longer
 *    materialised) — the same trade-off nginx / Netty / Ktor make for
 *    on-the-fly compression.
 *
 * **`identity;q=0` handling.** RFC 9110 §12.5.3 lets a client explicitly
 * forbid identity (uncompressed) responses, in which case a server with no
 * acceptable encoding should return `406 Not Acceptable`. keel's handler
 * resolves `identity;q=0` plus no registered encoder accepted to the same
 * outcome as "no encoder picked" — the response is forwarded uncompressed
 * (identity) rather than rewritten as 406. Applications that need strict
 * RFC compliance should layer their own 406 check upstream of this handler.
 *
 * **Per-channel working buffer**: the encoder drains into a pooled
 * [IoBuf] of [SCRATCH_CAPACITY] bytes. On every emit that buffer is
 * handed *straight downstream* as the `HttpBody` payload — ownership
 * transfers to the transport, which recycles it into the pool after the
 * `writev` — and a fresh pooled buffer is acquired for the next codec
 * step. There is no `copyTo` into an exact-size buffer: every emitted
 * chunk, full or partial, is a pooled buffer, so a streamed response
 * mints no fresh `DirectByteBuffer` / `Cleaner` per chunk (the previous
 * exact-size `allocate(n)` missed the pool on every non-full chunk).
 * Memory peak stays at `SCRATCH_CAPACITY` per in-flight chunk.
 *
 * **Pipelining**: this handler tracks one in-flight response at a time
 * (request → response is strictly sequential on HTTP/1.1). The
 * inbound `Accept-Encoding` is captured into a queue so that a
 * pipelined client (sequential request/response on one connection)
 * still gets the right value matched to each response.
 *
 * **Mid-stream failure recovery**: if a response aborts mid-stream (a
 * downstream write throws, or an emit allocation fails), the handler
 * closes the in-flight [EncoderSession] and releases the working buffer
 * (only if still held — an already-emitted buffer is the transport's)
 * before re-throwing, and also discards any such leftover state at the
 * start of every new response. This keeps a keep-alive connection from
 * leaking the aborted response's session or bleeding its partial output
 * into the head of the next response.
 *
 * **Threading**: pipeline handlers are single-threaded (run on the
 * EventLoop pinned to the channel), so internal state needs no
 * synchronization.
 *
 * @param registry encoder registry — caller pre-registers the codecs
 *   they want to support (`registry.register(GzipCodec)` etc.)
 * @param allocator output allocator for the pooled working / emit buffers
 * @param condition optional predicate per response. Default = always
 *   compress when the client accepts. Common condition: skip
 *   pre-compressed MIME types (`image/`, `video/`, `application/zip`)
 *   or small responses (`Content-Length < N`)
 * @param defaultEncoderOptions options forwarded to every
 *   [Encoder.newSession] call. Keep [EncoderOptions.flushMode] as
 *   `Sync` (default) for HTTP streaming.
 * @param scratchCapacity per-chunk working IoBuf size. Higher = fewer
 *   emit cycles + larger emit size, lower = bounded peak. Default 8 KiB
 *   matches Netty `JdkZlibEncoder`'s emit chunk size and is a registered
 *   pool class, so the working buffers recycle; a non-pool-class size
 *   still works but allocates fresh per emit.
 * @param maxPendingResponses upper bound on the pipelined Accept-Encoding
 *   queue before the connection is failed (slowloris guard). Default 1024.
 * @throws IllegalArgumentException if [scratchCapacity] or
 *   [maxPendingResponses] is not positive.
 */
public class CompressionHandler(
    private val registry: CompressionRegistry,
    private val allocator: BufferAllocator,
    private val condition: CompressionCondition = CompressionCondition.Default,
    private val defaultEncoderOptions: EncoderOptions = EncoderOptions(),
    private val scratchCapacity: Int = SCRATCH_CAPACITY,
    private val maxPendingResponses: Int = DEFAULT_MAX_PENDING_RESPONSES,
) : DuplexHandler {

    init {
        require(scratchCapacity > 0) {
            "CompressionHandler.scratchCapacity must be > 0 (got $scratchCapacity)"
        }
        // A non-positive cap makes the `acceptQueue.size < maxPendingResponses`
        // gate fail on the very first request (`0 < 0` is false), bricking the
        // connection. Reject at construction rather than per-request.
        require(maxPendingResponses > 0) {
            "CompressionHandler.maxPendingResponses must be > 0 (got $maxPendingResponses)"
        }
    }

    /**
     * Pending Accept-Encoding values, FIFO per pipelined request.
     *
     * Bounded by [maxPendingResponses]: each inbound [HttpRequestHead]
     * enqueues one entry, dequeued when the matching response head goes
     * out. A client that pipelines request heads without ever reading
     * the responses would otherwise grow this queue without limit — a
     * slowloris-style resource-exhaustion vector. Reaching the cap means
     * that many requests are in flight with no response written, which
     * is abnormal for HTTP/1.1, so the handler fails the connection
     * rather than accumulate unbounded state.
     */
    private val acceptQueue: ArrayDeque<String?> = ArrayDeque()

    /** Active encoder session for the in-flight response, or `null`. */
    private var activeSession: EncoderSession? = null

    /**
     * The pooled buffer the encoder currently drains into. Allocated lazily
     * at [scratchCapacity] (a registered pool size when left at the 8 KiB
     * default). On every emit it is handed straight downstream as the
     * `HttpBody` payload — ownership transfers to the transport, which
     * recycles it into the pool after the `writev` — and a fresh pooled
     * buffer is acquired for the next codec step. When the encoder produces
     * no output for a body (the codec buffered the input internally) the
     * still-empty buffer is kept for reuse rather than re-allocated.
     *
     * `null` means "not currently held" — either never allocated, or just
     * handed off and not yet re-acquired. The handler owns it whenever it is
     * non-null; [discardPendingResponse] / [handlerRemoved] release it only
     * then (releasing after a hand-off would double-free a buffer the
     * transport already owns).
     *
     * This replaces the previous "persistent scratch + `copyTo` into a fresh
     * exact-size `IoBuf` per emit" shape. That exact-size allocation missed
     * the pool on every partial (non-full) chunk — `allocate(n)` only pools
     * when `n` matches a registered class — so each trailing chunk minted a
     * fresh `DirectByteBuffer` + `Cleaner` + `Deallocator`. Handing off the
     * pooled buffer directly recycles every chunk and drops the per-chunk
     * `memcpy`.
     */
    private var working: IoBuf? = null

    // ---- Inbound: capture Accept-Encoding ----

    override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
        if (msg is HttpRequestHead) {
            check(acceptQueue.size < maxPendingResponses) {
                "too many pipelined requests without responses " +
                    "(${acceptQueue.size} >= $maxPendingResponses); failing the connection"
            }
            acceptQueue.addLast(msg.headers.getCombined(HttpHeaderName.ACCEPT_ENCODING))
        }
        ctx.propagateRead(msg)
    }

    override fun handlerRemoved(ctx: PipelineHandlerContext) {
        working?.release()
        working = null
        activeSession?.close()
        activeSession = null
    }

    // ---- Outbound: negotiate + transform ----

    override fun onWrite(ctx: PipelineHandlerContext, msg: Any) {
        when (msg) {
            is HttpResponse -> handleAggregatedResponse(ctx, msg)
            is HttpResponseHead -> handleResponseHead(ctx, msg)
            is HttpBodyEnd -> handleBodyEnd(ctx, msg)
            is HttpBody -> handleBody(ctx, msg) // must come AFTER HttpBodyEnd (subclass).
            else -> ctx.propagateWrite(msg)
        }
    }

    /**
     * Discard any state left over from a previous response before starting a
     * new one. A response that aborted mid-stream — a downstream write threw,
     * or an emit allocation failed — can leave [activeSession] open and the
     * [working] buffer holding partially compressed bytes. Because one handler
     * instance serves every response on a keep-alive connection, starting the
     * next response without discarding them would leak the encoder session and
     * bleed the leftover bytes into the head of the new response.
     *
     * [EncoderSession.close] is idempotent, so calling this after a response
     * that ended cleanly (session already closed and nulled, working buffer
     * either reused-empty or already handed off) is a no-op.
     */
    private fun discardPendingResponse() {
        activeSession?.close()
        activeSession = null
        // Release the working buffer only if the handler still holds it. After
        // an emit it is null (the transport owns it), so this neither leaks a
        // held buffer nor double-frees a handed-off one.
        working?.release()
        working = null
    }

    private fun handleAggregatedResponse(ctx: PipelineHandlerContext, response: HttpResponse) {
        discardPendingResponse()
        val accept = if (acceptQueue.isNotEmpty()) acceptQueue.removeFirst() else null

        if (isCompressionExempt(response.status.code) || response.body == null || response.body.isEmpty()) {
            ctx.propagateWrite(response)
            return
        }
        // The headers-only static checks are sufficient when no custom
        // predicate is configured (the common case), so skip allocating
        // an `HttpResponseHead` purely for the condition lookup. When a
        // custom predicate is set we still need a real head — materialise
        // it lazily.
        if (!condition.shouldCompressStatic(response.headers)) {
            ctx.propagateWrite(response)
            return
        }
        if (condition.hasCustomPredicate) {
            val asHead = HttpResponseHead(response.status, response.version, response.headers)
            if (!condition.shouldCompress(asHead)) {
                ctx.propagateWrite(response)
                return
            }
        }
        val encoder = negotiateContentEncoding(registry, accept) ?: run {
            ctx.propagateWrite(response)
            return
        }

        // Stream-compress the aggregated body through the chunked path: emit the
        // head (Transfer-Encoding: chunked), then drive the encoder over the body
        // emitting HttpBody chunks *as they are produced*, then HttpBodyEnd. This
        // never buffers the whole compressed output (no contiguous ByteArray) —
        // memory is bounded to one working buffer — and matches how nginx / Netty
        // / Ktor compress dynamically. Content-Length is replaced by chunked
        // since the compressed size is no longer materialised (RFC 9112 §6.1
        // forbids both). The head + body + end reuse the streaming handlers.
        val mutatedHead = HttpResponseHead(
            response.status,
            response.version,
            rewriteHeaders(response.headers, encoder.name, fixedLength = null),
        )
        // From here on the handler owns an EncoderSession and (after
        // ensureWorking) a pooled working IoBuf. Any throw before handleBody /
        // handleBodyEnd reach their own discardPendingResponse() catch would
        // orphan the session and bleed leftover bytes into the next response,
        // so we own the cleanup ourselves until the streaming handlers do.
        activeSession = encoder.newSession(allocator, defaultEncoderOptions)
        try {
            ensureWorking()
            ctx.propagateWrite(mutatedHead)

            // Feed the body to the encoder as a zero-copy view where the
            // allocator supports it (NIO heap-ByteBuffer / Native pinned-
            // pointer); the codec consumes `src` synchronously within
            // handleBody / handleBodyEnd, so the app's array is never read
            // after this call returns. Allocators without zero-copy wrap
            // (DefaultAllocator / Netty) return null → owned copy.
            val body = response.body
            val src = allocator.wrapBytes(body, 0, body.size)
                ?: allocator.allocate(body.size).apply { writeByteArray(body, 0, body.size) }
            // Internal direct dispatch to the streaming handlers — safe
            // because `CompressionHandler` is `public final`, so a
            // subclass cannot override `handleBody` / `handleBodyEnd`
            // and divert the aggregated branch's body emission. If this
            // class is ever made `open`, this dispatch must be rerouted
            // through `ctx.onWrite(...)` so a subclass's override sees
            // both branches' chunks identically.
            handleBody(ctx, HttpBody(src))
            handleBodyEnd(ctx, HttpBodyEnd.EMPTY)
        } catch (t: Throwable) {
            discardPendingResponse()
            throw t
        }
    }

    private fun handleResponseHead(ctx: PipelineHandlerContext, head: HttpResponseHead) {
        discardPendingResponse()
        val accept = if (acceptQueue.isNotEmpty()) acceptQueue.removeFirst() else null

        if (isCompressionExempt(head.status.code) || !condition.shouldCompress(head)) {
            ctx.propagateWrite(head)
            return
        }
        val encoder = negotiateContentEncoding(registry, accept) ?: run {
            ctx.propagateWrite(head)
            return
        }

        val mutated = head.copy(headers = rewriteHeaders(head.headers, encoder.name, fixedLength = null))
        // Same ownership window as handleAggregatedResponse: once newSession
        // ran, an exception from ensureWorking() or the downstream
        // propagateWrite (a later handler in the pipeline rejecting the
        // head) would otherwise leak the session and the next response would
        // get a fresh one on top of it.
        activeSession = encoder.newSession(allocator, defaultEncoderOptions)
        try {
            ensureWorking()
            ctx.propagateWrite(mutated)
        } catch (t: Throwable) {
            discardPendingResponse()
            throw t
        }
    }

    private fun handleBody(ctx: PipelineHandlerContext, body: HttpBody) {
        val session = activeSession
        if (session == null) {
            ctx.propagateWrite(body)
            return
        }
        val src = body.content
        try {
            // Drive update until input fully consumed; emit chunks on NEED_OUTPUT.
            // Each emit hands off the working buffer and the next iteration
            // re-acquires a fresh pooled one.
            while (true) {
                when (session.update(src, acquireWorking())) {
                    CodecStatus.NEED_OUTPUT -> emitWorking(ctx)
                    CodecStatus.NEED_INPUT -> break
                    CodecStatus.FINISHED -> error("update should not return FINISHED")
                }
            }
            // Emit any pending output bytes from this update (no-op if the
            // codec buffered everything; the empty buffer is kept for reuse).
            emitWorking(ctx)
        } catch (e: Throwable) {
            // The response aborted mid-stream — close the session and release
            // the working buffer now so the next response on this connection
            // starts clean (no leaked session, no bled-over bytes), then re-throw.
            discardPendingResponse()
            throw e
        } finally {
            src.release()
        }
    }

    private fun handleBodyEnd(ctx: PipelineHandlerContext, end: HttpBodyEnd) {
        val session = activeSession
        if (session == null) {
            ctx.propagateWrite(end)
            return
        }
        try {
            // Drain any trailing input from the terminal HttpBody first.
            if (end.content.readableBytes > 0) {
                while (true) {
                    when (session.update(end.content, acquireWorking())) {
                        CodecStatus.NEED_OUTPUT -> emitWorking(ctx)
                        CodecStatus.NEED_INPUT -> break
                        CodecStatus.FINISHED -> error("update should not return FINISHED")
                    }
                }
                emitWorking(ctx)
            }

            // Drive finish to emit the format trailer.
            var finishing = true
            while (finishing) {
                when (session.finish(acquireWorking())) {
                    CodecStatus.NEED_OUTPUT -> emitWorking(ctx)
                    CodecStatus.NEED_INPUT, CodecStatus.FINISHED -> {
                        emitWorking(ctx)
                        finishing = false
                    }
                }
            }

            session.close()
            activeSession = null
        } catch (e: Throwable) {
            // The response aborted mid-finish — close the session and release
            // the working buffer now so the next response on this connection
            // starts clean, then re-throw.
            discardPendingResponse()
            throw e
        } finally {
            end.content.release()
        }
        ctx.propagateWrite(HttpBodyEnd.EMPTY)
    }

    /**
     * Returns the working buffer the encoder should drain into, allocating a
     * fresh pooled one (at [scratchCapacity]) when none is currently held.
     */
    private fun acquireWorking(): IoBuf =
        working ?: allocator.allocate(scratchCapacity).also { working = it }

    /** Pre-allocates the working buffer so an allocation failure surfaces
     * inside the response's ownership window (before any bytes are emitted). */
    private fun ensureWorking() {
        if (working == null) working = allocator.allocate(scratchCapacity)
    }

    /**
     * Hands the working buffer's readable bytes straight downstream as an
     * [HttpBody] and relinquishes ownership — the transport recycles the
     * pooled buffer into its pool after the `writev`. The next codec step
     * re-acquires a fresh pooled buffer via [acquireWorking].
     *
     * A no-op when the buffer holds nothing (the codec buffered the input
     * internally): the empty buffer is kept for reuse rather than emitted as
     * a zero-length chunk. Unlike the previous `copyTo`-into-exact-size shape,
     * no per-chunk `memcpy` runs and every emitted chunk — full or partial —
     * is a pooled buffer (the old exact-size `allocate(n)` missed the pool on
     * every non-full chunk).
     */
    private fun emitWorking(ctx: PipelineHandlerContext) {
        val buf = working ?: return
        if (buf.readableBytes == 0) return
        working = null
        // Pipeline ownership semantics: `propagateWrite` accepts the message
        // only when it returns normally. A synchronous throw from a
        // downstream handler leaves `buf` orphaned — `working` is already
        // null so `discardPendingResponse` cannot find it. Symmetric to
        // `HttpRequestDecompressionHandler.emitDecodedChunk`'s inbound-side
        // fix.
        try {
            ctx.propagateWrite(HttpBody(buf))
        } catch (t: Throwable) {
            buf.release()
            throw t
        }
    }

    /**
     * Build the post-compression header set:
     *
     * - drop the original `Content-Length` (compressed size differs)
     *   and `Content-Encoding` (we replace it),
     * - drop any pre-existing `Transfer-Encoding` (we'll re-set the
     *   transfer mode based on whether [fixedLength] is known),
     * - add `Content-Encoding: <encoding>`,
     * - add `Vary: Accept-Encoding` (cache correctness),
     * - add either `Content-Length: <fixedLength>` (aggregated path:
     *   we accumulated the full compressed body and know its size)
     *   OR `Transfer-Encoding: chunked` (streaming path: compressed
     *   size unknown until [EncoderSession.finish] returns FINISHED).
     *
     * RFC 9112 §6.1 forbids both `Content-Length` and `Transfer-Encoding:
     * chunked` on the same response, so the encoder throws if neither
     * (or both) are set — calling sites here MUST pass one. The
     * `HttpResponseEncoder` sees the rewritten header and serialises the
     * matching framing.
     */
    private fun rewriteHeaders(src: HttpHeaders, encoding: String, fixedLength: String?): HttpHeaders {
        return HttpHeaders().apply {
            for (i in 0 until src.size) {
                val name = src.nameAt(i)
                if (name.equals(HttpHeaderName.CONTENT_LENGTH, ignoreCase = true)) continue
                if (name.equals(HttpHeaderName.CONTENT_ENCODING, ignoreCase = true)) continue
                if (name.equals(HttpHeaderName.TRANSFER_ENCODING, ignoreCase = true)) continue
                // Skip every Vary entry; we rebuild it once below from the
                // combined view so a response carrying multiple Vary lines
                // (`Vary: User-Agent` + `Vary: Cookie`) doesn't lose any
                // existing value to the subsequent `this["Vary"] = …` set —
                // that `set` clears every Vary and re-adds a single line.
                if (name.equals("Vary", ignoreCase = true)) continue
                add(name, src.valueAt(i))
            }
            this[HttpHeaderName.CONTENT_ENCODING] = encoding
            if (fixedLength != null) {
                this[HttpHeaderName.CONTENT_LENGTH] = fixedLength
            } else {
                this[HttpHeaderName.TRANSFER_ENCODING] = "chunked"
            }
            // Combine every Vary value the caller supplied so multi-line
            // `Vary` (RFC 9110 §12.5.5) survives the rewrite. `getCombined`
            // joins repeated header lines with `, ` per HTTP field rules.
            val existingVary = src.getCombined("Vary")
            this["Vary"] = if (existingVary.isNullOrBlank()) {
                "Accept-Encoding"
            } else if (existingVary.contains("accept-encoding", ignoreCase = true)) {
                existingVary
            } else {
                "$existingVary, Accept-Encoding"
            }
        }
    }

    public companion object {
        public const val SCRATCH_CAPACITY: Int = 8 * 1024

        /**
         * Default cap on un-responded pipelined requests held in
         * [acceptQueue]. 1024 is far above any legitimate HTTP/1.1
         * pipeline depth (browsers cap at ~6 connections and rarely
         * pipeline; even aggressive clients stay in the low tens), so a
         * client hitting it is pipelining heads without reading
         * responses — an abuse pattern the handler refuses by failing
         * the connection.
         */
        public const val DEFAULT_MAX_PENDING_RESPONSES: Int = 1024
    }
}

/** Per-response predicate to skip compression. */
public class CompressionCondition(
    public val minContentLength: Int = 0,
    public val skipMimeTypes: List<String> = listOf(
        "image/", "video/", "audio/",
        "application/zip", "application/gzip", "application/x-gzip",
        "application/x-7z-compressed", "application/x-rar-compressed",
        "application/x-bzip2", "application/zstd",
    ),
    private val custom: ((HttpResponseHead) -> Boolean)? = null,
) {
    /**
     * Whether a [custom] predicate is configured. Alloc-sensitive call
     * sites (the aggregated-response path) use this to skip constructing
     * an [HttpResponseHead] purely for the condition check when the
     * static headers-only checks are sufficient.
     */
    public val hasCustomPredicate: Boolean get() = custom != null

    public fun shouldCompress(head: HttpResponseHead): Boolean {
        if (!shouldCompressStatic(head.headers)) return false
        return custom?.invoke(head) ?: true
    }

    /**
     * Runs the headers-only checks (existing `Content-Encoding`, minimum
     * `Content-Length`, MIME-type skip list). Sufficient when
     * [hasCustomPredicate] is false; the aggregated-response path uses
     * this overload first so it can skip allocating an
     * [HttpResponseHead] when no custom predicate needs the status /
     * version.
     */
    public fun shouldCompressStatic(headers: HttpHeaders): Boolean {
        if (headers[HttpHeaderName.CONTENT_ENCODING] != null) return false
        if (minContentLength > 0) {
            val len = headers.getString(HttpHeaderName.CONTENT_LENGTH)?.toLongOrNull() ?: -1L
            if (len in 0L until minContentLength.toLong()) return false
        }
        val ctype = headers.getString("Content-Type")?.lowercase().orEmpty()
        if (skipMimeTypes.any { ctype.startsWith(it) }) return false
        return true
    }

    public companion object {
        public val Default: CompressionCondition = CompressionCondition()
    }
}

/**
 * Status codes the compression layer never re-encodes.
 *
 * - **1xx, 204, 304** carry no body; compressing nothing is at best wasteful
 *   and risks emitting a non-zero gzip / deflate envelope on a status the
 *   protocol assumes is body-less (RFC 9110 §15.4 1xx informational, §15.3.5
 *   204 No Content, §15.4.5 304 Not Modified — the latter only re-validates
 *   a cached representation and must carry whatever Content-Encoding the
 *   origin used, not a fresh one).
 * - **206 Partial Content**: a 206 body is a byte range of the *unencoded*
 *   representation that the `Content-Range` header points at. Compressing it
 *   would silently invalidate Content-Range (the client requested bytes
 *   X..Y of the entity and now receives a deflated payload that no longer
 *   maps to that range), and there is no in-band way to signal "this 206
 *   was re-encoded after the Range was computed". This is exactly why nginx
 *   (`gzip_proxied` default), Apache mod_deflate, and major CDNs skip
 *   compression on 206 (RFC 9110 §15.3.7 Content-Range + §8.4 Content-Coding
 *   interaction). keel follows the same convention.
 */
private fun isCompressionExempt(code: Int): Boolean =
    code in 100..199 || code == 204 || code == 206 || code == 304
