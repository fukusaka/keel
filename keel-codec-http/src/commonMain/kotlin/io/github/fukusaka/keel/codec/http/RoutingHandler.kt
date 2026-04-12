package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.pipeline.ChannelHandlerContext
import io.github.fukusaka.keel.pipeline.ChannelInboundHandler
import kotlin.reflect.KClass

/**
 * Pipeline handler that routes [HttpRequestHead] messages to registered path handlers.
 *
 * Looks up the request [HttpRequestHead.path] in [routes]. If a match is found, the
 * handler is invoked and the resulting [HttpResponse] is forwarded outbound via
 * [ChannelHandlerContext.propagateWrite] + [ChannelHandlerContext.propagateFlush].
 * Unmatched paths receive a 404 Not Found response.
 *
 * This is a terminal inbound handler — it does not forward any inbound message
 * to downstream handlers. Body messages ([HttpBody] and [HttpBodyEnd]) are
 * silently released without propagation; applications that need request body
 * access should insert [HttpBodyAggregator] before this handler.
 *
 * **Typical pipeline setup** (outbound handlers must precede inbound in addLast order
 * so that outbound propagation from this handler reaches them toward HEAD):
 * ```
 * pipeline.addLast("encoder", HttpResponseEncoder())
 * pipeline.addLast("decoder", HttpRequestDecoder())
 * pipeline.addLast("routing", RoutingHandler(mapOf(
 *     "/hello" to { _ -> HttpResponse.ok("Hello, World!") },
 * )))
 * ```
 */
class RoutingHandler(
    private val routes: Map<String, (HttpRequestHead) -> HttpResponse>,
) : ChannelInboundHandler {

    override val acceptedType: KClass<*> get() = HttpMessage::class

    /** Terminal inbound handler — produces no further inbound messages. */
    override val producedType: KClass<*> get() = Any::class

    override fun onRead(ctx: ChannelHandlerContext, msg: Any) {
        when (msg) {
            is HttpRequestHead -> routeRequest(ctx, msg)
            is HttpBodyEnd -> msg.content.release()
            is HttpBody -> msg.content.release()
            else -> ctx.propagateRead(msg)
        }
    }

    private fun routeRequest(ctx: ChannelHandlerContext, head: HttpRequestHead) {
        val handler = routes[head.path]
        val response = if (handler != null) handler(head) else HttpResponse.notFound()
        ctx.propagateWrite(response)
        ctx.propagateFlush()
    }
}
