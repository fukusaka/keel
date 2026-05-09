package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.BufferAllocator
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
 *    the session, replacing each chunk's payload with the compressed
 *    bytes.
 * 4. **Skips compression** when the request did not accept any
 *    registered encoding, the response status is no-body (1xx / 204 /
 *    304), or the optional [condition] rejects.
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
 * **Allocation**: the [allocator] used to back encoder output buffers.
 * Typically passed in from the channel's `BufferAllocator` so emitted
 * `IoBuf` instances are pool-managed alongside engine-emitted reads.
 *
 * @param registry encoder registry — caller pre-registers the codecs
 *   they want to support (`registry.register(GzipCodec)` etc.)
 * @param allocator output allocator for encoder sessions
 * @param condition optional predicate per response. Default = always
 *   compress when the client accepts. Common condition: skip
 *   pre-compressed MIME types (`image/`, `video/`, `application/zip`)
 *   or small responses (`Content-Length < N`)
 * @param defaultEncoderOptions options forwarded to every
 *   [Encoder.newSession] call. Keep [EncoderOptions.flushMode] as
 *   `Sync` (default) for HTTP streaming.
 */
public class CompressionHandler(
    private val registry: CompressionRegistry,
    private val allocator: BufferAllocator,
    private val condition: CompressionCondition = CompressionCondition.Default,
    private val defaultEncoderOptions: EncoderOptions = EncoderOptions(),
) : DuplexHandler {

    /** Pending Accept-Encoding values, FIFO per pipelined request. */
    private val acceptQueue: ArrayDeque<String?> = ArrayDeque()

    /** Active encoder session for the in-flight response, or `null`. */
    private var activeSession: EncoderSession? = null

    // ---- Inbound: capture Accept-Encoding ----

    override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
        if (msg is HttpRequestHead) {
            acceptQueue.addLast(msg.headers[HttpHeaderName.ACCEPT_ENCODING])
        }
        ctx.propagateRead(msg)
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
        // Reuse the response-head condition logic — wrap into a head temporarily.
        val asHead = HttpResponseHead(response.status, response.version, response.headers)
        if (!condition.shouldCompress(asHead)) {
            ctx.propagateWrite(response)
            return
        }
        val encoder = registry.negotiate(accept) ?: run {
            ctx.propagateWrite(response)
            return
        }
        val session = encoder.newSession(allocator, defaultEncoderOptions)
        val srcBuf = allocator.allocate(response.body.size)
        srcBuf.writeByteArray(response.body, 0, response.body.size)
        // Encoder takes ownership of srcBuf and releases it.
        val mid = session.update(srcBuf)
        val tail = session.finish()
        session.close()
        // Concatenate mid + tail bytes to a single ByteArray for the
        // aggregated response shape (HttpResponse holds a byte[] body,
        // not a streaming chunk list).
        val midN = mid.readableBytes
        val tailN = tail.readableBytes
        val out = ByteArray(midN + tailN)
        if (midN > 0) mid.readByteArray(out, 0, midN)
        if (tailN > 0) tail.readByteArray(out, midN, tailN)
        mid.release()
        tail.release()

        val newHeaders = HttpHeaders().apply {
            for (i in 0 until response.headers.size) {
                val name = response.headers.nameAt(i)
                val value = response.headers.valueAt(i)
                if (name.equals(HttpHeaderName.CONTENT_LENGTH, ignoreCase = true)) continue
                if (name.equals(HttpHeaderName.CONTENT_ENCODING, ignoreCase = true)) continue
                add(name, value)
            }
            this[HttpHeaderName.CONTENT_ENCODING] = encoder.name
            this[HttpHeaderName.CONTENT_LENGTH] = out.size.toString()
            val existingVary = response.headers["Vary"]
            this["Vary"] = if (existingVary.isNullOrBlank()) {
                "Accept-Encoding"
            } else if (existingVary.contains("accept-encoding", ignoreCase = true)) {
                existingVary
            } else {
                "$existingVary, Accept-Encoding"
            }
        }
        ctx.propagateWrite(response.copy(headers = newHeaders, body = out))
    }

    private fun handleResponseHead(ctx: PipelineHandlerContext, head: HttpResponseHead) {
        val accept = if (acceptQueue.isNotEmpty()) acceptQueue.removeFirst() else null

        // Skip when status is no-body (1xx, 204, 304).
        if (isNoBodyStatus(head.status.code) || !condition.shouldCompress(head)) {
            ctx.propagateWrite(head)
            return
        }
        val encoder = registry.negotiate(accept) ?: run {
            ctx.propagateWrite(head)
            return
        }

        val mutated = rewriteHeaders(head, encoder.name)
        activeSession = encoder.newSession(allocator, defaultEncoderOptions)
        ctx.propagateWrite(mutated)
    }

    private fun handleBody(ctx: PipelineHandlerContext, body: HttpBody) {
        val session = activeSession
        if (session == null) {
            ctx.propagateWrite(body)
            return
        }
        // session.update takes ownership of the input IoBuf.
        val encoded = session.update(body.content)
        if (encoded.readableBytes > 0) {
            ctx.propagateWrite(HttpBody(encoded))
        } else {
            encoded.release()
        }
    }

    private fun handleBodyEnd(ctx: PipelineHandlerContext, end: HttpBodyEnd) {
        val session = activeSession
        if (session == null) {
            ctx.propagateWrite(end)
            return
        }
        // Drain any buffered input from the terminal HttpBody payload first.
        if (end.content.readableBytes > 0) {
            val mid = session.update(end.content)
            if (mid.readableBytes > 0) {
                ctx.propagateWrite(HttpBody(mid))
            } else {
                mid.release()
            }
        } else {
            // HttpBodyEnd.EMPTY case — content already empty, just release.
            end.content.release()
        }
        // Final trailer (gzip CRC32+ISIZE / zlib Adler32 / format-specific).
        val tail = session.finish()
        session.close()
        activeSession = null
        if (tail.readableBytes > 0) {
            // Emit one last HttpBody for the trailer, then a fresh empty HttpBodyEnd.
            ctx.propagateWrite(HttpBody(tail))
            ctx.propagateWrite(HttpBodyEnd.EMPTY)
        } else {
            tail.release()
            ctx.propagateWrite(HttpBodyEnd.EMPTY)
        }
    }

    private fun rewriteHeaders(head: HttpResponseHead, encoding: String): HttpResponseHead {
        val newHeaders = HttpHeaders().apply {
            // Copy over existing fields, dropping Content-Length (post-compression
            // size is unknown; transition to chunked transfer encoding).
            for (i in 0 until head.headers.size) {
                val name = head.headers.nameAt(i)
                val value = head.headers.valueAt(i)
                if (name.equals(HttpHeaderName.CONTENT_LENGTH, ignoreCase = true)) continue
                if (name.equals(HttpHeaderName.CONTENT_ENCODING, ignoreCase = true)) continue
                add(name, value)
            }
            this[HttpHeaderName.CONTENT_ENCODING] = encoding
            // Add Vary: Accept-Encoding (cache correctness). If a Vary
            // header already exists with a different value, append.
            val existingVary = head.headers["Vary"]
            this["Vary"] = if (existingVary.isNullOrBlank()) {
                "Accept-Encoding"
            } else if (existingVary.contains("accept-encoding", ignoreCase = true)) {
                existingVary
            } else {
                "$existingVary, Accept-Encoding"
            }
        }
        return head.copy(headers = newHeaders)
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
        // Honor existing Content-Encoding (caller already encoded).
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
