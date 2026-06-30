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
 * pipeline.addLast("encoder",     HttpResponseEncoder())
 * pipeline.addLast("decoder",     HttpRequestDecoder())
 * pipeline.addLast("aggregator",  HttpBodyAggregator())
 * pipeline.addLast("routing",     RoutingHandler(routes))
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
 * releases every held chunk. On overflow, error, or a reset the held
 * chunks are released in one pass instead. The aggregator is stateful
 * and must not be shared between connections.
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

    private fun startAggregation(newHead: HttpRequestHead) {
        // A new head before the previous body completed: release any chunks
        // still held so a malformed sequence cannot leak the pool.
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
