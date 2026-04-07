package io.github.fukusaka.keel.ktor

import io.github.fukusaka.keel.codec.http.HttpEofException
import io.github.fukusaka.keel.codec.http.HttpHeaderName
import io.github.fukusaka.keel.codec.http.HttpHeaders
import io.github.fukusaka.keel.codec.http.HttpParseException
import io.github.fukusaka.keel.codec.http.HttpStatus
import io.github.fukusaka.keel.codec.http.HttpVersion
import io.github.fukusaka.keel.codec.http.parseRequestHead
import io.github.fukusaka.keel.codec.http.writeResponseHead
import io.github.fukusaka.keel.core.Channel
import io.github.fukusaka.keel.core.Server
import io.github.fukusaka.keel.core.StreamEngine
import io.github.fukusaka.keel.io.BufferedSuspendSink
import io.github.fukusaka.keel.logging.error
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.tls.TlsCodecFactory
import io.github.fukusaka.keel.tls.TlsConfig
import io.github.fukusaka.keel.tls.TlsConnectorConfig
import io.github.fukusaka.keel.tls.TlsHandler
import io.github.fukusaka.keel.tls.TlsInstaller
import io.ktor.events.Events
import io.ktor.events.raiseCatching
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.application.ServerReady
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.BaseApplicationEngine
import io.ktor.server.engine.ConnectorType
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.EngineConnectorConfig
import io.ktor.server.engine.withPort
import io.ktor.util.pipeline.execute
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.ContinuationInterceptor

/**
 * Ktor server engine backed by keel I/O engines.
 *
 * **Dispatcher model**: Connection I/O (read/parse) runs on the channel's
 * [coroutineDispatcher][io.github.fukusaka.keel.core.Channel.coroutineDispatcher]
 * (EventLoop thread for kqueue/epoll/NIO), while the Ktor application
 * pipeline is offloaded to [Dispatchers.Default] to avoid blocking the
 * EventLoop with user code. For engines without a dedicated EventLoop
 * (Netty, NWConnection, Node.js), both use [Dispatchers.Default].
 *
 * Supports HTTP and HTTPS. HTTPS is enabled per-connector via
 * [Configuration.sslConnector], which installs a [TlsHandler] in the
 * channel pipeline after accept. HTTP and HTTPS can coexist on different ports.
 *
 * Supports HTTP/1.1 keep-alive: multiple requests can be processed on a
 * single TCP connection. Keep-alive is enabled by default and can be
 * disabled via [Configuration.keepAlive].
 */
public class KeelApplicationEngine(
    environment: ApplicationEnvironment,
    monitor: Events,
    developmentMode: Boolean,
    public val configuration: Configuration,
    private val applicationProvider: () -> Application,
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
         * Explicit [StreamEngine] instance. When null, the platform default is used
         * (JVM: NioEngine, macOS: KqueueEngine).
         */
        public var engine: StreamEngine? = null

        /**
         * Enable HTTP/1.1 keep-alive (default: true).
         *
         * When true, multiple requests are processed on a single TCP connection
         * (per HTTP/1.1 standard). The connection is closed when the client sends
         * `Connection: close` or an error occurs.
         *
         * When false, every response includes `Connection: close` and the
         * connection is closed after each request (Phase (a) behavior).
         */
        public var keepAlive: Boolean = true

        /**
         * Accept error backoff strategy (default: [AcceptBackoff.Exponential]).
         *
         * When `server.accept()` fails (e.g. EMFILE — too many open files),
         * the accept loop pauses before retrying to avoid CPU spin.
         *
         * - [AcceptBackoff.Fixed]: constant delay between retries
         * - [AcceptBackoff.Exponential]: doubles delay on each consecutive
         *   failure, resets on success (default: 100ms initial, 1s max)
         */
        public var acceptBackoff: AcceptBackoff = AcceptBackoff.Exponential()

        /**
         * TLS configuration per connector.
         *
         * Keyed by the [EngineConnectorConfig] instance added to the
         * [connectors][BaseApplicationEngine.Configuration.connectors] list
         * by [sslConnector]. Connectors without an entry use plain HTTP.
         */
        internal val tlsConnectors: MutableMap<EngineConnectorConfig, TlsConnectorConfig> = mutableMapOf()

        /**
         * Adds an HTTPS connector with keel TLS configuration.
         *
         * Uses keel's PEM-based [TlsConfig] instead of Ktor's JVM-only
         * `KeyStore`-based `sslConnector`. Works on all KMP targets.
         *
         * By default, installs keel's [TlsHandler] in the pipeline. Pass a
         * [TlsInstaller] to override with an engine-specific implementation
         * (e.g., Netty's `SslHandler` via `NettySslInstaller` in `:engine-netty`).
         *
         * ```
         * // Default: keel TlsHandler (all engines)
         * embeddedServer(Keel) {
         *     sslConnector(tlsConfig, JsseTlsCodecFactory()) { port = 8443 }
         * }
         *
         * // Netty SslHandler (requires :engine-netty dependency)
         * embeddedServer(Keel) {
         *     engine = NettyEngine(IoEngineConfig())
         *     sslConnector(tlsConfig, JsseTlsCodecFactory(), NettySslInstaller()) { port = 8443 }
         * }
         * ```
         *
         * @param tlsConfig TLS settings (certificates, trust, verify mode).
         * @param tlsCodecFactory Factory for per-connection TlsCodec instances.
         *        Ignored when [tlsInstaller] is set.
         * @param tlsInstaller Custom TLS installer. null = keel TlsHandler (default).
         * @param builder Connector builder for host/port configuration.
         */
        public fun sslConnector(
            tlsConfig: TlsConfig,
            tlsCodecFactory: TlsCodecFactory,
            tlsInstaller: TlsInstaller? = null,
            builder: EngineConnectorBuilder.() -> Unit,
        ) {
            val connector = EngineConnectorBuilder(ConnectorType.HTTPS).apply(builder)
            connectors.add(connector)
            tlsConnectors[connector] = TlsConnectorConfig(tlsConfig, tlsCodecFactory, tlsInstaller)
        }
    }

    /**
     * Accept error backoff strategy.
     *
     * Controls how long the accept loop waits after a failed `accept()`.
     * Prevents CPU spin when accept fails repeatedly (e.g. EMFILE).
     */
    public sealed class AcceptBackoff {
        /**
         * Fixed delay between retries.
         *
         * @param delayMs delay in milliseconds (default: 100ms)
         */
        public data class Fixed(val delayMs: Long = 100L) : AcceptBackoff()

        /**
         * Exponential backoff: doubles delay on each consecutive failure,
         * resets to [initialMs] on success.
         *
         * @param initialMs initial delay in milliseconds (default: 100ms)
         * @param maxMs maximum delay in milliseconds (default: 1000ms)
         */
        public data class Exponential(
            val initialMs: Long = 100L,
            val maxMs: Long = 1000L,
        ) : AcceptBackoff()
    }

    // Ktor pipeline runs on the channel's EventLoop dispatcher (same as
    // Netty's model). All processing — codec, routing, response write —
    // stays on the EventLoop thread, eliminating context switches.
    // User code that performs blocking I/O should use withContext(Dispatchers.IO).
    private val logger = KtorLoggerFactory(environment.log).logger("KeelApplicationEngine")
    private val startupJob = CompletableDeferred<Unit>()
    private val stopRequest: CompletableJob = Job()
    private var serverJob: Job = Job()

    init {
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
        stopRequest.complete()
        val result = withTimeoutOrNull(gracePeriodMillis) {
            serverJob.join()
            true
        }
        if (result == null) {
            serverJob.cancel()
            withTimeoutOrNull(timeoutMillis - gracePeriodMillis) {
                serverJob.join()
            }
        }
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
            val ioEngine = configuration.engine ?: defaultEngine()
            // Pair each server with its connector's TLS config (if any).
            val serverEntries = mutableListOf<Pair<Server, TlsConnectorConfig?>>()

            try {
                val resolved = connectors.map { connector ->
                    val server = ioEngine.bind(connector.host, connector.port)
                    serverEntries.add(server to tlsConnectors[connector])
                    connector.withPort(server.localAddress.port)
                }
                resolvedDeferred.complete(resolved)
            } catch (cause: Throwable) {
                serverEntries.forEach { (server, _) -> runCatching { server.close() } }
                ioEngine.close()
                startupJob.completeExceptionally(cause)
                throw cause
            }

            startupJob.complete(Unit)

            serverEntries.forEach { (server, tlsConfig) ->
                launch { acceptLoop(server, tlsConfig) }
            }

            stopRequest.join()

            serverEntries.forEach { (server, _) -> runCatching { server.close() } }
            ioEngine.close()
        }
    }

    /**
     * Accepts connections in a loop with configurable error backoff.
     *
     * On accept failure (e.g. EMFILE — too many open files), the loop
     * pauses according to [Configuration.acceptBackoff] to prevent
     * CPU spin. The delay resets on a successful accept (exponential
     * backoff mode).
     *
     * @param tlsConfig TLS configuration for this connector, or null for plain HTTP.
     */
    private suspend fun CoroutineScope.acceptLoop(
        server: Server,
        tlsConfig: TlsConnectorConfig?,
    ) {
        val scheme = if (tlsConfig != null) "https" else "http"
        var currentDelayMs = when (val b = configuration.acceptBackoff) {
            is AcceptBackoff.Fixed -> b.delayMs
            is AcceptBackoff.Exponential -> b.initialMs
        }

        while (server.isActive && isActive) {
            try {
                val channel = server.accept()
                // Reset backoff on successful accept
                currentDelayMs = when (val b = configuration.acceptBackoff) {
                    is AcceptBackoff.Fixed -> b.delayMs
                    is AcceptBackoff.Exponential -> b.initialMs
                }

                // Install TLS in the channel's pipeline for HTTPS.
                // All engines return PipelinedChannel from accept().
                if (tlsConfig != null) {
                    val pipelinedChannel = channel as PipelinedChannel
                    val installer = tlsConfig.installer
                    if (installer != null) {
                        // Engine-specific TLS (e.g., Netty SslHandler).
                        installer.install(pipelinedChannel, tlsConfig.config)
                    } else {
                        // Default: keel TlsHandler in the pipeline.
                        val codec = tlsConfig.codecFactory.createServerCodec(tlsConfig.config)
                        pipelinedChannel.pipeline.addLast("tls", TlsHandler(codec))
                    }
                }

                // Dispatch on EventLoop so read/parse runs on the I/O
                // thread without cross-thread dispatch. For engines
                // without a dedicated EventLoop (Netty, NWConnection,
                // Node.js), this falls back to Dispatchers.Default.
                launch(channel.coroutineDispatcher) {
                    handleConnection(channel, scheme)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (!server.isActive || !isActive) break
                // Backoff before retrying to prevent CPU spin on
                // persistent errors (e.g. EMFILE, fd exhaustion).
                logger.error(e) { "Accept failed, retrying in ${currentDelayMs}ms" }
                delay(currentDelayMs)
                // Advance exponential backoff
                if (configuration.acceptBackoff is AcceptBackoff.Exponential) {
                    val exp = configuration.acceptBackoff as AcceptBackoff.Exponential
                    currentDelayMs = (currentDelayMs * 2).coerceAtMost(exp.maxMs)
                }
            }
        }
    }

    /**
     * Handle HTTP requests on the accepted [channel].
     *
     * When keep-alive is enabled, processes multiple sequential requests
     * on the same TCP connection until the client sends `Connection: close`,
     * an error occurs, or the connection is closed by the peer.
     *
     * **Dispatcher model**: I/O runs on [channel.coroutineDispatcher][io.github.fukusaka.keel.core.Channel.coroutineDispatcher]
     * (EventLoop). The Ktor pipeline runs on [channel.appDispatcher][io.github.fukusaka.keel.core.Channel.appDispatcher]:
     * - Native (kqueue/epoll): EventLoop — zero context switches (Netty model)
     * - JVM NIO: Dispatchers.Default — ForkJoinPool work-stealing
     *
     * ```
     * [EventLoop]     parseRequestHead(source)   read → zero-copy
     * [EventLoop]     launch { body bridge }      source.read → EventLoop
     * [appDispatcher] pipeline.execute(call)      routing + response write
     * [EventLoop]     bodyBridgeJob?.join()        next request
     * ```
     *
     * Uses [BufferedSuspendSource]/[BufferedSuspendSink] for zero-copy I/O:
     * no kotlinx-io Buffer intermediary, no runBlocking.
     *
     * Request body bridging: keel's [BufferedSuspendSource] is piped into Ktor's
     * push-based [ByteReadChannel] via a dedicated coroutine. This copy is
     * unavoidable because Ktor expects a channel interface. The bridge job is
     * joined before parsing the next request to ensure body bytes are fully
     * consumed from the source.
     */
    private suspend fun CoroutineScope.handleConnection(channel: Channel, scheme: String = "http") {
        // PipelinedChannel uses push-mode BufferedSuspendSource via SuspendBridgeHandler
        // (zero-copy readOwned). Other channels fall back to pull-mode (1 copy).
        val source = channel.asBufferedSuspendSource()
        val sink = BufferedSuspendSink(channel.asSuspendSink(), channel.allocator, channel.supportsDeferredFlush)
        try {
            val serverKeepAlive = configuration.keepAlive

            // Reusable byte array for request body bridging. Allocated once per
            // connection instead of per request to reduce GC pressure.
            val bodyBridgeBuf = ByteArray(BODY_BRIDGE_BUFFER_SIZE)

            while (channel.isActive) {
                val head = try {
                    parseRequestHead(source)
                } catch (_: HttpEofException) {
                    break // client closed connection
                } catch (_: HttpParseException) {
                    respondBadRequest(sink)
                    break
                }

                val keepAlive = serverKeepAlive && head.isKeepAlive

                // Bridge request body: pull from BufferedSuspendSource → push to ByteReadChannel.
                // The bridge coroutine reads exactly contentLength bytes from source
                // and pipes them into bodyChannel for Ktor's push-based API.
                val contentLength = head.headers.contentLength
                var bodyBridgeJob: Job? = null
                val requestBody: ByteReadChannel = if (contentLength != null && contentLength > 0) {
                    val bodyChannel = ByteChannel()
                    bodyBridgeJob = launch {
                        var remaining = contentLength
                        val buf = bodyBridgeBuf
                        while (remaining > 0) {
                            val toRead = minOf(remaining, buf.size.toLong()).toInt()
                            val n = source.readAtMostTo(buf, 0, toRead)
                            if (n == -1) break
                            bodyChannel.writeFully(buf, 0, n)
                            remaining -= n
                        }
                        bodyChannel.flushAndClose()
                    }
                    bodyChannel
                } else {
                    ByteReadChannel.Empty
                }

                val call = KeelApplicationCall(
                    application = applicationProvider(),
                    head = head,
                    localAddress = channel.localAddress,
                    remoteAddress = channel.remoteAddress,
                    requestBody = requestBody,
                    sink = sink,
                    scope = this,
                    coroutineContext = coroutineContext,
                    keepAlive = keepAlive,
                    scheme = scheme,
                )

                // Run the Ktor pipeline on channel.appDispatcher.
                // Native engines (kqueue/epoll): EventLoop — zero context switches.
                // JVM NIO: Dispatchers.Default — ForkJoinPool work-stealing.
                val appCtx = channel.appDispatcher
                if (appCtx !== coroutineContext[ContinuationInterceptor]) {
                    withContext(appCtx) { pipeline.execute(call) }
                } else {
                    pipeline.execute(call)
                }

                if (!keepAlive) break

                // Ensure the body bridge coroutine has fully consumed the request
                // body from the source before parsing the next request. Without
                // this, leftover body bytes would be misinterpreted as the next
                // request line.
                bodyBridgeJob?.join()
            }
        } catch (e: Exception) {
            if (e !is CancellationException) {
                logger.error(e) { "Connection handling failed" }
            }
        } finally {
            runCatching { source.close() }
            runCatching { sink.close() }
            runCatching { channel.close() }
        }
    }

    /**
     * Sends an HTTP 400 Bad Request response before closing the connection.
     *
     * Uses HTTP/1.0 to avoid implying keep-alive support, following the same
     * approach as Ktor CIO's error response handling.
     */
    private suspend fun respondBadRequest(sink: BufferedSuspendSink) {
        try {
            val headers = HttpHeaders()
            headers.add(HttpHeaderName.CONNECTION, "close")
            headers.add(HttpHeaderName.CONTENT_LENGTH, "0")
            writeResponseHead(HttpStatus.BAD_REQUEST, HttpVersion.HTTP_1_0, headers, sink)
            sink.flush()
        } catch (_: Exception) {
            // Best-effort: client may have already disconnected
        }
    }

    private companion object {
        /** Buffer size for request body bridging (pull → push). */
        private const val BODY_BRIDGE_BUFFER_SIZE = 8192
    }
}
