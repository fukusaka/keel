package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.IoBuf
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
 * all remaining body messages are drained (releasing their [IoBuf]s)
 * and an [HttpParseException] is propagated via
 * [PipelineHandlerContext.propagateError]. The caller is responsible
 * for closing the connection.
 *
 * **Lifecycle**: every inbound [IoBuf] is released after its bytes are
 * copied into the aggregation buffer. The aggregator is stateful and
 * must not be shared between connections.
 */
class HttpBodyAggregator(
    private val maxContentLength: Int = DEFAULT_MAX_CONTENT_LENGTH,
) : InboundHandler {

    override val acceptedType: KClass<*> get() = HttpMessage::class
    override val producedType: KClass<*> get() = HttpRequest::class

    private var head: HttpRequestHead? = null
    private var body: ByteArray? = null
    private var bodySize: Int = 0
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
        head = newHead
        body = null
        bodySize = 0
        overflowed = false
    }

    private fun appendContent(content: HttpBody) {
        val src = content.content
        try {
            copyIntoBody(src)
        } finally {
            src.release()
        }
    }

    private fun copyIntoBody(src: IoBuf) {
        if (overflowed) return
        val len = src.readableBytes
        if (len == 0) return
        if (bodySize + len > maxContentLength) {
            overflowed = true
            body = null
            bodySize = 0
            return
        }
        val cur = body
        val needed = bodySize + len
        val buf = when {
            cur == null -> ByteArray(maxOf(len, INITIAL_BODY_CAPACITY))
            cur.size < needed -> cur.copyOf(maxOf(needed, cur.size * 2).coerceAtMost(maxContentLength))
            else -> cur
        }
        body = buf
        src.readByteArray(buf, bodySize, len)
        bodySize += len
    }

    private fun completeAggregation(ctx: PipelineHandlerContext, last: HttpBodyEnd) {
        val lastBuf = last.content
        try {
            if (lastBuf.readableBytes > 0) copyIntoBody(lastBuf)
        } finally {
            lastBuf.release()
        }
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
        val finalBody = if (bodySize == 0) null else body?.copyOf(bodySize)
        body = null
        bodySize = 0
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
        head = null
        body = null
        bodySize = 0
        overflowed = false
    }

    private companion object {
        /** Default maximum aggregated body size: 1 MiB. */
        private const val DEFAULT_MAX_CONTENT_LENGTH = 1 shl 20

        /** Initial backing array capacity, sized to fit small JSON payloads. */
        private const val INITIAL_BODY_CAPACITY = 256
    }
}
