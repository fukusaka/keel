package io.github.fukusaka.keel.core

/**
 * A server that accepts connections via Pipeline initializer callbacks.
 *
 * Created by [StreamEngine.bindPipeline]. Unlike [Server] which provides
 * [Server.accept] for app-driven connection handling, a [PipelinedServer]
 * delegates connection acceptance to the engine — each accepted connection
 * is configured via the `pipelineInitializer` callback passed to
 * [StreamEngine.bindPipeline].
 *
 * ```
 * val server = engine.bindPipeline("0.0.0.0", 8080) { pipeline ->
 *     pipeline.addLast("tls", TlsHandler(codec))
 *     pipeline.addLast("http", HttpHandler())
 * }
 * println("Listening on ${server.localAddress}")
 * // ... server runs until close
 * server.close()
 * ```
 */
interface PipelinedServer : AutoCloseable {

    /** Local address this server is bound to. */
    val localAddress: SocketAddress

    /** True if the server is listening for connections. */
    val isActive: Boolean

    /** Stops listening and releases resources. */
    override fun close()
}
