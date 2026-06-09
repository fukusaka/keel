package io.github.fukusaka.keel.server.http

import io.github.fukusaka.keel.codec.http.HttpHeaderLimitsConfig
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.core.StreamEngine
import io.github.fukusaka.keel.pipeline.PipelinedStreamServer
import io.github.fukusaka.keel.server.ServerConnector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A native HTTP server built on a keel [StreamEngine].
 *
 * [KeelHttpServer] binds a listening socket in Pipeline mode and installs
 * the HTTP/1.1 server codec plus an [HttpServerHandler] dispatch stage on
 * every accepted connection. Each request is resolved through the
 * [Router] supplied at construction time.
 *
 * Construct via the [keelHttpServer] DSL:
 *
 * ```
 * val server = keelHttpServer(engine) {
 *     connector { host = "0.0.0.0"; port = 8080 }
 *     get("/users/:id") { call -> call.respond(HttpResponse.ok(call.pathParameters["id"])) }
 * }
 * server.start()
 * // ...
 * server.stop()
 * ```
 *
 * Each request is resolved through the [Router] supplied at
 * construction time; an unmatched request is answered `404 Not Found`.
 * The supplied [Middleware] chain wraps the dispatch of every request,
 * and a request resolving to an upgrade route is handed to its
 * [UpgradeProtocol].
 *
 * **Lifecycle**: a second [start] while running is rejected; a [stop]
 * while stopped is a no-op. A stopped server can be started again. [stop]
 * shuts down gracefully — see its documentation. The engine itself is
 * owned by the caller and is never closed by [stop].
 */
public class KeelHttpServer internal constructor(
    private val engine: StreamEngine,
    private val connector: ServerConnector,
    private val queryParameterConfig: QueryParameterConfig,
    private val headerLimits: HttpHeaderLimitsConfig,
    private val headerTimeoutMillis: Long,
    private val router: Router,
    private val middlewares: List<Middleware>,
    private val errorHandlers: ErrorHandlers,
    private val compressionConfig: io.github.fukusaka.keel.server.http.dsl.CompressionPipelineConfig? = null,
) {

    /**
     * State of the current run, or null when stopped. [start] installs a
     * fresh instance; [stop] claims it (sets the field back to null) up
     * front and then works only through its captured copy — so a [stop]
     * still draining never corrupts a server that [start] has meanwhile
     * brought back up.
     */
    private var run: ServerRun? = null

    /**
     * The address the server is bound to.
     *
     * @throws IllegalStateException if the server has not been started.
     */
    public val localAddress: SocketAddress
        get() = checkNotNull(run) { "server has not been started" }.server.localAddress

    /** True while the server is bound and accepting connections. */
    public val isActive: Boolean
        get() = run?.server?.isActive == true

    /**
     * Binds the listening socket and begins accepting connections.
     *
     * @throws IllegalStateException if the server is already started.
     */
    public suspend fun start() {
        check(run == null) { "server is already started" }
        val scope = CoroutineScope(engine.coroutineContext + Job(engine.coroutineContext[Job]))
        val connections = ServerConnections()
        val server = engine.bindPipeline(
            connector.address,
            connector.resolveBindConfig(engine),
        ) { channel ->
            channel.installHttpServerPipeline(
                router = router,
                middlewares = middlewares,
                errorHandlers = errorHandlers,
                queryParameterConfig = queryParameterConfig,
                headerLimits = headerLimits,
                headerTimeoutMillis = headerTimeoutMillis,
                scope = scope,
                connections = connections,
                compression = compressionConfig,
            )
        }
        run = ServerRun(server, scope, connections)
    }

    /**
     * Stops the server gracefully with the default [DEFAULT_GRACE_PERIOD_MILLIS]
     * grace period and [DEFAULT_TIMEOUT_MILLIS] total budget.
     */
    public suspend fun stop(): Unit = stop(DEFAULT_GRACE_PERIOD_MILLIS, DEFAULT_TIMEOUT_MILLIS)

    /**
     * Stops the server in three phases:
     *
     * 1. The listening socket is closed — no new connections are accepted.
     *    Every live connection is told to drain: an idle keep-alive
     *    connection is closed at once, an active one finishes its
     *    in-flight request (whose response is tagged `Connection: close`)
     *    and then closes. The drain is awaited for up to [gracePeriodMillis].
     * 2. Connections still draining past the grace period have their
     *    handler coroutines cancelled. The drain is awaited for the
     *    remainder of [timeoutMillis].
     * 3. Anything still open past [timeoutMillis] is force-closed.
     *
     * Passing `gracePeriodMillis = 0` skips phase 1's wait — connections
     * are cancelled and force-closed immediately (an abrupt stop).
     *
     * The caller-owned [StreamEngine] is never closed. Calling [stop] on a
     * server that was never started, or stopping twice, is a no-op. A
     * stopped server can be started again — a [stop] still draining works
     * only through the run it claimed and never disturbs the new run.
     */
    public suspend fun stop(gracePeriodMillis: Long, timeoutMillis: Long) {
        // Claim the run up front: the field goes back to null now, so a
        // concurrent / later stop() is a no-op and a start() may bring a
        // fresh run up while this drain is still in flight.
        val current = run ?: return
        run = null
        // Phase 1: stop accepting, then drain live connections.
        current.server.close()
        val draining = current.connections.snapshot()
        draining.forEach { it.requestDrain() }
        if (gracePeriodMillis > 0) {
            awaitAllClosed(draining, gracePeriodMillis)
        }
        // Phase 2: cancel handlers still running, await the remainder.
        current.scope.cancel()
        val remaining = (timeoutMillis - gracePeriodMillis).coerceAtLeast(0)
        if (remaining > 0) {
            awaitAllClosed(draining, remaining)
        }
        // Phase 3: force-close anything still open. A fresh snapshot also
        // catches a connection that registered after the phase-1 snapshot.
        // The run's registry is discarded with `current`, so no clear.
        current.connections.snapshot().forEach { it.forceClose() }
    }

    /** Awaits every connection's close, bounded by [budgetMillis]. */
    private suspend fun awaitAllClosed(handlers: List<HttpServerHandler>, budgetMillis: Long) {
        withTimeoutOrNull(budgetMillis) {
            coroutineScope {
                handlers.forEach { handler -> launch { handler.awaitClosed() } }
            }
        }
    }

    /**
     * Per-run state — the bound server, the scope its connection handlers
     * run on, and the registry of those connections. A fresh instance is
     * created by each [start]; [stop] captures the live one and discards
     * the field, so its drain cannot outlive into the next run.
     */
    private class ServerRun(
        val server: PipelinedStreamServer,
        val scope: CoroutineScope,
        val connections: ServerConnections,
    )

    private companion object {
        /** Default time to wait for in-flight requests to finish (phase 1). */
        const val DEFAULT_GRACE_PERIOD_MILLIS = 5_000L

        /** Default total shutdown budget before connections are force-closed. */
        const val DEFAULT_TIMEOUT_MILLIS = 30_000L
    }
}
