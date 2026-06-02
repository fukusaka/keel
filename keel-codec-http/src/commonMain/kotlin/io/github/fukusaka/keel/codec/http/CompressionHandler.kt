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
 * **Per-channel scratch buffer**: a single output [IoBuf] of
 * [SCRATCH_CAPACITY] bytes is allocated once per channel attach and
 * reused across every emit. When the buffer fills, the handler copies
 * its readable bytes into a freshly allocated `IoBuf` (sized to the
 * exact emit size) and propagates that downstream as `HttpBody`. The
 * scratch is then cleared and refilled. This keeps the handler's
 * steady-state allocation rate at "one IoBuf per emitted chunk" rather
 * than "one IoBuf per `update` call regardless of output size", and
 * caps memory peak at `SCRATCH_CAPACITY` per pending response.
 *
 * **Pipelining**: this handler tracks one in-flight response at a time
 * (request → response is strictly sequential on HTTP/1.1). The
 * inbound `Accept-Encoding` is captured into a queue so that a
 * pipelined client (sequential request/response on one connection)
 * still gets the right value matched to each response.
 *
 * **Mid-stream failure recovery**: if a response aborts mid-stream (a
 * downstream write throws, or an emit allocation fails), the handler
 * closes the in-flight [EncoderSession] and clears the scratch buffer
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
 * @param allocator output allocator for emitted body chunks + scratch
 * @param condition optional predicate per response. Default = always
 *   compress when the client accepts. Common condition: skip
 *   pre-compressed MIME types (`image/`, `video/`, `application/zip`)
 *   or small responses (`Content-Length < N`)
 * @param defaultEncoderOptions options forwarded to every
 *   [Encoder.newSession] call. Keep [EncoderOptions.flushMode] as
 *   `Sync` (default) for HTTP streaming.
 * @param scratchCapacity per-channel scratch IoBuf size. Higher =
 *   fewer emit cycles + larger emit size, lower = bounded peak.
 *   Default 8 KiB matches Netty `JdkZlibEncoder`'s emit chunk size
 */
public class CompressionHandler(
    private val registry: CompressionRegistry,
    private val allocator: BufferAllocator,
    private val condition: CompressionCondition = CompressionCondition.Default,
    private val defaultEncoderOptions: EncoderOptions = EncoderOptions(),
    private val scratchCapacity: Int = SCRATCH_CAPACITY,
) : DuplexHandler {

    /** Pending Accept-Encoding values, FIFO per pipelined request. */
    private val acceptQueue: ArrayDeque<String?> = ArrayDeque()

    /** Active encoder session for the in-flight response, or `null`. */
    private var activeSession: EncoderSession? = null

    /** Per-channel reusable output scratch — allocated lazily on first compressed response. */
    private var scratch: IoBuf? = null

    // ---- Inbound: capture Accept-Encoding ----

    override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
        if (msg is HttpRequestHead) {
            acceptQueue.addLast(msg.headers.getCombined(HttpHeaderName.ACCEPT_ENCODING))
        }
        ctx.propagateRead(msg)
    }

    override fun handlerRemoved(ctx: PipelineHandlerContext) {
        scratch?.release()
        scratch = null
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
     * or an emit allocation failed — can leave [activeSession] open and
     * [scratch] holding partially compressed bytes. Because one handler
     * instance serves every response on a keep-alive connection, starting the
     * next response without discarding them would leak the encoder session and
     * bleed the leftover bytes into the head of the new response.
     *
     * [EncoderSession.close] is idempotent, so calling this after a response
     * that ended cleanly (session already closed and nulled, scratch already
     * cleared) is a no-op.
     */
    private fun discardPendingResponse() {
        activeSession?.close()
        activeSession = null
        scratch?.clear()
    }

    private fun handleAggregatedResponse(ctx: PipelineHandlerContext, response: HttpResponse) {
        discardPendingResponse()
        val accept = if (acceptQueue.isNotEmpty()) acceptQueue.removeFirst() else null

        if (isNoBodyStatus(response.status.code) || response.body == null || response.body.isEmpty()) {
            ctx.propagateWrite(response)
            return
        }
        val asHead = HttpResponseHead(response.status, response.version, response.headers)
        if (!condition.shouldCompress(asHead)) {
            ctx.propagateWrite(response)
            return
        }
        val encoder = negotiateContentEncoding(registry, accept) ?: run {
            ctx.propagateWrite(response)
            return
        }

        // Stream-compress the aggregated body through the chunked path: emit the
        // head (Transfer-Encoding: chunked), then drive the encoder over the body
        // emitting HttpBody chunks *as they are produced*, then HttpBodyEnd. This
        // never buffers the whole compressed output (no contiguous ByteArray) —
        // memory is bounded to one scratch buffer — and matches how nginx / Netty
        // / Ktor compress dynamically. Content-Length is replaced by chunked
        // since the compressed size is no longer materialised (RFC 9112 §6.1
        // forbids both). The head + body + end reuse the streaming handlers.
        val mutatedHead = HttpResponseHead(
            response.status,
            response.version,
            rewriteHeaders(response.headers, encoder.name, fixedLength = null),
        )
        activeSession = encoder.newSession(allocator, defaultEncoderOptions)
        ensureScratch()
        ctx.propagateWrite(mutatedHead)

        val body = response.body
        val src = allocator.allocate(body.size).apply { writeByteArray(body, 0, body.size) }
        handleBody(ctx, HttpBody(src))
        handleBodyEnd(ctx, HttpBodyEnd.EMPTY)
    }

    private fun handleResponseHead(ctx: PipelineHandlerContext, head: HttpResponseHead) {
        discardPendingResponse()
        val accept = if (acceptQueue.isNotEmpty()) acceptQueue.removeFirst() else null

        if (isNoBodyStatus(head.status.code) || !condition.shouldCompress(head)) {
            ctx.propagateWrite(head)
            return
        }
        val encoder = negotiateContentEncoding(registry, accept) ?: run {
            ctx.propagateWrite(head)
            return
        }

        val mutated = head.copy(headers = rewriteHeaders(head.headers, encoder.name, fixedLength = null))
        activeSession = encoder.newSession(allocator, defaultEncoderOptions)
        ensureScratch()
        ctx.propagateWrite(mutated)
    }

    private fun handleBody(ctx: PipelineHandlerContext, body: HttpBody) {
        val session = activeSession
        if (session == null) {
            ctx.propagateWrite(body)
            return
        }
        val src = body.content
        val out = scratch!!
        try {
            // Drive update until input fully consumed; emit chunks on NEED_OUTPUT.
            while (true) {
                when (session.update(src, out)) {
                    CodecStatus.NEED_OUTPUT -> emitChunk(ctx, out)
                    CodecStatus.NEED_INPUT -> break
                    CodecStatus.FINISHED -> error("update should not return FINISHED")
                }
            }
            // Emit any pending output bytes from this update.
            if (out.readableBytes > 0) emitChunk(ctx, out)
        } catch (e: Throwable) {
            // The response aborted mid-stream — close the session and clear
            // scratch now so the next response on this connection starts clean
            // (no leaked session, no bled-over bytes), then re-throw.
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
        val out = scratch!!
        try {
            // Drain any trailing input from the terminal HttpBody first.
            if (end.content.readableBytes > 0) {
                while (true) {
                    when (session.update(end.content, out)) {
                        CodecStatus.NEED_OUTPUT -> emitChunk(ctx, out)
                        CodecStatus.NEED_INPUT -> break
                        CodecStatus.FINISHED -> error("update should not return FINISHED")
                    }
                }
                if (out.readableBytes > 0) emitChunk(ctx, out)
            }

            // Drive finish to emit the format trailer.
            var finishing = true
            while (finishing) {
                when (session.finish(out)) {
                    CodecStatus.NEED_OUTPUT -> emitChunk(ctx, out)
                    CodecStatus.NEED_INPUT, CodecStatus.FINISHED -> {
                        if (out.readableBytes > 0) emitChunk(ctx, out)
                        finishing = false
                    }
                }
            }

            session.close()
            activeSession = null
        } catch (e: Throwable) {
            // The response aborted mid-finish — close the session and clear
            // scratch now so the next response on this connection starts clean,
            // then re-throw.
            discardPendingResponse()
            throw e
        } finally {
            end.content.release()
        }
        ctx.propagateWrite(HttpBodyEnd.EMPTY)
    }

    /**
     * Emit the readable bytes of [scratch] as a fresh [HttpBody]
     * downstream, then clear the scratch for reuse.
     *
     * Uses `IoBuf.copyTo` to skip the intermediate `ByteArray` that
     * a `readByteArray + writeByteArray` round-trip would force —
     * `copyTo` delegates to platform-optimized `memcpy` on JVM
     * (DirectByteBuffer.put) and Native (`memcpy` via cinterop).
     */
    private fun emitChunk(ctx: PipelineHandlerContext, scratchBuf: IoBuf) {
        val n = scratchBuf.readableBytes
        if (n == 0) return
        val emit = allocator.allocate(n)
        scratchBuf.copyTo(emit, n)
        scratchBuf.clear()
        ctx.propagateWrite(HttpBody(emit))
    }

    private fun ensureScratch() {
        if (scratch == null) {
            scratch = allocator.allocate(scratchCapacity)
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
                val value = src.valueAt(i)
                if (name.equals(HttpHeaderName.CONTENT_LENGTH, ignoreCase = true)) continue
                if (name.equals(HttpHeaderName.CONTENT_ENCODING, ignoreCase = true)) continue
                if (name.equals(HttpHeaderName.TRANSFER_ENCODING, ignoreCase = true)) continue
                add(name, value)
            }
            this[HttpHeaderName.CONTENT_ENCODING] = encoding
            if (fixedLength != null) {
                this[HttpHeaderName.CONTENT_LENGTH] = fixedLength
            } else {
                this[HttpHeaderName.TRANSFER_ENCODING] = "chunked"
            }
            val existingVary = src.getString("Vary")
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
    public fun shouldCompress(head: HttpResponseHead): Boolean {
        if (head.headers[HttpHeaderName.CONTENT_ENCODING] != null) return false
        if (minContentLength > 0) {
            val len = head.headers.getString(HttpHeaderName.CONTENT_LENGTH)?.toLongOrNull() ?: -1L
            if (len in 0L until minContentLength.toLong()) return false
        }
        val ctype = head.headers.getString("Content-Type")?.lowercase().orEmpty()
        if (skipMimeTypes.any { ctype.startsWith(it) }) return false
        return custom?.invoke(head) ?: true
    }

    public companion object {
        public val Default: CompressionCondition = CompressionCondition()
    }
}

private fun isNoBodyStatus(code: Int): Boolean =
    code in 100..199 || code == 204 || code == 304
