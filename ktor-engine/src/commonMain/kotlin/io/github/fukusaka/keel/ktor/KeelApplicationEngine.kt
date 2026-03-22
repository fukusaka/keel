package io.github.fukusaka.keel.ktor

import io.github.fukusaka.keel.codec.http.parseRequestHead
import io.github.fukusaka.keel.core.IoEngine
import io.github.fukusaka.keel.core.ServerChannel
import io.ktor.events.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.util.pipeline.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.io.buffered

/**
 * Ktor server engine backed by keel I/O engines.
 *
 * Phase (a): synchronous I/O, no keep-alive (Connection: close per request).
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
    }

    private val engineDispatcher = Dispatchers.IO
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

        return CoroutineScope(
            applicationProvider().parentCoroutineContext + engineDispatcher
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

    private suspend fun CoroutineScope.acceptLoop(server: ServerChannel) {
        while (server.isActive && isActive) {
            try {
                val channel = server.accept()
                launch(engineDispatcher) {
                    handleConnection(channel)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (!server.isActive || !isActive) break
            }
        }
    }

    /**
     * Handle a single HTTP request on the accepted [channel].
     *
     * Data flow (Phase (a) — single request, no keep-alive):
     * ```
     * Channel ──asSource()──► RawSource ──parseRequestHead()──► HttpRequestHead
     *                              │                                    │
     *                              ▼                                    ▼
     *                   body bytes (pull)                     KeelApplicationCall
     *                              │                                    │
     *                              ▼                                    ▼
     *                     ByteReadChannel ◄── bridge coroutine   Ktor pipeline
     *                                                                   │
     *                                                                   ▼
     *                   Channel ◄──asSink()──◄── writeResponseHead + body
     * ```
     *
     * Request body bridging: keel's pull-based [RawSource] is piped into Ktor's
     * push-based [ByteReadChannel] via a dedicated coroutine. This copy is
     * unavoidable because Ktor expects a channel interface.
     */
    private suspend fun CoroutineScope.handleConnection(channel: io.github.fukusaka.keel.core.Channel) {
        try {
            val source = channel.asSource().buffered()
            val sink = channel.asSink().buffered()

            val head = parseRequestHead(source)

            // Bridge request body: pull from RawSource → push to ByteReadChannel
            val contentLength = head.headers.contentLength()
            val requestBody: ByteReadChannel = if (contentLength != null && contentLength > 0) {
                val bodyChannel = ByteChannel()
                launch(Dispatchers.IO) {
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
            )

            pipeline.execute(call)
        } catch (e: Exception) {
            environment.log.error("Connection handling failed", e)
        } finally {
            runCatching { channel.close() }
        }
    }
}
