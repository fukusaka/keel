package io.github.fukusaka.keel.ktor

import io.github.fukusaka.keel.codec.http.parseRequestHead
import io.github.fukusaka.keel.io.BufferedSuspendSink
import io.github.fukusaka.keel.io.BufferedSuspendSource
import io.github.fukusaka.keel.core.IoEngine
import io.github.fukusaka.keel.core.ServerChannel
import io.ktor.events.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.util.pipeline.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*

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

    public class Configuration : BaseApplicationEngine.Configuration() {
        /**
         * Explicit [IoEngine] instance. When null, the platform default is used
         * (JVM: NioEngine, macOS: KqueueEngine).
         */
        public var engine: IoEngine? = null

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

    // Application pipeline dispatcher. Uses Dispatchers.Default (thread pool)
    // so user code in routing {} blocks doesn't block the I/O EventLoop.
    // Channel I/O is dispatched on channel.coroutineDispatcher instead.
    private val appDispatcher = Dispatchers.Default
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
        val resolvedDeferred = resolvedConnectorsDeferred

        // Server lifecycle (bind, accept loop, shutdown) uses appDispatcher.
        // These are coordination tasks, not I/O — no need for EventLoop.
        return CoroutineScope(
            applicationProvider().parentCoroutineContext + appDispatcher
        ).launch(start = CoroutineStart.LAZY) {
            val ioEngine = configuration.engine ?: defaultEngine()
            val servers = mutableListOf<ServerChannel>()

            try {
                val resolved = connectors.map { connector ->
                    val server = ioEngine.bind(connector.host, connector.port)
                    servers.add(server)
                    connector.withPort(server.localAddress.port)
                }
                resolvedDeferred.complete(resolved)
            } catch (cause: Throwable) {
                servers.forEach { runCatching { it.close() } }
                ioEngine.close()
                startupJob.completeExceptionally(cause)
                throw cause
            }

            startupJob.complete(Unit)

            servers.forEach { server ->
                launch { acceptLoop(server) }
            }

            stopRequest.join()

            servers.forEach { runCatching { it.close() } }
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
     */
    private suspend fun CoroutineScope.acceptLoop(server: ServerChannel) {
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
                // Dispatch on EventLoop so read/parse runs on the I/O
                // thread without cross-thread dispatch. For engines
                // without a dedicated EventLoop (Netty, NWConnection,
                // Node.js), this falls back to Dispatchers.Default.
                launch(channel.coroutineDispatcher) {
                    handleConnection(channel)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (!server.isActive || !isActive) break
                // Backoff before retrying to prevent CPU spin on
                // persistent errors (e.g. EMFILE, fd exhaustion).
                environment.log.error("Accept failed, retrying in ${currentDelayMs}ms", e)
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
     * **Dispatcher model**: This method runs on [channel.coroutineDispatcher][io.github.fukusaka.keel.core.Channel.coroutineDispatcher]
     * (EventLoop thread for kqueue/epoll/NIO, Dispatchers.Default for others).
     * I/O operations (read/parse) execute directly on the EventLoop without
     * cross-thread dispatch. The Ktor pipeline is offloaded to [appDispatcher]
     * (thread pool) so user routing code doesn't block the EventLoop.
     *
     * ```
     * [EventLoop]  parseRequestHead(source)          read → zero-copy
     * [EventLoop]  launch { body bridge }             source.read → EventLoop
     * [Default]    withContext { pipeline.execute() }  user code + response write
     * [EventLoop]  bodyBridgeJob?.join()               next request
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
    private suspend fun CoroutineScope.handleConnection(channel: io.github.fukusaka.keel.core.Channel) {
        val source = BufferedSuspendSource(channel.asSuspendSource(), channel.allocator)
        val sink = BufferedSuspendSink(channel.asSuspendSink(), channel.allocator)
        try {
            val serverKeepAlive = configuration.keepAlive

            while (channel.isActive) {
                // parseRequestHead throws IllegalArgumentException on EOF
                // ("Unexpected EOF reading request line") and on malformed
                // requests. Both cases mean the connection should be closed.
                val head = try {
                    parseRequestHead(source)
                } catch (_: Exception) {
                    break
                }

                val keepAlive = serverKeepAlive && head.isKeepAlive()

                // Bridge request body: pull from BufferedSuspendSource → push to ByteReadChannel.
                // The bridge coroutine reads exactly contentLength bytes from source
                // and pipes them into bodyChannel for Ktor's push-based API.
                val contentLength = head.headers.contentLength()
                var bodyBridgeJob: Job? = null
                val requestBody: ByteReadChannel = if (contentLength != null && contentLength > 0) {
                    val bodyChannel = ByteChannel()
                    bodyBridgeJob = launch {
                        var remaining = contentLength
                        val buf = ByteArray(8192)
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
                )

                // Run the Ktor pipeline on appDispatcher (thread pool)
                // so user routing code doesn't block the EventLoop.
                // For engines without a dedicated EventLoop, both
                // dispatchers are Dispatchers.Default → no-op switch.
                withContext(appDispatcher) {
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
            if (e !is kotlinx.coroutines.CancellationException) {
                environment.log.error("Connection handling failed", e)
            }
        } finally {
            runCatching { source.close() }
            runCatching { sink.close() }
            runCatching { channel.close() }
        }
    }
}
