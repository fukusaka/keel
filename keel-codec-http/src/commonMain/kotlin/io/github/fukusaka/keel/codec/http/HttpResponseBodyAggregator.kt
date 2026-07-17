package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.IoBufMutableChunks
import io.github.fukusaka.keel.pipeline.InboundHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import kotlin.reflect.KClass

/**
 * Buffers a streaming [HttpResponseHead] + [HttpBody] + [HttpBodyEnd]
 * sequence into a single [HttpResponse] with the full body as
 * [ByteArray], then forwards it downstream — the client counterpart of
 * [HttpBodyAggregator].
 *
 * Insert this handler between [HttpResponseDecoder] and consumers that
 * expect the complete [HttpResponse] type (see `addHttp1ClientCodec`).
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
 * releases every held chunk. On overflow, error, or a reset the held
 * chunks are released in one pass instead. The aggregator is stateful
 * and must not be shared between connections.
 *
 * **Interim responses**: a non-101 1xx head (`100 Continue`, `102`,
 * `103`) and its terminating [HttpBodyEnd] are consumed silently — the
 * final response that follows is the one aggregated and forwarded, so a
 * consumer awaiting one [HttpResponse] per request never receives an
 * interim status as its answer (RFC 9110 §15.2 lets a client ignore
 * unexpected 1xx responses). `101 Switching Protocols` is a final
 * response to its request and is aggregated normally.
 *
 * Deliberately a sibling of [HttpBodyAggregator] rather than a shared
 * base class: the fold/flatten core is small, and keeping the client
 * codec free of server-class edits keeps this addition self-contained.
 * Revisit extraction if a third aggregation variant appears.
 */
class HttpResponseBodyAggregator(
    private val maxContentLength: Int = DEFAULT_MAX_CONTENT_LENGTH,
) : InboundHandler {

    override val acceptedType: KClass<*> get() = HttpMessage::class
    override val producedType: KClass<*> get() = HttpResponse::class

    private var head: HttpResponseHead? = null
    private var acc: IoBufMutableChunks? = null
    private var overflowed: Boolean = false

    // True while the streamed message belongs to an interim (non-101 1xx)
    // response: its terminating HttpBodyEnd is consumed without emitting.
    private var interimPending: Boolean = false

    override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
        when (msg) {
            is HttpResponseHead ->
                if (isInterim(msg)) interimPending = true else startAggregation(msg)
            is HttpBodyEnd ->
                if (interimPending) {
                    interimPending = false
                    msg.release()
                } else {
                    completeAggregation(ctx, msg)
                }
            is HttpBody ->
                if (interimPending) {
                    // Interim responses are bodyless by construction; release
                    // defensively rather than folding into the next response.
                    msg.release()
                } else {
                    foldChunk(msg.content)
                }
            else -> ctx.propagateRead(msg)
        }
    }

    /** True for an interim informational head — every 1xx except 101. */
    private fun isInterim(head: HttpResponseHead): Boolean =
        head.status.isInformational && head.status.code != SWITCHING_PROTOCOLS_CODE

    override fun onError(ctx: PipelineHandlerContext, cause: Throwable) {
        resetAggregation()
        ctx.propagateError(cause)
    }

    override fun onInactive(ctx: PipelineHandlerContext) {
        // A close mid-aggregation (decoder already reported any truncation
        // via onError) must not strand held chunks.
        resetAggregation()
        ctx.propagateInactive()
    }

    private fun startAggregation(newHead: HttpResponseHead) {
        // A new head before the previous body completed: release any chunks
        // still held so a malformed sequence cannot leak the pool.
        acc?.release()
        head = newHead
        acc = null
        overflowed = false
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
            resetAggregation()
            ctx.propagateError(
                HttpParseException(
                    "Response body exceeds maxContentLength ($maxContentLength)",
                ),
            )
            return
        }
        val held = acc
        acc = null
        // Flatten the held chunks to a contiguous array exactly once (the
        // application-API boundary); null body when nothing was held. Guard
        // the flatten so a failed body-sized allocation releases the held
        // pool chunks instead of leaking them.
        val finalBody = held?.let { chunks ->
            try {
                chunks.toByteArray()
            } catch (t: Throwable) {
                chunks.release()
                throw t
            }
        }
        ctx.propagateRead(
            HttpResponse(
                aggregatedHead.status,
                aggregatedHead.version,
                aggregatedHead.headers,
                finalBody,
            ),
        )
    }

    private fun resetAggregation() {
        acc?.release()
        head = null
        acc = null
        overflowed = false
        interimPending = false
    }

    companion object {
        /** Default maximum aggregated body size: 1 MiB. */
        const val DEFAULT_MAX_CONTENT_LENGTH: Int = 1 shl 20

        private const val SWITCHING_PROTOCOLS_CODE = 101
    }
}
