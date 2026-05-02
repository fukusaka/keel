package io.github.fukusaka.keel.server.ktor

import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.StreamEngine
import io.github.fukusaka.keel.core.StreamServer
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
        KtorLoggerFactory(environment.log).logger("KeelApplicationEngine")
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

            try {
                val resolved = connectors.map { connector ->
                    val tlsConfig = tlsConnectors[connector]
                    val bindConfig = tlsConfig ?: BindConfig()
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
