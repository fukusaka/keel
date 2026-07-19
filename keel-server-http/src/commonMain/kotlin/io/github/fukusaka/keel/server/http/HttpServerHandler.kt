package io.github.fukusaka.keel.server.http

import io.github.fukusaka.keel.buf.EmptyIoBuf
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
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
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
// Param count is capped by [pipelineInstallers]: new pipeline-level
// extensions (compression, future metrics / rate-limit / 3rd-party plugins)
// register through that hook rather than adding a parameter here, so the
// count no longer grows per feature. The function is `internal` and called
// from exactly one site (`KeelHttpServer.start`), so suppressing is bounded.
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
    pipelineInstallers: List<PipelineInstaller> = emptyList(),
) {
    addHttp1ServerCodec(
        aggregateBody = false,
        headerLimits = headerLimits,
        headerTimeoutMillis = headerTimeoutMillis,
        requestTimeoutMillis = requestTimeoutMillis,
        minBodyRateBytesPerSec = minBodyRateBytesPerSec,
    )
    // Pipeline installers sit between the codec (decoder/encoder) and
    // HttpServerHandler so their handlers can intercept HttpRequestHead /
    // HttpBody (inbound) and HttpResponseHead / HttpBody (outbound) before the
    // encoder serialises to wire bytes. They run in registration order — the
    // built-in `compression { }` DSL registers one installer here. See
    // [PipelineInstaller].
    for (installer in pipelineInstallers) {
        installer.install(pipeline, allocator)
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
     *
     * The channel's `ioDispatcher` (the owning EventLoop) is folded in
     * once here — it is constant for the connection's life — so the
     * per-request `launch` sites need no explicit dispatcher argument.
     * `launch(dispatcher) { }` re-combines the scope context with the
     * dispatcher into a fresh [kotlin.coroutines.CombinedContext] on every
     * request; folding it once per connection removes that per-request
     * allocation while keeping the exact same EventLoop-thread affinity.
     */
    private val connectionScope: CoroutineScope =
        scope + Job(scope.coroutineContext[Job]) + channel.ioDispatcher

    /**
     * Completion for the born-parented inline request dispatch in
     * [onRequestHead]. Its [context] is [connectionScope]'s — the per-connection
     * [Job] plus the EventLoop dispatcher — so a handler started with
     * `startCoroutineUninterceptedOrReturn(this)` is born correctly parented and
     * EL-dispatched: a synchronously-completing handler runs inline on the
     * EventLoop thread with no `StandaloneCoroutine` / `DispatchedContinuation` /
     * `ChildHandleNode` / EL dispatch task allocated, while one that suspends is
     * still resumed on the EventLoop thread (the suspension point intercepts via
     * this context's dispatcher) and torn down by [onInactive]'s
     * `connectionScope.cancel()` (the suspension point registers its cancellation
     * handler on this context's [Job]).
     *
     * [resumeWith] runs only when a handler completed by *suspending* and later
     * finished — the synchronous path never invokes it (a synchronously-thrown
     * outcome unwinds to the [onRequestHead] call site, which handles it
     * symmetrically). The handler body owns its own `try/catch(Throwable)`, so
     * ordinary handler errors are handled there and never reach here; [resumeWith]
     * mirrors `launch`'s coroutine boundary for the residual case where the body's
     * `catch`/`finally` itself throws: a [CancellationException] (the expected
     * shape on disconnect) is swallowed, and anything else cancels the connection —
     * a visible effect — rather than the failure being lost.
     */
    private val dispatchCompletion: Continuation<Unit> = object : Continuation<Unit> {
        override val context: CoroutineContext = connectionScope.coroutineContext

        override fun resumeWith(result: Result<Unit>) {
            val cause = result.exceptionOrNull() ?: return
            if (cause is CancellationException) return
            connectionScope.cancel(CancellationException("request handler completion failed", cause))
        }
    }

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

    /**
     * The request handler body, run per request via
     * `startCoroutineUninterceptedOrReturn(dispatchCompletion)` in [onRequestHead].
     * Allocated **once per connection** and reused: it reads the current call from
     * [inFlight] (HTTP/1.1 keep-alive is serial, so exactly one request is in
     * flight when it runs) rather than capturing per-request state in a fresh
     * closure. That keeps the SuspendLambda off the per-request allocation path —
     * only the coroutine's own state-machine copy, which any `suspend` invocation
     * needs, remains per request. The `try/finally` lives here so the exactly-once
     * pooled `head.headers.release()` runs once whether the handler completes
     * synchronously, throws, or suspends-then-resumes.
     */
    private val dispatchBody: suspend () -> Unit = {
        val call = checkNotNull(inFlight) { "dispatchBody run with no in-flight call" }
        try {
            dispatch(call, call.resolution)
            // The 500 guard does not apply to an upgrade: a successful upgrade
            // takes over the connection (sends `101`, swaps the pipeline codec)
            // without going through `call.respond`, so `responded` stays false.
            if (!call.isUpgrade && !call.responded) {
                call.ctx.propagateWriteAndFlush(INTERNAL_ERROR_RESPONSE)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            handleException(call.ctx, call, e, call.isUpgrade)
        } finally {
            // The handler is done — stop feeding it body chunks and drain
            // anything that arrived but was never consumed.
            if (inFlight === call) inFlight = null
            call.discardUnconsumedBody()
            // Return the pooled request headers; the response has been written so
            // no further reads of the head are valid. Runs exactly once per request.
            call.head.headers.release()
            // Draining: the request has been answered, so close the keep-alive
            // connection now (the response already carried `Connection: close`).
            if (draining) channel.close()
        }
    }

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
        connectionScope.launch {
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
            resolution = resolution,
            isUpgrade = isUpgrade,
            varyOnAccept = match?.varyOnAccept == true,
        )
        if (draining) call.markConnectionClose()
        inFlight = call
        // Born-parented inline dispatch: run the reusable per-connection
        // [dispatchBody] on the EventLoop thread this call already runs on, rather
        // than through `connectionScope.launch`. A body that completes
        // synchronously (no suspension — the /hello shape) returns without
        // allocating any `StandaloneCoroutine` / `DispatchedContinuation` /
        // `ChildHandleNode` / EL dispatch task (measured -111 B/req on the NIO
        // EventLoop dispatcher); reusing [dispatchBody] keeps its SuspendLambda off
        // the per-request path as well (only the state-machine copy any `suspend`
        // invocation needs remains). One that suspends is carried by
        // [dispatchCompletion]'s context (connectionScope's Job + EL dispatcher):
        // the suspension point intercepts through that dispatcher (so it resumes on
        // the EventLoop thread) and registers its cancellation on that Job (so
        // [onInactive]'s `connectionScope.cancel()` still tears it down). The
        // `try/finally` lives in [dispatchBody], so the exactly-once pooled
        // `head.headers.release()` runs once whether the body completes
        // synchronously, throws, or suspends-then-resumes.
        // The intrinsic returns `Unit` on synchronous completion or
        // COROUTINE_SUSPENDED when the body suspended (which then completes on its
        // own via [dispatchCompletion]) — neither return needs action. A
        // synchronously *thrown* outcome unwinds to here rather than through
        // [dispatchCompletion] (which only runs after a suspension), so mirror what
        // `connectionScope.launch` did at its boundary and keep the two completion
        // routes symmetric: absorb a cancellation (the body re-throws it after its
        // `finally` has already run — it must not reach the pipeline tail), and
        // cancel the connection on any other residual throwable (the body's own
        // `catch` handles ordinary handler errors, so this is only reached if that
        // `catch` / `finally` itself threw).
        try {
            dispatchBody.startCoroutineUninterceptedOrReturn(dispatchCompletion)
        } catch (ignore: CancellationException) {
            // Absorbed as coroutine cancellation, exactly as connectionScope.launch
            // did at its boundary: the body's finally has already run, and the
            // cancellation must not surface to the pipeline tail.
        } catch (e: Throwable) {
            connectionScope.cancel(CancellationException("request handler completion failed", e))
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
            val headers = HttpHeaders.build(base.headers.size + 1) {
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
    // `internal` (not `private`) so [HttpServerHandler]'s per-connection reusable
    // dispatch body can read this call's request head / pipeline context / route
    // resolution / upgrade flag off the in-flight call instead of capturing them
    // in a fresh per-request closure (the L4-big SuspendLambda de-alloc).
    internal val head: HttpRequestHead,
    internal val ctx: PipelineHandlerContext,
    override val queryParameters: QueryParameters,
    override val pathParameters: Map<String, String>,
    /** The route resolution that produced this call, consumed by the dispatch body. */
    internal val resolution: RouteResolution,
    /** True when this request is a protocol upgrade (see [HttpServerHandler.onRequestHead]). */
    internal val isUpgrade: Boolean,
    /**
     * When true, the matched route negotiates on `Accept`, so
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
                // If the consumer is cancelled after this resume is dispatched
                // but before its continuation runs, kotlinx's prompt-cancellation
                // guarantee discards the resumed value — release the pooled chunk
                // in that case so it is not leaked. onCancellation runs iff the
                // value is not delivered, so the delivered path never double-frees.
                waiter.resume(content) { _, _, _ -> content.release() }
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
        val sink = Http1ResponseBodySink(ctx)
        block(sink)
        val end = if (sink.trailers.isEmpty) HttpBodyEnd.EMPTY else HttpBodyEnd(EmptyIoBuf, sink.trailers)
        ctx.propagateWriteAndFlush(end)
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

    // A fresh instance, not the shared HttpHeaders.EMPTY singleton — trailers
    // is a mutable var exposing HttpHeaders.add()/set(), and a caller that
    // mutates in place (idiomatic elsewhere in this codebase, e.g.
    // HttpResponse.contentHeaders()) rather than reassigning would otherwise
    // corrupt the process-wide EMPTY sentinel every other call site relies
    // on as "no headers." Mirrors HttpResponseHead.headers' own default.
    override var trailers: HttpHeaders = HttpHeaders()

    // One wrapper reused across every chunk of this response instead of a
    // fresh HttpBody(chunk) per write (L5-b). Safe because the outbound
    // pipeline dispatch chain (propagateWriteAndFlush -> invokeOnWrite ->
    // handler.onWrite) runs synchronously end-to-end: HttpResponseEncoder /
    // CompressionHandler read `.content` and forward or release the inner
    // IoBuf, never the HttpBody wrapper itself, so nothing retains this
    // instance past the write() call that set it. The sink is per-response
    // and respondStream() drives writes serially, so there is never a
    // concurrent in-flight write to alias.
    private val reusableChunk = ReusableHttpBody()

    override suspend fun write(chunk: IoBuf) {
        // Runs on the handler coroutine, already on the EventLoop thread.
        reusableChunk.content = chunk
        ctx.propagateWriteAndFlush(reusableChunk)
        if (!ctx.channel.isWritable) {
            ctx.channel.awaitFlushComplete()
        }
    }
}

/**
 * [HttpBody] backed by a mutable field instead of a constructor `val`, so
 * [Http1ResponseBodySink] can reuse one instance across every chunk of a
 * streamed response. See [Http1ResponseBodySink.reusableChunk] for the
 * synchronous-dispatch invariant that makes this safe — this type must
 * never be held by a caller across a suspension point or handed to any
 * consumer that retains the wrapper (as opposed to just its [content])
 * beyond a single synchronous pipeline pass.
 */
private class ReusableHttpBody : HttpBody(EmptyIoBuf) {
    override var content: IoBuf = EmptyIoBuf
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
    HttpHeaders.build(this@withConnectionClose.size + 1) {
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
    return HttpHeaders.build(this@withVaryAccept.size + 1) {
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
