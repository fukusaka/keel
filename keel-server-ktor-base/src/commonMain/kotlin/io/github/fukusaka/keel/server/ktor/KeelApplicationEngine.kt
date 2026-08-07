package io.github.fukusaka.keel.server.ktor

import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.SocketOptions
import io.github.fukusaka.keel.core.StreamEngine
import io.github.fukusaka.keel.core.StreamServer
import io.github.fukusaka.keel.logging.guarded
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.server.AcceptBackoff
import io.github.fukusaka.keel.server.TlsServerConfig
import io.github.fukusaka.keel.server.TlsServerInstaller
import io.github.fukusaka.keel.server.acceptLoopWithBackoff
import io.github.fukusaka.keel.server.gracefulShutdown
import io.github.fukusaka.keel.tls.TlsConfig
import io.ktor.events.Events
import io.ktor.events.raiseCatching
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.application.ApplicationStarting
import io.ktor.server.application.ServerReady
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.BaseApplicationEngine
import io.ktor.server.engine.ConnectorType
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.EngineConnectorConfig
import io.ktor.server.engine.withPort
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Codec-agnostic Ktor server engine backed by keel I/O engines.
 *
 * Provides the engine-neutral plumbing (`BaseApplicationEngine` integration,
 * accept loop with backoff, two-phase graceful shutdown, TLS connector
 * configuration) and delegates per-connection HTTP handling to a
 * [KtorConnectionHandler] supplied at construction time by a sibling codec
 * module:
 *
 * - `:keel-server-ktor` injects `KeelCodecConnectionHandler`, which uses
 *   keel's `addHttp1ServerCodec()` from `:keel-codec-http`.
 * - `:keel-server-ktor-cio` (future) injects `KtorCioConnectionHandler`,
 *   which uses `ktor-http-cio`'s `parseRequest`.
 *
 * Both factories produce instances of this same engine class — only the
 * injected handler differs.
 *
 * **Dispatcher model**: every engine exposes a single-threaded
 * [ioDispatcher][io.github.fukusaka.keel.core.Channel.ioDispatcher]
 * that drives its native I/O primitive (epoll / kqueue / io_uring
 * pthread, the NIO Selector thread, a Netty `EventLoop`, a
 * per-connection GCD dispatch queue, or the Node.js event loop).
 * Connection I/O and the HTTP codec always run on that thread. The
 * Ktor application pipeline runs on
 * [Configuration.applicationDispatcher] — null (default) collapses to
 * the channel's `ioDispatcher`, so the entire request flows on one
 * thread with no cross-thread hop.
 *
 * Supports HTTP and HTTPS. HTTPS is enabled per-connector via
 * [Configuration.sslConnector]. HTTP and HTTPS can coexist on different ports.
 *
 * Supports HTTP/1.1 keep-alive: enabled by default, disable via
 * [Configuration.keepAlive].
 */
public class KeelApplicationEngine(
    environment: ApplicationEnvironment,
    monitor: Events,
    developmentMode: Boolean,
    public val configuration: Configuration,
    private val applicationProvider: () -> Application,
    /**
     * Codec-specific per-connection handler. Provided by a sibling codec
     * module's factory (e.g. `Keel` from `:keel-server-ktor` injects
     * `KeelCodecConnectionHandler`).
     */
    private val connectionHandler: KtorConnectionHandler,
) : BaseApplicationEngine(environment, monitor, developmentMode) {

    /**
     * Engine configuration for [KeelApplicationEngine].
     *
     * Extends Ktor's [BaseApplicationEngine.Configuration] with keel-specific
     * settings: I/O engine selection, keep-alive, accept backoff, and TLS
     * connectors via [sslConnector].
     */
    public class Configuration : BaseApplicationEngine.Configuration() {
        /**
         * Required [StreamEngine] instance. Set explicitly (e.g.
         * `engine = NioEngine()`); `start()` throws `IllegalStateException`
         * if left unset.
         */
        public var engine: StreamEngine? = null

        /**
         * Enable HTTP/1.1 keep-alive (default: true). When false, every
         * response includes `Connection: close` and the connection is
         * closed after each request.
         */
        public var keepAlive: Boolean = true

        /**
         * Accept error backoff strategy (default: [AcceptBackoff.Exponential]).
         */
        public var acceptBackoff: AcceptBackoff = AcceptBackoff.Exponential()

        /**
         * Dispatcher for the Ktor application pipeline. When null (default),
         * the pipeline runs on the channel's `ioDispatcher` (the EventLoop
         * thread). Set to e.g. [Dispatchers.Default] to offload at the cost
         * of one cross-thread hop per request.
         */
        public var applicationDispatcher: CoroutineDispatcher? = null

        /**
         * Socket options applied to every accepted client connection.
         *
         * Defaults to [SocketOptions.DEFAULT] (`TCP_NODELAY` enabled).
         * Set `SocketOptions(tcpNoDelay = false)` to re-enable Nagle's
         * algorithm for bulk streaming workloads where batching
         * outweighs per-write latency.
         */
        public var socketOptions: SocketOptions = SocketOptions.DEFAULT

        /**
         * Optional pipeline customizer invoked once per accepted
         * connection, **after** the standard HTTP/1.1 codec stack has
         * been installed and **before** the suspend-bridge handler is
         * appended. The callback receives the freshly-built
         * `PipelinedChannel` and may add custom handlers (compression,
         * tracing, header rewrite, etc.) at the appropriate position.
         *
         * Pipeline shape at the call point (before customizer runs):
         * ```
         *   HEAD ↔ decoder ↔ encoder ↔ TAIL
         * ```
         *
         * After the customizer has appended a "compression" handler:
         * ```
         *   HEAD ↔ decoder ↔ encoder ↔ compression ↔ TAIL
         * ```
         *
         * The bridge is added next, yielding the final shape:
         * ```
         *   HEAD ↔ decoder ↔ encoder ↔ compression ↔ bridge ↔ TAIL
         * ```
         *
         * **Concurrency**: invoked on the EventLoop pinned to the
         * channel — the same thread that drives all subsequent
         * pipeline events. The customizer must not block.
         *
         * **Use case**: opt-in installation of `CompressionHandler`
         * from `keel-codec-http` for Native engines (`ktor-keel-kqueue`
         * / `-nwconnection` / `-epoll` / `-io-uring`) where
         * `ktor-server-compression` is not available. JVM-only consumers
         * typically install Ktor's `Compression` plugin at the
         * application layer instead.
         *
         * **Path semantics**: invoked on both the `Keel` (keel codec-http
         * parser) and `KeelCio` (ktor-http-cio parser) connection-handler
         * paths for API symmetry. However, only the `Keel` path's
         * pipeline carries `HttpResponseHead` / `HttpBody` / `HttpBodyEnd`
         * messages — `CompressionHandler` only intercepts response bytes
         * meaningfully on the `Keel` path. On `KeelCio`, byte-level
         * handlers (tracing / byte-counters / metrics) still work, but
         * compression of cio-parser output requires a different
         * integration point. JVM `KeelCio` consumers should use Ktor's
         * application-level `Compression` plugin instead.
         */
        public var pipelineCustomizer: ((io.github.fukusaka.keel.pipeline.PipelinedChannel) -> Unit)? = null

        /**
         * TLS configuration per connector. Keyed by [EngineConnectorConfig]
         * added via [sslConnector].
         */
        internal val tlsConnectors: MutableMap<EngineConnectorConfig, TlsServerConfig> = mutableMapOf()

        /**
         * Adds an HTTPS connector with keel TLS configuration.
         *
         * Wrap a `TlsCodecFactory` in `TlsCodecServerInstaller` (in
         * `:keel-server`) for keel's `TlsHandler`. Engine-specific
         * installers (e.g., a Netty `SslHandler` adapter) install TLS
         * at the transport level instead.
         */
        public fun sslConnector(
            tlsConfig: TlsConfig,
            tlsInstaller: TlsServerInstaller,
            builder: EngineConnectorBuilder.() -> Unit,
        ) {
            val connector = EngineConnectorBuilder(ConnectorType.HTTPS).apply(builder)
            connectors.add(connector)
            tlsConnectors[connector] = TlsServerConfig(tlsConfig, tlsInstaller)
        }
    }

    /**
     * Returns the running [Application]. Codec handlers use it to build
     * Ktor `ApplicationCall` instances.
     */
    public fun application(): Application = applicationProvider()

    /**
     * keel [io.github.fukusaka.keel.logging.Logger] adapted from Ktor's
     * environment logger. Exposed so [KtorConnectionHandler] implementations
     * in sibling codec modules can log per-connection events without each
     * reconstructing its own [KtorLoggerFactory].
     */
    public val logger: io.github.fukusaka.keel.logging.Logger =
        // Guarded like the engines guard the factory they are configured with:
        // this is Ktor's logger, so a statement made through it is a call into
        // the application's logging setup. Handlers use it to report failures
        // they have already caught, and a throw there would abandon whatever
        // the catch was protecting.
        KtorLoggerFactory(environment.log).guarded().logger("KeelApplicationEngine")
    private val startupJob = CompletableDeferred<Unit>()
    private val stopRequest: CompletableJob = Job()
    private var serverJob: Job = Job()

    // The active I/O engine, resolved lazily when the server job starts.
    // [stopSuspend] reads this field to drive `engine.close()` in its
    // `finally`, so it must outlive the server job.
    private var ioEngine: StreamEngine? = null

    init {
        // Subscribe BEFORE initServerJob() calls applicationProvider(), which fires
        // ApplicationStarting. BaseApplicationEngine.init already subscribed Ktor's
        // installDefaultTransformations (JVM-only multipart), so our subscription
        // comes second — the multipart transformer runs after Ktor's and is a no-op
        // on JVM (subject will already be MultiPartData, not ByteReadChannel).
        monitor.subscribe(ApplicationStarting) {
            it.receivePipeline.installMultipartTransform()
        }
        serverJob = initServerJob()
        serverJob.invokeOnCompletion { cause ->
            cause?.let { stopRequest.completeExceptionally(it) }
            cause?.let { startupJob.completeExceptionally(it) }
        }
    }

    override suspend fun startSuspend(wait: Boolean): ApplicationEngine {
        serverJob.start()
        startupJob.await()
        monitor.raiseCatching(ServerReady, environment, environment.log)
        if (wait) {
            serverJob.join()
        }
        return this
    }

    override fun start(wait: Boolean): ApplicationEngine = runBlocking { startSuspend(wait) }

    override suspend fun stopSuspend(gracePeriodMillis: Long, timeoutMillis: Long) {
        val engine = ioEngine
        if (engine == null) {
            stopRequest.complete()
            return
        }
        gracefulShutdown(serverJob, stopRequest, engine, gracePeriodMillis, timeoutMillis)
    }

    override fun stop(gracePeriodMillis: Long, timeoutMillis: Long): Unit = runBlocking {
        stopSuspend(gracePeriodMillis, timeoutMillis)
    }

    private fun initServerJob(): Job {
        val connectors = configuration.connectors
        val tlsConnectors = configuration.tlsConnectors
        val resolvedDeferred = resolvedConnectorsDeferred

        // Server lifecycle (bind, accept loop, shutdown) uses Dispatchers.Default.
        // These are coordination tasks, not I/O — no need for EventLoop.
        return CoroutineScope(
            applicationProvider().parentCoroutineContext + Dispatchers.Default,
        ).launch(start = CoroutineStart.LAZY) {
            val engine = checkNotNull(configuration.engine) {
                "KeelApplicationEngine.Configuration.engine must be set explicitly " +
                    "(e.g. `engine = NioEngine()`). The Ktor adapter no longer ships " +
                    "a platform default to avoid pulling every keel engine into the " +
                    "classpath."
            }
            ioEngine = engine
            // Pair each server with its connector's TLS config (if any).
            val serverEntries = mutableListOf<Pair<StreamServer, TlsServerConfig?>>()

            val childOpts = configuration.socketOptions
            try {
                val resolved = connectors.map { connector ->
                    val tlsConfig = tlsConnectors[connector]
                    val bindConfig = if (tlsConfig != null) {
                        TlsServerConfig(
                            tls = tlsConfig.tls,
                            installer = tlsConfig.installer,
                            backlog = tlsConfig.backlog,
                            childSocketOptions = childOpts,
                        )
                    } else {
                        BindConfig(childSocketOptions = childOpts)
                    }
                    val server = engine.bind(InetSocketAddress(connector.host, connector.port), bindConfig)
                    serverEntries.add(server to tlsConfig)
                    connector.withPort((server.localAddress as InetSocketAddress).port)
                }
                resolvedDeferred.complete(resolved)
            } catch (cause: Throwable) {
                serverEntries.forEach { (server, _) -> runCatching { server.close() } }
                engine.close()
                startupJob.completeExceptionally(cause)
                throw cause
            }

            startupJob.complete(Unit)

            serverEntries.forEach { (server, tlsConfig) ->
                val scheme = if (tlsConfig != null) "https" else "http"
                launch {
                    server.acceptLoopWithBackoff(configuration.acceptBackoff, logger) { channel ->
                        // Launch on the engine scope (not on this accept loop)
                        // so handlers are children of the engine's SupervisorJob.
                        // [IoEngine.close] cancels and joins them on shutdown,
                        // and [stopSuspend]'s grace phase can wait for them
                        // explicitly via `engine.coroutineContext.job.children`.
                        //
                        // Dispatcher is the channel's EventLoop so read/parse,
                        // HTTP codec, and the Ktor application pipeline all run
                        // on the I/O thread without cross-thread dispatch.
                        engine.launch(channel.ioDispatcher) {
                            connectionHandler.handle(
                                channel = channel as PipelinedChannel,
                                scheme = scheme,
                                engine = this@KeelApplicationEngine,
                                scope = this,
                            )
                        }
                    }
                }
            }

            stopRequest.join()

            serverEntries.forEach { (server, _) -> runCatching { server.close() } }
            // Engine shutdown moved to [stopSuspend]'s finally, so the
            // engine outlives this job and in-flight handlers launched
            // on its scope can be joined during the grace period.
        }
    }
}
