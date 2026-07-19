package io.github.fukusaka.keel.core

import io.github.fukusaka.keel.pipeline.PipelinedChannel
import kotlinx.coroutines.withContext

/**
 * Opens a Pipeline-mode connection to [address] and configures it with
 * [pipelineInitializer] — the client-side counterpart of
 * [StreamEngine.bindPipeline].
 *
 * [connect]s with [config], requires the resulting [Channel] to be a
 * [PipelinedChannel] (closing it and throwing otherwise), then runs
 * [pipelineInitializer] on the channel's EventLoop thread (via
 * [PipelinedChannel.ioDispatcher], where all pipeline mutation must happen).
 * The initializer performs the full channel setup — installing the codec,
 * adding handlers, and enabling reads — exactly as a [StreamEngine.bindPipeline]
 * initializer does for each accepted connection:
 *
 * ```kotlin
 * val channel = engine.connectPipeline(host, port) {
 *     it.addHttp1ClientCodec()
 *     it.pipeline.addLast("bridge", suspendMessageBridge<HttpResponse>())
 *     it.readEnabled = true
 * }
 * ```
 *
 * If [pipelineInitializer] throws, the channel is closed before the throw
 * propagates, so a half-configured connection does not leak.
 *
 * @param address remote endpoint (same semantics as [connect]).
 * @param config per-connect configuration (socket options, etc.). `null`
 *   (default) uses the plain [connect] — the common path, supported by every
 *   engine; a non-null config opts into [connect] with [ConnectConfig], which
 *   only engines that support socket options accept.
 * @param pipelineInitializer configures the just-opened channel; runs on the
 *   EventLoop thread.
 * @return the configured [PipelinedChannel].
 * @throws UnsupportedOperationException if the engine's [connect] does not
 *   return a [PipelinedChannel], or if a non-null [config] carries socket
 *   options the engine does not support.
 */
public suspend fun StreamEngine.connectPipeline(
    address: SocketAddress,
    config: ConnectConfig? = null,
    pipelineInitializer: (PipelinedChannel) -> Unit,
): PipelinedChannel {
    val channel = if (config == null) connect(address) else connect(address, config)
    val pipelined = channel as? PipelinedChannel ?: run {
        channel.close()
        throw UnsupportedOperationException(
            "connectPipeline requires a PipelinedChannel connection; " +
                "got ${channel::class.simpleName} from ${this::class.simpleName}",
        )
    }
    try {
        withContext(pipelined.ioDispatcher) { pipelineInitializer(pipelined) }
    } catch (t: Throwable) {
        pipelined.close()
        throw t
    }
    return pipelined
}

/** Convenience overload: pipeline-mode connect to `host:port`. */
public suspend fun StreamEngine.connectPipeline(
    host: String,
    port: Int,
    config: ConnectConfig? = null,
    pipelineInitializer: (PipelinedChannel) -> Unit,
): PipelinedChannel = connectPipeline(InetSocketAddress(host, port), config, pipelineInitializer)
