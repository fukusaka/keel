package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.core.StreamEngine
import io.github.fukusaka.keel.core.StreamServer

/**
 * A server that accepts connections via Pipeline initializer callbacks.
 *
 * Created by [StreamEngine.bindPipeline]. Unlike [StreamServer] which provides
 * [StreamServer.accept] for app-driven connection handling, a [PipelinedStreamServer]
 * delegates connection acceptance to the engine — each accepted connection
 * is configured via the `pipelineInitializer` callback passed to
 * [StreamEngine.bindPipeline].
 *
 * ```
 * val server = engine.bindPipeline("0.0.0.0", 8080, config = tlsBindConfig) { channel ->
 *     channel.pipeline.addLast("http", HttpHandler())
 * }
 * println("Listening on ${server.localAddress}")
 * // ... server runs until close
 * server.close()
 * ```
 */
interface PipelinedStreamServer : AutoCloseable {

    /**
     * Local address this server is bound to. A multi-address server
     * (created by the list-taking [StreamEngine.bindPipeline] overload)
     * reports its first address in bind order; [localAddresses] carries
     * them all.
     */
    val localAddress: SocketAddress

    /**
     * The addresses this server is currently bound to, in bind order.
     * Single-address servers (the default) report `[localAddress]`.
     */
    val localAddresses: List<SocketAddress> get() = listOf(localAddress)

    /** True if the server is listening for connections. */
    val isActive: Boolean

    /** Stops listening and releases resources. */
    override fun close()
}
