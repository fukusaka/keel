package io.github.fukusaka.keel.testing.engine

import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.pipeline.PipelinedStreamServer

/**
 * In-memory listener registered by [InMemoryEngine.bindPipeline].
 *
 * Holds the bind [config] and the `pipelineInitializer` so that every
 * [InMemoryEngine.connect] to [localAddress] can build a server-side
 * channel configured exactly like a real accepted connection: the
 * engine applies [BindConfig.initializeConnection] first, then runs the
 * `pipelineInitializer`.
 *
 * Unlike a real server there is no accept loop — connections are created
 * synchronously inside [InMemoryEngine.connect].
 *
 * @param onClose invoked by [close] so the owning engine can drop this
 *   listener from its registry.
 */
internal class InMemoryPipelinedStreamServer(
    override val localAddress: SocketAddress,
    val config: BindConfig,
    val pipelineInitializer: (PipelinedChannel) -> Unit,
    private val onClose: () -> Unit,
) : PipelinedStreamServer {

    private var closed = false

    override val isActive: Boolean get() = !closed

    /**
     * Configures [channel] as the server side of a freshly accepted
     * in-memory connection: [BindConfig.initializeConnection] runs first
     * (per-connection transport setup), then the `pipelineInitializer`.
     */
    fun accept(channel: PipelinedChannel) {
        config.initializeConnection(channel)
        pipelineInitializer(channel)
    }

    /** Stops the listener and removes it from the engine registry. Idempotent. */
    override fun close() {
        if (closed) return
        closed = true
        onClose()
    }
}
