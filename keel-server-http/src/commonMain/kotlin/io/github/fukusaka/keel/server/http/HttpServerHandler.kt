package io.github.fukusaka.keel.server.http

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.codec.http.HttpBody
import io.github.fukusaka.keel.codec.http.HttpBodyEnd
import io.github.fukusaka.keel.codec.http.HttpHeaderName
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.suspendCancellableCoroutine
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
 * body access. [middlewares] is the middleware chain wrapping every
 * request's dispatch (outermost first). [scope] is the coroutine scope
 * each request's suspending handler is launched on — the owning engine
 * in production, a test scope in unit tests.
 */
internal fun PipelinedChannel.installHttpServerPipeline(
    router: Router,
    middlewares: List<Middleware>,
    scope: CoroutineScope,
) {
    addHttp1ServerCodec(aggregateBody = false)
    pipeline.addLast(HTTP_SERVER_HANDLER_NAME, HttpServerHandler(router, middlewares, scope, channel = this))
}

/**
 * Terminal inbound handler that resolves each request through the
 * [Router] and dispatches it to the matched [RouteHandler].
 *
 * Receives the streaming codec's `HttpRequestHead` → `HttpBody`* →
 * `HttpBodyEnd` sequence. On `HttpRequestHead` the route is resolved, the
 * handler coroutine launched, and the [middlewares] chain run around the
 * dispatch; body chunks that follow are fed to the in-flight call's body
 * conduit (or released if no call is consuming them). A request with no
 * matching route is answered `404 Not Found` — still through the
 * middleware chain, so middleware observes it. A request resolving to an
 * upgrade route whose `Upgrade` header names the route's
 * [UpgradeProtocol] is handed to it as the chain terminal, so middleware
 * runs before the handshake.
 *
 * The pipeline callbacks run on the EventLoop thread; the suspending
 * handler is launched on a per-connection child of [scope], bound to the
 * channel's `ioDispatcher` (the EventLoop itself), so the handler — and
 * the body conduit it pulls from — runs on the owning thread, lock-free.
 *
 * If the handler returns without responding, or throws, a `500 Internal
 * Server Error` is emitted so the client is never left hanging.
 */
internal class HttpServerHandler(
    private val router: Router,
    private val middlewares: List<Middleware>,
    private val scope: CoroutineScope,
    private val channel: PipelinedChannel,
) : InboundHandler {

    override val acceptedType: KClass<*> get() = HttpMessage::class

    /** Terminal inbound handler — produces no further inbound messages. */
    override val producedType: KClass<*> get() = Any::class

    /**
     * Per-connection child scope each request handler is launched on.
     *
     * Every `launch` attaches the new coroutine to its parent `Job`'s
     * lock-free child list. Launching all requests of all connections on
     * the shared engine [scope] makes that one list a per-request
     * contention point (`LockFreeLinkedListNode.correctPrev` /
     * `attachChild` show up in CPU profiles at saturation). A child `Job`
     * per connection localises the list to one connection's in-flight
     * requests — HTTP/1.1 is serial per connection, so the list holds at
     * most one node. Cancelling it on [onInactive] tears down any handler
     * still running when the peer disconnects.
     */
    private val connectionScope: CoroutineScope =
        scope + Job(scope.coroutineContext[Job])

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

    /** Cancels any in-flight handler when the connection goes away. */
    override fun onInactive(ctx: PipelineHandlerContext) {
        connectionScope.cancel()
        ctx.propagateInactive()
    }

    private fun onRequestHead(ctx: PipelineHandlerContext, head: HttpRequestHead) {
        val match = router.resolve(head.method, head.path)
        // An upgrade request: the resolved route carries an UpgradeProtocol
        // and the request's `Upgrade` header names it. The upgrade is
        // dispatched as the terminal of the middleware chain (see
        // [invokeTerminal]), so middleware runs before the handshake —
        // auth / CORS / logging observe the upgrade request.
        val isUpgrade = upgradeFor(head.headers, match) != null
        // Fast path: no middleware and nothing to dispatch (no route, or a
        // route node with no handler for this method and no matching
        // upgrade) — answer 404 synchronously, without a handler coroutine.
        if (match?.handler == null && !isUpgrade && middlewares.isEmpty()) {
            ctx.propagateWriteAndFlush(NOT_FOUND_RESPONSE)
            return
        }
        val call = Http1Call(head, ctx, match?.pathParameters ?: emptyMap())
        inFlight = call
        connectionScope.launch(ctx.channel.ioDispatcher) {
            try {
                dispatch(call, match)
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

    /**
     * Runs the [middlewares] chain (if any) around the dispatch terminal.
     * With no middleware registered the terminal is invoked directly, so
     * the common path keeps the pre-middleware cost.
     */
    private suspend fun dispatch(call: Http1Call, match: RouteMatch?) {
        if (middlewares.isEmpty()) {
            invokeTerminal(call, match)
        } else {
            runChain(0, call, match)
        }
    }

    /**
     * Runs middleware [index]; its `next` continuation recurses into
     * [index] + 1, and the terminal runs once the chain is exhausted.
     */
    private suspend fun runChain(index: Int, call: Http1Call, match: RouteMatch?) {
        if (index < middlewares.size) {
            middlewares[index](call) { runChain(index + 1, call, match) }
        } else {
            invokeTerminal(call, match)
        }
    }

    /**
     * Chain terminal: the upgrade hand-off when the request resolved to an
     * `Upgrade`-matching protocol, otherwise the matched route handler, or
     * `404` when nothing matched. Reached after the middleware chain, so
     * middleware runs before an upgrade handshake.
     */
    private suspend fun invokeTerminal(call: Http1Call, match: RouteMatch?) {
        val upgrade = upgradeFor(call.headers, match)
        if (upgrade != null) {
            upgrade.upgrade(call, channel)
            return
        }
        val handler = match?.handler
        if (handler != null) {
            handler(call)
        } else {
            call.respond(NOT_FOUND_RESPONSE)
        }
    }

    /**
     * The [UpgradeProtocol] to dispatch to — non-null only when [match]'s
     * route carries one and [headers]' `Upgrade` token names it.
     */
    private fun upgradeFor(headers: HttpHeaders, match: RouteMatch?): UpgradeProtocol? {
        val upgrade = match?.upgrade ?: return null
        return if (headers[HttpHeaderName.UPGRADE].equalsIgnoreCase(upgrade.name)) upgrade else null
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
        // Collect every chunk first, then size the result array once and
        // copy each chunk in exactly once: O(body) total copy. Growing an
        // accumulator per chunk would be O(body * chunkCount) — quadratic
        // in the chunk count, which a chunked-encoding client controls.
        val chunks = ArrayList<IoBuf>()
        try {
            var total = 0
            while (true) {
                val chunk = receiveChunk() ?: break
                chunks.add(chunk)
                total += chunk.readableBytes
            }
            val acc = ByteArray(total)
            var offset = 0
            for (chunk in chunks) {
                val n = chunk.readableBytes
                if (n > 0) {
                    chunk.readByteArray(acc, offset, n)
                    offset += n
                }
            }
            return acc
        } finally {
            for (chunk in chunks) chunk.release()
        }
    }

    // --- response ---

    override suspend fun respond(response: HttpResponse) {
        markResponded()
        // The handler coroutine is launched on the channel's ioDispatcher
        // (the EventLoop itself), so this already runs on the owning
        // thread — no withContext hop needed.
        ctx.propagateWriteAndFlush(response)
    }

    override suspend fun respondText(text: String, status: HttpStatus) {
        respond(HttpResponse.of(status, text, contentType = TEXT_PLAIN_UTF8))
    }

    override suspend fun respondStream(
        head: HttpResponseHead,
        block: suspend (HttpResponseBodySink) -> Unit,
    ) {
        markResponded()
        ctx.propagateWrite(head)
        block(Http1ResponseBodySink(ctx))
        ctx.propagateWriteAndFlush(HttpBodyEnd.EMPTY)
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
        // Runs on the handler coroutine, already on the EventLoop thread.
        ctx.propagateWriteAndFlush(HttpBody(chunk))
    }
}

/** Case-insensitive equality tolerant of a null receiver (an absent header). */
private fun String?.equalsIgnoreCase(other: String): Boolean =
    this != null && this.equals(other, ignoreCase = true)
