package io.github.fukusaka.keel.server.http

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.IoBufChunks
import io.github.fukusaka.keel.buf.IoBufMutableChunks
import io.github.fukusaka.keel.codec.http.HttpBody
import io.github.fukusaka.keel.codec.http.HttpBodyEnd
import io.github.fukusaka.keel.codec.http.HttpHeaderLimitsConfig
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
 * each request's suspending handler is launched on — the server scope in
 * production, a test scope in unit tests. [connections] is the registry
 * the handler joins for the duration of the connection so
 * [KeelHttpServer.stop] can drain it. [queryParameterConfig] bounds the
 * query-string parsing of every request (see [QueryParameterConfig]).
 * [headerLimits] is the matching DoS guard for header parsing
 * (currently `maxHeaderCount`); see [HttpHeaderLimitsConfig].
 */
// Param count grows by one per new pipeline phase (codec / dispatch /
// hooks). 8 is detekt's project-wide threshold; the install function
// is `internal` and called from exactly one site (`KeelHttpServer.start`)
// so suppressing is bounded. The planned DSL pluggable redesign will
// collapse these into a single `HttpServerPipelineConfig` bundle.
@Suppress("LongParameterList")
internal fun PipelinedChannel.installHttpServerPipeline(
    router: Router,
    middlewares: List<Middleware>,
    errorHandlers: ErrorHandlers,
    queryParameterConfig: QueryParameterConfig,
    scope: CoroutineScope,
    connections: ServerConnections = ServerConnections(),
    headerLimits: HttpHeaderLimitsConfig = HttpHeaderLimitsConfig.DEFAULT,
    headerTimeoutMillis: Long = 0,
    requestTimeoutMillis: Long = 0,
    minBodyRateBytesPerSec: Long = 0,
    compression: io.github.fukusaka.keel.server.http.dsl.CompressionPipelineConfig? = null,
) {
    addHttp1ServerCodec(
        aggregateBody = false,
        headerLimits = headerLimits,
        headerTimeoutMillis = headerTimeoutMillis,
        requestTimeoutMillis = requestTimeoutMillis,
        minBodyRateBytesPerSec = minBodyRateBytesPerSec,
    )
    // Compression handlers sit between the codec (decoder/encoder) and
    // HttpServerHandler so they can intercept HttpRequestHead / HttpBody
    // (inbound, for `Content-Encoding`) and HttpResponseHead / HttpBody
    // (outbound, for `Accept-Encoding`) before the encoder serialises to
    // wire bytes. Either branch is a no-op when its config is absent.
    if (compression != null) {
        compression.installRequestDecoder(allocator)?.let { handler ->
            pipeline.addLast("request-decompression", handler)
        }
        if (compression.hasResponseEncoder) {
            pipeline.addLast("compression", compression.installResponseEncoder(allocator))
        }
    }
    pipeline.addLast(
        HTTP_SERVER_HANDLER_NAME,
        HttpServerHandler(
            router,
            middlewares,
            errorHandlers,
            queryParameterConfig,
            scope,
            connections,
            channel = this,
        ),
    )
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
 * If the handler returns without responding, a `500 Internal Server
 * Error` is emitted so the client is never left hanging. If it throws, a
 * registered exception mapper ([ErrorHandlers]) answers it, falling back
 * to `500` when none matches.
 *
 * **Graceful shutdown**: the handler joins [connections] while the
 * channel is active. [KeelHttpServer.stop] snapshots that registry and
 * calls [requestDrain] on each connection — an idle one is closed at
 * once, an active one finishes its in-flight request (whose response is
 * tagged `Connection: close`) before the channel closes.
 */
internal class HttpServerHandler(
    private val router: Router,
    private val middlewares: List<Middleware>,
    private val errorHandlers: ErrorHandlers,
    private val queryParameterConfig: QueryParameterConfig,
    private val scope: CoroutineScope,
    private val connections: ServerConnections,
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

    /**
     * Set once [requestDrain] has run on this connection. Touched only on
     * the EventLoop thread (the drain coroutine and [onRequestHead] both
     * run there), so a plain `var` is enough — no atomic needed.
     *
     * While set, every completed request closes the channel and its
     * response carries `Connection: close`.
     */
    private var draining: Boolean = false

    /** This connection's registry shard, joined on [onActive]. */
    private var shard: Shard? = null

    /** Joins this connection's EventLoop-thread registry shard. */
    override fun onActive(ctx: PipelineHandlerContext) {
        // onActive runs on the connection's owning EventLoop thread, and a
        // shard's handler set is mutated only by that thread — so this is
        // a direct, lock-free add: no coroutine launch, no mutex.
        val joined = connections.shardFor(channel.ioDispatcher)
        shard = joined
        joined.handlers.add(this)
        ctx.propagateActive()
    }

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
        // Leave the registry shard. onInactive runs on the same EventLoop
        // thread as onActive, so the remove is direct and lock-free; the
        // shard was assigned synchronously in onActive, so no ordering
        // gate is needed.
        shard?.handlers?.remove(this)
        ctx.propagateInactive()
    }

    /**
     * Begins draining this connection for [KeelHttpServer.stop]. Hops to
     * the connection's EventLoop thread, then: an idle connection (no
     * in-flight request) is closed at once; an active one is flagged so
     * its in-flight request closes the channel once it has responded, and
     * that response is tagged `Connection: close`.
     */
    fun requestDrain() {
        connectionScope.launch(channel.ioDispatcher) {
            draining = true
            val active = inFlight
            if (active == null) {
                channel.close()
            } else {
                active.markConnectionClose()
            }
        }
    }

    /** Suspends until this connection's channel is fully closed. */
    suspend fun awaitClosed() {
        channel.awaitClosed()
    }

    /** Closes the channel unconditionally — the shutdown force phase. */
    fun forceClose() {
        channel.close()
    }

    private fun onRequestHead(ctx: PipelineHandlerContext, head: HttpRequestHead) {
        // Parse the query string eagerly, at the water's edge: an
        // oversized / malformed query is answered `400 Bad Request`
        // synchronously, before route resolution, middleware, or the
        // handler coroutine — the same shape as the unmatched fast path.
        val queryParameters = try {
            parseQueryParameters(head.queryString, queryParameterConfig)
        } catch (@Suppress("SwallowedException") e: MalformedQueryStringException) {
            // The rejection is by design — the request is answered `400`
            // and not propagated; the cause carries no further detail the
            // client should see.
            head.headers.release()
            ctx.propagateWriteAndFlush(BAD_REQUEST_RESPONSE)
            if (draining) channel.close()
            return
        }
        val resolution = router.resolve(head.method, head.path, head)
        val match = (resolution as? RouteResolution.Matched)?.match
        // An upgrade request: the resolved route carries an UpgradeProtocol
        // and the request's `Upgrade` header names it. The upgrade is
        // dispatched as the terminal of the middleware chain (see
        // [invokeTerminal]), so middleware runs before the handshake —
        // auth / CORS / logging observe the upgrade request.
        val isUpgrade = upgradeFor(head.headers, match) != null
        // Fast path: an error response (404 / 405) with nothing that needs
        // a handler coroutine — no route handler for this method and no
        // matching upgrade, and no middleware / custom notFound to run
        // through. Answer synchronously.
        val unmatched = match?.handler == null && !isUpgrade
        val noAsyncWork = middlewares.isEmpty() && errorHandlers.notFound == null
        if (unmatched && noAsyncWork) {
            head.headers.release()
            ctx.propagateWriteAndFlush(errorResponseFor(resolution))
            if (draining) channel.close()
            return
        }
        val call = Http1Call(
            head,
            ctx,
            queryParameters,
            match?.pathParameters ?: emptyMap(),
            varyOnAccept = match?.varyOnAccept == true,
        )
        if (draining) call.markConnectionClose()
        inFlight = call
        connectionScope.launch(ctx.channel.ioDispatcher) {
            try {
                dispatch(call, resolution)
                // The 500 guard does not apply to an upgrade: a successful
                // upgrade takes over the connection (sends `101`, swaps the
                // pipeline codec) without going through `call.respond`, so
                // `responded` stays false by design.
                if (!isUpgrade && !call.responded) {
                    ctx.propagateWriteAndFlush(INTERNAL_ERROR_RESPONSE)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                handleException(ctx, call, e, isUpgrade)
            } finally {
                // The handler is done — stop feeding it body chunks and
                // drain anything that arrived but was never consumed.
                if (inFlight === call) inFlight = null
                call.discardUnconsumedBody()
                // Return the pooled request headers; the response has
                // been written so no further reads of `head.headers`
                // are valid. The `finally` runs exactly once per
                // request (one per `launch`), so the pool only sees
                // one matching `release` per `borrow` on the decoder
                // side.
                head.headers.release()
                // Draining: the request has been answered, so close the
                // keep-alive connection now (the response already carried
                // `Connection: close`). Runs even on cancellation.
                if (draining) channel.close()
            }
        }
    }

    /**
     * Runs the [middlewares] chain (if any) around the dispatch terminal.
     * With no middleware registered the terminal is invoked directly, so
     * the common path keeps the pre-middleware cost.
     */
    private suspend fun dispatch(call: Http1Call, resolution: RouteResolution) {
        if (middlewares.isEmpty()) {
            invokeTerminal(call, resolution)
        } else {
            runChain(0, call, resolution)
        }
    }

    /**
     * Runs middleware [index]; its `next` continuation recurses into
     * [index] + 1, and the terminal runs once the chain is exhausted.
     */
    private suspend fun runChain(index: Int, call: Http1Call, resolution: RouteResolution) {
        if (index < middlewares.size) {
            middlewares[index](call) { runChain(index + 1, call, resolution) }
        } else {
            invokeTerminal(call, resolution)
        }
    }

    /**
     * Chain terminal: the upgrade hand-off when the request resolved to an
     * `Upgrade`-matching protocol, otherwise the matched route handler, or
     * an error terminal — the configured `notFound` handler / built-in
     * `404` when nothing matched, or a `405 Method Not Allowed` when the
     * path is registered for other methods. Reached after the middleware
     * chain, so middleware runs before an upgrade handshake and observes
     * the `404` / `405`.
     */
    private suspend fun invokeTerminal(call: Http1Call, resolution: RouteResolution) {
        val match = (resolution as? RouteResolution.Matched)?.match
        val upgrade = upgradeFor(call.headers, match)
        if (upgrade != null) {
            upgrade.upgrade(call, channel)
            return
        }
        val handler = match?.handler
        if (handler != null) {
            handler(call)
            return
        }
        // No route handler. A 405 / 406 routes straight to the built-in
        // response — the `notFound` handler answers a genuine miss only,
        // not a method mismatch or a negotiation failure. A 404 prefers the
        // custom `notFound`.
        when (resolution) {
            is RouteResolution.MethodNotAllowed -> call.respond(methodNotAllowedResponse(resolution.allowedMethods))
            is RouteResolution.NotAcceptable -> call.respond(notAcceptableResponse(resolution.producibleTypes))
            else -> {
                val notFound = errorHandlers.notFound
                if (notFound != null) notFound(call) else call.respond(NOT_FOUND_RESPONSE)
            }
        }
    }

    /**
     * Completes a request whose handler threw. A registered exception
     * mapper turns the throwable into a response; with no mapper (or once
     * the handler had already responded — a second response would fail)
     * it is propagated and answered with the built-in `500`.
     *
     * An [isUpgrade] request that threw is reported but not answered: the
     * upgrade may already have swapped the pipeline codec, so injecting an
     * HTTP response would corrupt the new protocol's byte stream.
     */
    private suspend fun handleException(
        ctx: PipelineHandlerContext,
        call: Http1Call,
        cause: Throwable,
        isUpgrade: Boolean,
    ) {
        if (isUpgrade) {
            ctx.propagateError(cause)
            return
        }
        val mapper = if (call.responded) null else errorHandlers.mapperFor(cause)
        if (mapper != null) {
            try {
                mapper.handler(call, cause)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (mapperFailure: Throwable) {
                // The exception mapper itself failed — fall back to the
                // built-in 500, reporting the mapper's failure.
                ctx.propagateError(mapperFailure)
                if (!call.responded) ctx.propagateWriteAndFlush(INTERNAL_ERROR_RESPONSE)
                return
            }
        }
        ctx.propagateError(cause)
        if (!call.responded) ctx.propagateWriteAndFlush(INTERNAL_ERROR_RESPONSE)
    }

    /**
     * The [UpgradeProtocol] to dispatch to — non-null only when [match]'s
     * route carries one and [headers]' `Upgrade` token names it.
     */
    private fun upgradeFor(headers: HttpHeaders, match: RouteMatch?): UpgradeProtocol? {
        val upgrade = match?.upgrade ?: return null
        return if (headers.getString(HttpHeaderName.UPGRADE).equalsIgnoreCase(upgrade.name)) upgrade else null
    }

    private companion object {
        val NOT_FOUND_RESPONSE: HttpResponse =
            HttpResponse.of(HttpStatus.NOT_FOUND, "Not Found")
        val INTERNAL_ERROR_RESPONSE: HttpResponse =
            HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error")

        /**
         * The synchronous response for a request whose query string is
         * rejected — oversized (`maxParameterCount`) or, with the strict
         * options on, malformed. Answered at the edge, before dispatch.
         */
        val BAD_REQUEST_RESPONSE: HttpResponse =
            HttpResponse.of(HttpStatus.BAD_REQUEST, "Bad Request")

        /**
         * The synchronous fast-path error response for an unmatched
         * request: a `405 Method Not Allowed` carrying an `Allow` header
         * when the path is registered for other methods, otherwise a
         * `404 Not Found`. Used only when no middleware / `notFound` runs.
         */
        fun errorResponseFor(resolution: RouteResolution): HttpResponse = when (resolution) {
            is RouteResolution.MethodNotAllowed -> methodNotAllowedResponse(resolution.allowedMethods)
            is RouteResolution.NotAcceptable -> notAcceptableResponse(resolution.producibleTypes)
            else -> NOT_FOUND_RESPONSE
        }

        /**
         * Builds a `406 Not Acceptable` response. The body lists the media
         * types the matched route can produce (RFC 9110 §15.5.7 suggests
         * the response include the available representations), comma-space
         * joined and sorted for determinism. Carries `Vary: Accept` — the
         * 406 is itself an `Accept`-negotiation outcome (RFC 9110 §12.5.5).
         */
        fun notAcceptableResponse(producibleTypes: Set<String>): HttpResponse {
            val available = producibleTypes.sorted().joinToString(", ")
            return HttpResponse.of(HttpStatus.NOT_ACCEPTABLE, "Not Acceptable: $available").withVaryAccept()
        }

        /**
         * Builds a `405 Method Not Allowed` response whose `Allow` header
         * lists [allowedMethods], sorted by name and comma-space joined
         * (RFC 7231 §7.4.1).
         */
        fun methodNotAllowedResponse(allowedMethods: Set<HttpMethod>): HttpResponse {
            val allow = allowedMethods.map { it.name }.sorted().joinToString(", ")
            val base = HttpResponse.of(HttpStatus.METHOD_NOT_ALLOWED, "Method Not Allowed")
            val headers = HttpHeaders.build {
                base.headers.forEach { name, value -> add(name, value) }
                set(HttpHeaderName.ALLOW, allow)
            }
            return base.copy(headers = headers)
        }
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
    override val queryParameters: QueryParameters,
    override val pathParameters: Map<String, String>,
    /**
     * When true, the matched route negotiates on `Accept` (router R-5), so
     * this call's response is tagged `Vary: Accept` (RFC 9110 §12.5.5) for
     * cache correctness. See [RouteMatch.varyOnAccept].
     */
    private val varyOnAccept: Boolean = false,
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

    /**
     * Set when the connection is draining (see [HttpServerHandler]). The
     * response this call produces is then tagged `Connection: close` so
     * the client does not reuse the keep-alive connection the server is
     * about to close.
     */
    private var connectionClose: Boolean = false

    /** Marks this call's response to carry `Connection: close`. */
    fun markConnectionClose() {
        connectionClose = true
    }

    // --- body conduit ---

    private var pending: ArrayDeque<IoBuf>? = null
    private var pendingBytes: Int = 0

    /**
     * `true` when this conduit asked the transport to stop draining
     * reads because [pendingBytes] reached the high water mark. Cleared
     * once the consumer dequeues back below the low water mark.
     * Guards against double-pause / double-resume and is reset (without
     * a resume call) by [discardUnconsumedBody] when the connection is
     * tearing down.
     */
    private var readsPausedByBackpressure: Boolean = false
    private var bodyEnded: Boolean = false
    private var bodyWaiter: CancellableContinuation<IoBuf?>? = null

    /**
     * Feeds a body chunk into the conduit. Called on the EventLoop thread
     * for every `HttpBody` / `HttpBodyEnd` of this request.
     *
     * **Backpressure**: when the chunk lands in the [pending] queue (no
     * suspended consumer to hand it to directly) and the cumulative
     * queued bytes cross [INBOUND_BODY_HIGH_WATERMARK_BYTES], the
     * transport's read side is paused via [PipelinedChannel.pauseReads];
     * the engine then stops draining the kernel `rcvbuf` and the peer's
     * TCP window stalls. The dequeue path in [receiveChunk] re-arms reads
     * once the queue drains back below [INBOUND_BODY_LOW_WATERMARK_BYTES]
     * (hysteresis avoids flapping on every delivery). Chunks handed
     * straight to a suspended consumer bypass the queue entirely and do
     * not contribute to the watermark.
     */
    fun onBodyChunk(content: IoBuf, last: Boolean) {
        if (content.readableBytes > 0) {
            val waiter = bodyWaiter
            if (waiter != null) {
                // Direct handoff — bypass the queue and the watermark
                // accounting; the consumer is already waiting, the chunk
                // never lingers.
                bodyWaiter = null
                waiter.resume(content)
            } else {
                val queue = pending ?: ArrayDeque<IoBuf>().also { pending = it }
                queue.addLast(content)
                pendingBytes += content.readableBytes
                if (!readsPausedByBackpressure && pendingBytes >= INBOUND_BODY_HIGH_WATERMARK_BYTES) {
                    readsPausedByBackpressure = true
                    ctx.channel.pauseReads()
                }
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
        // The transport is being torn down; do NOT call resumeReads here.
        // Arming a dead transport's read would be a no-op at best and the
        // close path is responsible for the final cleanup.
        pendingBytes = 0
        readsPausedByBackpressure = false
    }

    override suspend fun receiveChunk(): IoBuf? {
        val queue = pending
        if (queue != null && queue.isNotEmpty()) {
            val chunk = queue.removeFirst()
            pendingBytes -= chunk.readableBytes
            if (readsPausedByBackpressure && pendingBytes <= INBOUND_BODY_LOW_WATERMARK_BYTES) {
                readsPausedByBackpressure = false
                ctx.channel.resumeReads()
            }
            return chunk
        }
        if (bodyEnded) return null
        return suspendCancellableCoroutine { cont ->
            bodyWaiter = cont
            cont.invokeOnCancellation { bodyWaiter = null }
        }
    }

    override suspend fun receiveBytes(): ByteArray {
        // Collect every chunk into an IoBufMutableChunks (held pooled, no
        // per-chunk realloc), then flatten once: O(body) total copy. The
        // toByteArray() flatten stays inside the try so its body-sized
        // allocation — whose size a chunked client controls — releases the
        // held chunks on OOM instead of leaking them.
        val acc = IoBufMutableChunks()
        try {
            while (true) {
                val chunk = receiveChunk() ?: break
                acc.add(chunk)
            }
            return acc.toByteArray()
        } catch (t: Throwable) {
            acc.release()
            throw t
        }
    }

    override suspend fun receiveChunks(): IoBufChunks {
        // Collect every body chunk into an IoBufMutableChunks and hand it off
        // as IoBufChunks — no flatten. Ownership transfers to the caller (it
        // releases). On error before hand-off, release what was collected.
        // (IoBufMutableChunks.add drops empty chunks.)
        val acc = IoBufMutableChunks()
        try {
            while (true) {
                val chunk = receiveChunk() ?: break
                acc.add(chunk)
            }
        } catch (t: Throwable) {
            acc.release()
            throw t
        }
        return acc.toIoBufChunks()
    }

    // --- response ---

    override suspend fun respond(response: HttpResponse) {
        markResponded()
        // The handler coroutine is launched on the channel's ioDispatcher
        // (the EventLoop itself), so this already runs on the owning
        // thread — no withContext hop needed.
        ctx.propagateWriteAndFlush(decorate(response))
    }

    override suspend fun respondText(text: String, status: HttpStatus) {
        respond(HttpResponse.of(status, text, contentType = TEXT_PLAIN_UTF8))
    }

    override suspend fun respondStream(
        head: HttpResponseHead,
        block: suspend (HttpResponseBodySink) -> Unit,
    ) {
        markResponded()
        ctx.propagateWrite(decorate(head))
        block(Http1ResponseBodySink(ctx))
        ctx.propagateWriteAndFlush(HttpBodyEnd.EMPTY)
    }

    /**
     * Applies this call's response-header decorations — `Vary: Accept` when
     * the route negotiates on `Accept`, then `Connection: close` while
     * draining. Each is a no-op header copy when its flag is unset.
     */
    private fun decorate(response: HttpResponse): HttpResponse {
        var out = response
        if (varyOnAccept) out = out.withVaryAccept()
        if (connectionClose) out = out.withConnectionClose()
        return out
    }

    /** [decorate] for the streaming-response head. */
    private fun decorate(head: HttpResponseHead): HttpResponseHead {
        var out = head
        if (varyOnAccept) out = out.withVaryAccept()
        if (connectionClose) out = out.withConnectionClose()
        return out
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
 *
 * **Backpressure**: after propagating the chunk, the sink suspends on
 * [PipelinedChannel.awaitFlushComplete] whenever
 * [PipelinedChannel.isWritable] is `false` — i.e. the transport's
 * pendingBytes have crossed the high-water mark and the EL has not yet
 * drained back below the low-water mark. Without this gate a chunked /
 * SSE producer that calls [write] in a tight loop outruns the
 * EventLoop's write-readiness processing: the EL keeps servicing emit
 * tasks and never reaches `kevent(2)` / `epoll_wait(2)`, so
 * write-readiness is observed late and throughput collapses while
 * pendingWrites grows unbounded. Mirrors the Ktor-adapter path's
 * `AbstractPipelinedWriteChannel.flush` gate. Honours the
 * "implementation can apply back-pressure" contract on
 * [HttpResponseBodySink.write].
 */
private class Http1ResponseBodySink(
    private val ctx: PipelineHandlerContext,
) : HttpResponseBodySink {

    override suspend fun write(chunk: IoBuf) {
        // Runs on the handler coroutine, already on the EventLoop thread.
        ctx.propagateWriteAndFlush(HttpBody(chunk))
        if (!ctx.channel.isWritable) {
            ctx.channel.awaitFlushComplete()
        }
    }
}

/** Case-insensitive equality tolerant of a null receiver (an absent header). */
private fun String?.equalsIgnoreCase(other: String): Boolean =
    this != null && this.equals(other, ignoreCase = true)

/** Token written into the `Connection` header while a connection is draining. */
private const val CONNECTION_CLOSE = "close"

/**
 * Builds a copy of [headers] with `Connection: close` set. The headers
 * are copied rather than mutated in place because the source may be a
 * shared constant (`NOT_FOUND_RESPONSE` and the like).
 */
private fun HttpHeaders.withConnectionClose(): HttpHeaders =
    HttpHeaders.build {
        this@withConnectionClose.forEach { name, value -> add(name, value) }
        set(HttpHeaderName.CONNECTION, CONNECTION_CLOSE)
    }

/** [HttpResponse] copy whose headers carry `Connection: close`. */
private fun HttpResponse.withConnectionClose(): HttpResponse =
    copy(headers = headers.withConnectionClose())

/** [HttpResponseHead] copy whose headers carry `Connection: close`. */
private fun HttpResponseHead.withConnectionClose(): HttpResponseHead =
    copy(headers = headers.withConnectionClose())

/** Field name added to / merged into `Vary` for `Accept`-negotiated responses. */
private const val ACCEPT_FIELD = "Accept"

/**
 * Inbound body queue high / low watermarks for `Http1Call`'s body
 * conduit. Crossing high suspends the transport's read side via
 * `pauseReads`, dropping back below low re-arms it via `resumeReads`.
 * Hysteresis avoids flapping on every delivery. Values mirror the
 * `SuspendBridgeHandler` read-side watermarks (64 KiB / 32 KiB) so a
 * future engine-common consolidation can lift the constants without a
 * tuning-value reconciliation.
 */
private const val INBOUND_BODY_HIGH_WATERMARK_BYTES = 64 * 1024
private const val INBOUND_BODY_LOW_WATERMARK_BYTES = 32 * 1024

/**
 * Builds a copy of [headers] with `Accept` added to `Vary`, **appending**
 * a `Vary: Accept` field line rather than rewriting what the handler set.
 *
 * `Vary` is a list-based field (RFC 9110 §12.5.5, `#( "*" / field-name )`),
 * so a separate `Vary: Accept` line is equivalent to extending an existing
 * `Vary` with `, Accept` (§5.3 — multiple lines combine, in order). Keel
 * therefore leaves the handler's own `Vary` line(s) byte-for-byte intact
 * and only adds its own, rather than collapsing / rewriting peer-supplied
 * headers.
 *
 * A no-op (returns the same instance) when any existing `Vary` line
 * already names `Accept`, or contains `*` (which subsumes every
 * field-name, so adding `Accept` is meaningless) — so repeated decoration
 * and a handler that set `Vary: Accept` itself do not duplicate the field.
 */
private fun HttpHeaders.withVaryAccept(): HttpHeaders {
    val present = getAll(HttpHeaderName.VARY)
        .flatMap { it.split(',') }
        .map { it.trim() }
    if (present.any { it == "*" || it.equals(ACCEPT_FIELD, ignoreCase = true) }) return this
    return HttpHeaders.build {
        this@withVaryAccept.forEach { name, value -> add(name, value) }
        add(HttpHeaderName.VARY, ACCEPT_FIELD)
    }
}

/** [HttpResponse] copy whose headers carry `Vary: Accept`. */
private fun HttpResponse.withVaryAccept(): HttpResponse =
    copy(headers = headers.withVaryAccept())

/** [HttpResponseHead] copy whose headers carry `Vary: Accept`. */
private fun HttpResponseHead.withVaryAccept(): HttpResponseHead =
    copy(headers = headers.withVaryAccept())
