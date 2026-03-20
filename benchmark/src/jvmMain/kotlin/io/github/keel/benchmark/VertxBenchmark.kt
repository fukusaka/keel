package io.github.keel.benchmark

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
private val vertxLargePayload = "x".repeat(102_400)

fun startVertx(config: BenchmarkConfig) {
    val s = config.socket
    val vertxOptions = VertxOptions()
    s.threads?.let { vertxOptions.eventLoopPoolSize = it }

    val vertx = Vertx.vertx(vertxOptions)
    val router = Router.router(vertx)

    router.get("/hello").handler { ctx ->
        val response = ctx.response().putHeader("Content-Type", "text/plain")
        if (config.connectionClose) response.putHeader("Connection", "close")
        response.end("Hello, World!")
    }

    router.get("/large").handler { ctx ->
        val response = ctx.response().putHeader("Content-Type", "text/plain")
        if (config.connectionClose) response.putHeader("Connection", "close")
        response.end(vertxLargePayload)
    }

    val v = config.engineConfig as? EngineConfig.Vertx ?: EngineConfig.Vertx()
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
