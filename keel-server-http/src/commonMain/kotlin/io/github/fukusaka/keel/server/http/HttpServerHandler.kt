package io.github.fukusaka.keel.server.http

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.codec.http.HttpBody
import io.github.fukusaka.keel.codec.http.HttpBodyEnd
import io.github.fukusaka.keel.codec.http.HttpHeaders
import io.github.fukusaka.keel.codec.http.HttpMessage
import io.github.fukusaka.keel.codec.http.HttpMethod
import io.github.fukusaka.keel.codec.http.HttpRequestHead
import io.github.fukusaka.keel.codec.http.HttpResponse
import io.github.fukusaka.keel.codec.http.HttpResponseHead
import io.github.fukusaka.keel.codec.http.HttpStatus
import io.github.fukusaka.keel.codec.http.addHttp1ServerCodec
import io.github.fukusaka.keel.pipeline.InboundHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.reflect.KClass

/** Handler name of the dispatch stage in the server pipeline. */
internal const val HTTP_SERVER_HANDLER_NAME: String = "http-server"

/**
 * Installs the keel-server-http server pipeline on [this] channel:
 *
 * ```
 * HEAD ↔ decoder ↔ encoder ↔ http-server ↔ TAIL
 * ```
 *
 * The codec is installed in **streaming mode** (`aggregateBody = false`)
 * so [HttpServerHandler] sees the `HttpRequestHead` → `HttpBody`* →
 * `HttpBodyEnd` sequence and can offer both streaming and aggregated
 * body access. [scope] is the coroutine scope each request's suspending
 * handler is launched on — the owning engine in production, a test scope
 * in unit tests.
 */
internal fun PipelinedChannel.installHttpServerPipeline(router: Router, scope: CoroutineScope) {
    addHttp1ServerCodec(aggregateBody = false)
    pipeline.addLast(HTTP_SERVER_HANDLER_NAME, HttpServerHandler(router, scope))
}

/**
 * Terminal inbound handler that resolves each request through the
 * [Router] and dispatches it to the matched [RouteHandler].
 *
 * Receives the streaming codec's `HttpRequestHead` → `HttpBody`* →
 * `HttpBodyEnd` sequence. On `HttpRequestHead` the route is resolved and
 * the handler coroutine launched; body chunks that follow are fed to the
 * in-flight call's body conduit (or released if no call is consuming
 * them). A request with no matching route is answered `404 Not Found`.
 *
 * The pipeline callbacks run on the EventLoop thread; the suspending
 * handler is launched on [scope] bound to the channel's `ioDispatcher`
 * (the EventLoop itself), so the handler — and the body conduit it pulls
 * from — runs on the owning thread, lock-free.
 *
 * If the handler returns without responding, or throws, a `500 Internal
 * Server Error` is emitted so the client is never left hanging.
 */
internal class HttpServerHandler(
    private val router: Router,
    private val scope: CoroutineScope,
) : InboundHandler {

    override val acceptedType: KClass<*> get() = HttpMessage::class

    /** Terminal inbound handler — produces no further inbound messages. */
    override val producedType: KClass<*> get() = Any::class

    /** The call currently consuming body chunks, or null between requests. */
    private var inFlight: Http1Call? = null

    override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
        when (msg) {
            is HttpRequestHead -> onRequestHead(ctx, msg)
            is HttpBodyEnd -> {
                inFlight?.onBodyChunk(msg.content, last = true) ?: msg.content.release()
            }
            is HttpBody -> {
                inFlight?.onBodyChunk(msg.content, last = false) ?: msg.content.release()
            }
            else -> ctx.propagateRead(msg)
        }
    }

    private fun onRequestHead(ctx: PipelineHandlerContext, head: HttpRequestHead) {
        val match = router.resolve(head.method, head.path)
        if (match == null) {
            ctx.propagateWriteAndFlush(NOT_FOUND_RESPONSE)
            return
        }
        val call = Http1Call(head, ctx, match.pathParameters)
        inFlight = call
        scope.launch(ctx.channel.ioDispatcher) {
            try {
                match.handler(call)
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
            } finally {
                // The handler is done — stop feeding it body chunks and
                // drain anything that arrived but was never consumed.
                if (inFlight === call) inFlight = null
                call.discardUnconsumedBody()
            }
        }
    }

    private companion object {
        val NOT_FOUND_RESPONSE: HttpResponse =
            HttpResponse.of(HttpStatus.NOT_FOUND, "Not Found")
        val INTERNAL_ERROR_RESPONSE: HttpResponse =
            HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error")
    }
}

/**
 * [HttpCall] implementation for an HTTP/1.1 request, backed by the
 * pipeline [PipelineHandlerContext].
 *
 * **Body conduit**: body chunks delivered by [onBodyChunk] are handed to
 * a suspended [receiveChunk] caller if one is waiting, otherwise queued
 * in [pending] (lazily allocated on the first non-empty chunk — a
 * bodyless request never allocates it). [receiveChunk] is the zero-copy
 * primary; [receiveBytes] copies. `onBodyChunk` and the handler coroutine
 * both run on the EventLoop thread, so the conduit needs no locking.
 */
internal class Http1Call(
    private val head: HttpRequestHead,
    private val ctx: PipelineHandlerContext,
    override val pathParameters: Map<String, String>,
) : HttpCall {

    override val method: HttpMethod get() = head.method
    override val uri: String get() = head.uri
    override val path: String get() = head.path
    override val queryString: String? get() = head.queryString
    override val headers: HttpHeaders get() = head.headers

    /**
     * True once [respond] / [respondText] / [respondStream] has been
     * invoked — this tracks "a response was issued", not "bytes reached
     * the wire".
     *
     * Set before the outbound write, so it stays `true` even if the write
     * fails. That is deliberate: a failed write means the transport is
     * already broken, and [HttpServerHandler]'s 500 guard keying off this
     * flag must not then push a second response onto it.
     */
    var responded: Boolean = false
        private set

    // --- body conduit ---

    private var pending: ArrayDeque<IoBuf>? = null
    private var bodyEnded: Boolean = false
    private var bodyWaiter: CancellableContinuation<IoBuf?>? = null

    /**
     * Feeds a body chunk into the conduit. Called on the EventLoop thread
     * for every `HttpBody` / `HttpBodyEnd` of this request.
     */
    fun onBodyChunk(content: IoBuf, last: Boolean) {
        if (content.readableBytes > 0) {
            val waiter = bodyWaiter
            if (waiter != null) {
                bodyWaiter = null
                waiter.resume(content)
            } else {
                val queue = pending ?: ArrayDeque<IoBuf>().also { pending = it }
                queue.addLast(content)
            }
        } else {
            content.release()
        }
        if (last) {
            bodyEnded = true
            val waiter = bodyWaiter
            if (waiter != null) {
                bodyWaiter = null
                waiter.resume(null)
            }
        }
    }

    /** Releases any body chunks that arrived but were never consumed. */
    fun discardUnconsumedBody() {
        pending?.let { queue ->
            while (queue.isNotEmpty()) queue.removeFirst().release()
        }
    }

    override suspend fun receiveChunk(): IoBuf? {
        val queue = pending
        if (queue != null && queue.isNotEmpty()) return queue.removeFirst()
        if (bodyEnded) return null
        return suspendCancellableCoroutine { cont ->
            bodyWaiter = cont
            cont.invokeOnCancellation { bodyWaiter = null }
        }
    }

    override suspend fun receiveBytes(): ByteArray {
        var acc = ByteArray(0)
        while (true) {
            val chunk = receiveChunk() ?: return acc
            val n = chunk.readableBytes
            if (n > 0) {
                val grown = acc.copyOf(acc.size + n)
                chunk.readByteArray(grown, acc.size, n)
                acc = grown
            }
            chunk.release()
        }
    }

    // --- response ---

    override suspend fun respond(response: HttpResponse) {
        markResponded()
        withContext(ctx.channel.ioDispatcher) {
            ctx.propagateWriteAndFlush(response)
        }
    }

    override suspend fun respondText(text: String, status: HttpStatus) {
        respond(HttpResponse.of(status, text, contentType = TEXT_PLAIN_UTF8))
    }

    override suspend fun respondStream(
        head: HttpResponseHead,
        block: suspend (HttpResponseBodySink) -> Unit,
    ) {
        markResponded()
        withContext(ctx.channel.ioDispatcher) {
            ctx.propagateWrite(head)
        }
        block(Http1ResponseBodySink(ctx))
        withContext(ctx.channel.ioDispatcher) {
            ctx.propagateWriteAndFlush(HttpBodyEnd.EMPTY)
        }
    }

    private fun markResponded() {
        check(!responded) { "respond*() called more than once for the same request" }
        responded = true
    }

    private companion object {
        const val TEXT_PLAIN_UTF8 = "text/plain; charset=utf-8"
    }
}

/**
 * [HttpResponseBodySink] backing [HttpCall.respondStream]. Each [write]
 * propagates one `HttpBody` chunk outbound, taking ownership of the
 * caller's [IoBuf].
 */
private class Http1ResponseBodySink(
    private val ctx: PipelineHandlerContext,
) : HttpResponseBodySink {

    override suspend fun write(chunk: IoBuf) {
        withContext(ctx.channel.ioDispatcher) {
            ctx.propagateWriteAndFlush(HttpBody(chunk))
        }
    }
}
