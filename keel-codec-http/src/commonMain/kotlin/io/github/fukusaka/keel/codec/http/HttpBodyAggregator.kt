package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.IoBufMutableChunks
import io.github.fukusaka.keel.pipeline.InboundHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import kotlin.reflect.KClass

/**
 * Buffers a streaming [HttpRequestHead] + [HttpBody] + [HttpBodyEnd]
 * sequence into a single legacy [HttpRequest] with the full body as
 * [ByteArray], then forwards it downstream.
 *
 * Insert this handler between [HttpRequestDecoder] and application
 * handlers that expect the complete [HttpRequest] type:
 *
 * ```
 * pipeline.addLast("h1-encoder",    HttpResponseEncoder())
 * pipeline.addLast("h1-decoder",    HttpRequestDecoder())
 * pipeline.addLast("h1-aggregator", HttpBodyAggregator())
 * pipeline.addLast("routing",       RoutingHandler(routes))
 * ```
 *
 * **Size limit**: if the accumulated body exceeds [maxContentLength],
 * the held and remaining body [IoBuf]s are released and an
 * [HttpParseException] is propagated via
 * [PipelineHandlerContext.propagateError]. The caller is responsible
 * for closing the connection.
 *
 * **Lifecycle**: inbound body [IoBuf]s are *held* in a pooled
 * [IoBufMutableChunks] — no per-chunk copy and no growing intermediate
 * array. The body is flattened to a contiguous [ByteArray] exactly once,
 * at [HttpBodyEnd] (the application-API boundary), and that flatten
 * releases every held chunk. On overflow, error, connection close, or a
 * reset the held chunks — and the head's pooled [HttpHeaders], which retain
 * the recv buffer via the decoder's `addRange` zero-copy path — are released
 * instead (on the success path the headers' ownership transfers to the emitted
 * [HttpRequest]). The aggregator is stateful and must not be shared between
 * connections.
 */
class HttpBodyAggregator(
    private val maxContentLength: Int = DEFAULT_MAX_CONTENT_LENGTH,
) : InboundHandler {

    override val acceptedType: KClass<*> get() = HttpMessage::class
    override val producedType: KClass<*> get() = HttpRequest::class

    private var head: HttpRequestHead? = null
    private var acc: IoBufMutableChunks? = null
    private var overflowed: Boolean = false

    override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
        when (msg) {
            is HttpRequestHead -> startAggregation(msg)
            is HttpBodyEnd -> completeAggregation(ctx, msg)
            is HttpBody -> appendContent(msg)
            else -> ctx.propagateRead(msg)
        }
    }

    override fun onError(ctx: PipelineHandlerContext, cause: Throwable) {
        resetAggregation()
        ctx.propagateError(cause)
    }

    override fun onInactive(ctx: PipelineHandlerContext) {
        // A connection that closes mid-body (a peer that drops the socket
        // after the head and some body bytes, e.g. an abandoned upload)
        // leaves the partial body chunks held in [acc]. The decoder does
        // not synthesise a terminating [HttpBodyEnd] on close, so without
        // this release every truncated request leaks its held pool chunks
        // (a slow-loris-style accumulation). Release before propagating.
        resetAggregation()
        ctx.propagateInactive()
    }

    private fun startAggregation(newHead: HttpRequestHead) {
        // A new head before the previous body completed: release the previous
        // head's pooled headers (which retain the recv buffer via the decoder's
        // addRange zero-copy path) and any chunks still held, so a malformed
        // sequence cannot leak the pool / the recv buffer. release() is a no-op
        // for a non-pooled HttpHeaders and is idempotent for a pooled one.
        head?.headers?.release()
        acc?.release()
        head = newHead
        acc = null
        overflowed = false
    }

    private fun appendContent(content: HttpBody) {
        foldChunk(content.content)
    }

    /**
     * Folds one inbound [src] into the held accumulator. Ownership of a
     * retained chunk transfers to [acc] (no copy); an empty chunk, an
     * already-overflowed stream, or the chunk that tips the body past
     * [maxContentLength] is released here instead.
     */
    private fun foldChunk(src: IoBuf) {
        if (overflowed) {
            src.release()
            return
        }
        val len = src.readableBytes
        if (len == 0) {
            src.release()
            return
        }
        val target = acc ?: IoBufMutableChunks().also { acc = it }
        if (target.size + len > maxContentLength) {
            overflowed = true
            target.release()
            acc = null
            src.release()
            return
        }
        target.add(src)
    }

    private fun completeAggregation(ctx: PipelineHandlerContext, last: HttpBodyEnd) {
        foldChunk(last.content)
        val aggregatedHead = head
        if (aggregatedHead == null) {
            // Stray HttpBodyEnd without preceding head — reset defensively.
            resetAggregation()
            return
        }
        head = null
        if (overflowed) {
            // `head` is already nulled, so resetAggregation cannot release the
            // head's pooled headers — release the captured head's headers here.
            aggregatedHead.headers.release()
            resetAggregation()
            ctx.propagateError(
                HttpParseException(
                    "Request body exceeds maxContentLength ($maxContentLength)",
                ),
            )
            return
        }
        val held = acc
        acc = null
        // Flatten the held chunks to a contiguous array exactly once (the
        // application-API boundary); null body when nothing was held. Guard
        // the flatten so a failed body-sized allocation releases the held
        // pool chunks AND the head's recv-buffer-retaining headers instead of
        // leaking them (`head` is already nulled, so this is the sole owner).
        val finalBody = held?.let { chunks ->
            try {
                chunks.toByteArray()
            } catch (t: Throwable) {
                chunks.release()
                aggregatedHead.headers.release()
                throw t
            }
        }
        ctx.propagateRead(
            HttpRequest(
                aggregatedHead.method,
                aggregatedHead.uri,
                aggregatedHead.version,
                aggregatedHead.headers,
                finalBody,
            ),
        )
    }

    private fun resetAggregation() {
        // Release the held head's pooled headers as well as the body chunks:
        // the decoder's addRange zero-copy path retains the recv buffer in
        // head.headers, so a reset that only frees `acc` strands the (much
        // larger) recv buffer on the abort paths (onError / onInactive /
        // stray-end). Ownership is transferred to the emitted HttpRequest on
        // the success path, which does not route through here.
        head?.headers?.release()
        acc?.release()
        head = null
        acc = null
        overflowed = false
    }

    private companion object {
        /** Default maximum aggregated body size: 1 MiB. */
        private const val DEFAULT_MAX_CONTENT_LENGTH = 1 shl 20
    }
}
