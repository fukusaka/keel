package io.github.fukusaka.keel.server.http

import io.github.fukusaka.keel.codec.http.HttpRequest
import io.github.fukusaka.keel.codec.http.HttpResponse
import io.github.fukusaka.keel.codec.http.HttpStatus
import io.github.fukusaka.keel.codec.http.addHttp1ServerCodec
import io.github.fukusaka.keel.pipeline.InboundHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.reflect.KClass

/** Handler name of the dispatch stage in the server pipeline. */
internal const val HTTP_SERVER_HANDLER_NAME: String = "http-server"

/**
 * Installs the keel-server-http server pipeline on [this] channel:
 *
 * ```
 * HEAD ↔ decoder ↔ encoder ↔ aggregator ↔ http-server ↔ TAIL
 * ```
 *
 * The first three stages are the standard `keel-codec-http` HTTP/1.1
 * server codec ([addHttp1ServerCodec]); [HttpServerHandler] is the
 * dispatch stage that runs the application [handler]. [scope] is the
 * coroutine scope each request's suspending [handler] is launched on —
 * the owning engine in production, a test scope in unit tests.
 */
internal fun PipelinedChannel.installHttpServerPipeline(handler: RouteHandler, scope: CoroutineScope) {
    addHttp1ServerCodec(aggregateBody = true)
    pipeline.addLast(HTTP_SERVER_HANDLER_NAME, HttpServerHandler(handler, scope))
}

/**
 * Terminal inbound handler that dispatches each aggregated [HttpRequest]
 * to the application [RouteHandler].
 *
 * The pipeline callback ([onRead]) runs on the EventLoop thread and must
 * not suspend, so the suspending [handler] is launched as a coroutine on
 * [scope] bound to the channel's `ioDispatcher`. That dispatcher is the
 * EventLoop itself, so the coroutine — and any `respond` it issues —
 * resumes back on the owning thread, preserving the single-thread-per-
 * channel invariant.
 *
 * If the handler returns without calling [HttpCall.respond], or throws,
 * a `500 Internal Server Error` is emitted so the client is never left
 * waiting on a half-open request.
 */
internal class HttpServerHandler(
    private val handler: RouteHandler,
    private val scope: CoroutineScope,
) : InboundHandler {

    override val acceptedType: KClass<*> get() = HttpRequest::class

    /** Terminal inbound handler — produces no further inbound messages. */
    override val producedType: KClass<*> get() = Any::class

    override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
        if (msg !is HttpRequest) {
            ctx.propagateRead(msg)
            return
        }
        val call = PipelineHttpCall(msg, ctx)
        scope.launch(ctx.channel.ioDispatcher) {
            try {
                handler(call)
                if (!call.responded) {
                    ctx.propagateWriteAndFlush(INTERNAL_ERROR_RESPONSE)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                ctx.propagateError(e)
                if (!call.responded) {
                    ctx.propagateWriteAndFlush(INTERNAL_ERROR_RESPONSE)
                }
            }
        }
    }

    private companion object {
        val INTERNAL_ERROR_RESPONSE: HttpResponse =
            HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error")
    }
}

/**
 * [HttpCall] backed by a pipeline [PipelineHandlerContext].
 *
 * [respond] forwards the response outbound via the context. The write is
 * marshalled onto the channel's `ioDispatcher` (the EventLoop) so a
 * handler that hopped to another dispatcher mid-request still writes on
 * the owning thread.
 */
internal class PipelineHttpCall(
    override val request: HttpRequest,
    private val ctx: PipelineHandlerContext,
) : HttpCall {

    /**
     * True once [respond] has been invoked — this tracks "a response was
     * issued", not "bytes reached the wire".
     *
     * The flag is set before the outbound write, so it stays `true` even
     * if the write fails. That is deliberate: a failed write means the
     * transport is already broken, and the server's 500 guard keying off
     * this flag must not then push a second response onto it.
     */
    var responded: Boolean = false
        private set

    override suspend fun respond(response: HttpResponse) {
        check(!responded) { "respond() called more than once for the same request" }
        // Set before the write — see the `responded` KDoc for why a failed
        // write must still leave this flag `true`.
        responded = true
        withContext(ctx.channel.ioDispatcher) {
            ctx.propagateWriteAndFlush(response)
        }
    }
}
