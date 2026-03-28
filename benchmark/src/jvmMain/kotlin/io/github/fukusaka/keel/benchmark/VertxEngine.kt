package io.github.fukusaka.keel.benchmark

import io.vertx.core.Vertx
import io.vertx.core.VertxOptions
import io.vertx.core.http.HttpServerOptions
import io.vertx.ext.web.Router
import java.util.concurrent.CountDownLatch

/**
 * Vert.x HTTP server for benchmarking.
 *
 * Uses Vert.x core + vertx-web Router for routing.
 * Single-verticle deployment for simplicity.
 */

/** Vert.x HttpServerOptions beyond common socket config. */
data class VertxEngineConfig(
    val maxChunkSize: Int? = null,
    val maxHeaderSize: Int? = null,
    val maxInitialLineLength: Int? = null,
    val decoderInitialBufferSize: Int? = null,
    val compressionSupported: Boolean? = null,
    val compressionLevel: Int? = null,
    val idleTimeout: Int? = null,
) : EngineConfig {
    override fun displayTo(sb: StringBuilder, engine: String) {
        val d = HttpServerOptions()
        sb.appendLine("--- Engine-Specific (vertx) ---")
        sb.fmtLine("max-chunk-size:", maxChunkSize?.toString() ?: "${d.maxChunkSize} (default by Vert.x)")
        sb.fmtLine("max-header-size:", maxHeaderSize?.toString() ?: "${d.maxHeaderSize} (default by Vert.x)")
        sb.fmtLine("max-initial-line-len:", maxInitialLineLength?.toString() ?: "${d.maxInitialLineLength} (default by Vert.x)")
        sb.fmtLine("decoder-buf-size:", decoderInitialBufferSize?.toString() ?: "${d.decoderInitialBufferSize} (default by Vert.x)")
        sb.fmtLine("compression:", compressionSupported?.toString() ?: "${d.isCompressionSupported} (default by Vert.x)")
        sb.fmtLine("compression-level:", compressionLevel?.toString() ?: "${d.compressionLevel} (default by Vert.x)")
        sb.fmtLine("connection-idle-timeout:", "${idleTimeout ?: d.idleTimeout} sec${if (idleTimeout == null) " (default by Vert.x)" else ""}")
    }

    override fun toString(): String = buildString {
        maxChunkSize?.let { append("maxChunkSize=$it") }
        compressionSupported?.let { if (isNotEmpty()) append(", "); append("compression=$it") }
    }
}

object VertxEngine : EngineBenchmark {

    private val helloBytes = "Hello, World!".toByteArray()
    private val largeBytes = "x".repeat(LARGE_PAYLOAD_SIZE).toByteArray()

    override fun start(config: BenchmarkConfig) {
        val s = config.socket
        val vertxOptions = VertxOptions()
        s.threads?.let { vertxOptions.eventLoopPoolSize = it }

        val vertx = Vertx.vertx(vertxOptions)
        val router = Router.router(vertx)

        router.get("/hello").handler { ctx ->
            val response = ctx.response().putHeader("Content-Type", "text/plain")
            if (config.connectionClose) response.putHeader("Connection", "close")
            response.end(io.vertx.core.buffer.Buffer.buffer(helloBytes))
        }

        router.get("/large").handler { ctx ->
            val response = ctx.response().putHeader("Content-Type", "text/plain")
            if (config.connectionClose) response.putHeader("Connection", "close")
            response.end(io.vertx.core.buffer.Buffer.buffer(largeBytes))
        }

        val v = config.engineConfig as? VertxEngineConfig ?: VertxEngineConfig()
        val serverOptions = HttpServerOptions()
            .setPort(config.port)
        // Common socket options
        s.tcpNoDelay?.let { serverOptions.setTcpNoDelay(it) }
        s.backlog?.let { serverOptions.setAcceptBacklog(it) }
        s.sendBuffer?.let { serverOptions.setSendBufferSize(it) }
        s.receiveBuffer?.let { serverOptions.setReceiveBufferSize(it) }
        s.reuseAddress?.let { serverOptions.setReuseAddress(it) }
        // Vert.x-specific
        v.maxChunkSize?.let { serverOptions.setMaxChunkSize(it) }
        v.maxHeaderSize?.let { serverOptions.setMaxHeaderSize(it) }
        v.maxInitialLineLength?.let { serverOptions.setMaxInitialLineLength(it) }
        v.decoderInitialBufferSize?.let { serverOptions.setDecoderInitialBufferSize(it) }
        v.compressionSupported?.let { serverOptions.setCompressionSupported(it) }
        v.compressionLevel?.let { serverOptions.setCompressionLevel(it) }
        v.idleTimeout?.let { serverOptions.setIdleTimeout(it) }

        val latch = CountDownLatch(1)
        vertx.createHttpServer(serverOptions)
            .requestHandler(router)
            .listen()
            .onSuccess { server ->
                println("Vert.x server started on port ${server.actualPort()}")
            }
            .onFailure { err ->
                System.err.println("Failed to start Vert.x server: ${err.message}")
                latch.countDown()
            }

        latch.await()
    }

    // Vert.x already sets tcpNoDelay=true, reuseAddress=true
    override fun tunedSocket(s: SocketConfig, cpuCores: Int): SocketConfig = s.copy(
        backlog = s.backlog ?: TUNED_BACKLOG,
        threads = s.threads ?: cpuCores,
    )

    override fun tunedConfig(config: BenchmarkConfig, cpuCores: Int): BenchmarkConfig = config.copy(
        engineConfig = VertxEngineConfig(decoderInitialBufferSize = 256)
    )

    override fun mergeConfig(base: EngineConfig, args: Map<String, String>): EngineConfig {
        val b = base as? VertxEngineConfig ?: VertxEngineConfig()
        return VertxEngineConfig(
            maxChunkSize = args["max-chunk-size"]?.toInt() ?: b.maxChunkSize,
            maxHeaderSize = args["max-header-size"]?.toInt() ?: b.maxHeaderSize,
            maxInitialLineLength = args["max-initial-line-length"]?.toInt() ?: b.maxInitialLineLength,
            decoderInitialBufferSize = args["decoder-initial-buffer-size"]?.toInt() ?: b.decoderInitialBufferSize,
            compressionSupported = args["compression-supported"]?.toBooleanStrict() ?: b.compressionSupported,
            compressionLevel = args["compression-level"]?.toInt() ?: b.compressionLevel,
            idleTimeout = args["connection-idle-timeout"]?.toInt() ?: b.idleTimeout,
        )
    }

    override fun socketDefaults(os: OsSocketDefaults): SocketConfig.SocketDefaults {
        val cpuCores = availableProcessors()
        val vertxDefaults = HttpServerOptions()
        return SocketConfig.SocketDefaults(
            tcpNoDelay = "${vertxDefaults.isTcpNoDelay} (default by Vert.x)",
            reuseAddress = "${vertxDefaults.isReuseAddress} (default by Vert.x)",
            backlog = "${vertxDefaults.acceptBacklog} (default by Vert.x)",
            sendBuffer = if (vertxDefaults.sendBufferSize > 0) "${vertxDefaults.sendBufferSize} bytes (default by Vert.x)" else "${os.sendBuffer} bytes (default by OS)",
            receiveBuffer = if (vertxDefaults.receiveBufferSize > 0) "${vertxDefaults.receiveBufferSize} bytes (default by Vert.x)" else "${os.receiveBuffer} bytes (default by OS)",
            threads = "$cpuCores (default by Vert.x, eventLoopPoolSize)",
        )
    }
}
