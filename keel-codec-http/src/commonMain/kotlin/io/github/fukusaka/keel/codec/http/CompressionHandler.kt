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
            acceptQueue.addLast(msg.headers[HttpHeaderName.ACCEPT_ENCODING])
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

    private fun handleAggregatedResponse(ctx: PipelineHandlerContext, response: HttpResponse) {
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
        val encoder = registry.negotiate(accept) ?: run {
            ctx.propagateWrite(response)
            return
        }

        // Run the streaming SPI to compress the entire body, accumulating
        // chunks into a single ByteArray (aggregated response shape needs
        // a contiguous body). For aggregated responses the streaming win
        // is moot, but we still go through the streaming SPI for code
        // path consistency + per-chunk zip-bomb defence (encoder side
        // doesn't need it but symmetry).
        val session = encoder.newSession(allocator, defaultEncoderOptions)
        val srcBuf = allocator.allocate(response.body.size)
        srcBuf.writeByteArray(response.body, 0, response.body.size)
        val out = encodeAggregated(session, srcBuf)
        srcBuf.release()
        session.close()

        val newHeaders = rewriteHeaders(response.headers, encoder.name, fixedLength = out.size.toString())
        ctx.propagateWrite(response.copy(headers = newHeaders, body = out))
    }

    private fun handleResponseHead(ctx: PipelineHandlerContext, head: HttpResponseHead) {
        val accept = if (acceptQueue.isNotEmpty()) acceptQueue.removeFirst() else null

        if (isNoBodyStatus(head.status.code) || !condition.shouldCompress(head)) {
            ctx.propagateWrite(head)
            return
        }
        val encoder = registry.negotiate(accept) ?: run {
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

        // Drain any trailing input from the terminal HttpBody first.
        if (end.content.readableBytes > 0) {
            try {
                while (true) {
                    when (session.update(end.content, out)) {
                        CodecStatus.NEED_OUTPUT -> emitChunk(ctx, out)
                        CodecStatus.NEED_INPUT -> break
                        CodecStatus.FINISHED -> error("update should not return FINISHED")
                    }
                }
                if (out.readableBytes > 0) emitChunk(ctx, out)
            } finally {
                end.content.release()
            }
        } else {
            end.content.release()
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
     * Drive the streaming SPI, accumulating compressed output into a
     * primitive `ByteArray` (no `ArrayList<Byte>` boxing). Used by the
     * aggregated `HttpResponse` path where the result must end up as a
     * single contiguous byte array — bench `/large` 100 KB takes this
     * path on `pipeline-http-*`.
     *
     * Initial estimate is pessimistic (`max(input/4, 256)` — typical
     * gzip output for text payloads is ≤ ¼ of input); growth doubles
     * on overflow.
     */
    private fun encodeAggregated(session: EncoderSession, src: IoBuf): ByteArray {
        ensureScratch()
        val s = scratch!!
        var result = ByteArray((src.readableBytes / 4).coerceAtLeast(MIN_AGGREGATED_BUF))
        var resultLen = 0

        fun appendScratch() {
            val n = s.readableBytes
            if (n == 0) return
            if (resultLen + n > result.size) {
                val newSize = (result.size + n).coerceAtLeast(result.size * 2)
                result = result.copyOf(newSize)
            }
            s.readByteArray(result, resultLen, n)
            resultLen += n
            s.clear()
        }

        while (true) {
            when (session.update(src, s)) {
                CodecStatus.NEED_OUTPUT -> appendScratch()
                CodecStatus.NEED_INPUT -> break
                CodecStatus.FINISHED -> error("update should not return FINISHED")
            }
        }
        appendScratch()

        var finishing = true
        while (finishing) {
            when (session.finish(s)) {
                CodecStatus.NEED_OUTPUT -> appendScratch()
                CodecStatus.NEED_INPUT, CodecStatus.FINISHED -> {
                    appendScratch()
                    finishing = false
                }
            }
        }
        return if (resultLen == result.size) result else result.copyOf(resultLen)
    }

    private fun rewriteHeaders(src: HttpHeaders, encoding: String, fixedLength: String?): HttpHeaders {
        return HttpHeaders().apply {
            for (i in 0 until src.size) {
                val name = src.nameAt(i)
                val value = src.valueAt(i)
                if (name.equals(HttpHeaderName.CONTENT_LENGTH, ignoreCase = true)) continue
                if (name.equals(HttpHeaderName.CONTENT_ENCODING, ignoreCase = true)) continue
                add(name, value)
            }
            this[HttpHeaderName.CONTENT_ENCODING] = encoding
            if (fixedLength != null) this[HttpHeaderName.CONTENT_LENGTH] = fixedLength
            val existingVary = src["Vary"]
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
        private const val MIN_AGGREGATED_BUF: Int = 256
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
            val len = head.headers[HttpHeaderName.CONTENT_LENGTH]?.toLongOrNull() ?: -1L
            if (len in 0L until minContentLength.toLong()) return false
        }
        val ctype = head.headers["Content-Type"]?.lowercase().orEmpty()
        if (skipMimeTypes.any { ctype.startsWith(it) }) return false
        return custom?.invoke(head) ?: true
    }

    public companion object {
        public val Default: CompressionCondition = CompressionCondition()
    }
}

private fun isNoBodyStatus(code: Int): Boolean =
    code in 100..199 || code == 204 || code == 304
