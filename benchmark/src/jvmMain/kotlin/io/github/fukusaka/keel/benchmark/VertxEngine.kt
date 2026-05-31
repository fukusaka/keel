package io.github.fukusaka.keel.benchmark

import io.vertx.core.Vertx
import io.vertx.core.VertxOptions
import io.vertx.core.http.HttpServerOptions
import io.vertx.core.net.PemKeyCertOptions
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
    private val uploadAckBytes = "ok".toByteArray()

    override fun start(config: BenchmarkConfig): () -> Unit {
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

        router.post("/upload-stream").handler { ctx ->
            // Stream the request body via Vert.x's per-chunk handler — no
            // memory aggregation. Counts bytes, replies after `endHandler`
            // with `X-Bytes-Received` matching the client's payload size.
            var received = 0L
            val req = ctx.request()
            req.handler { chunk -> received += chunk.length() }
            req.endHandler {
                val response = ctx.response().putHeader("Content-Type", "text/plain")
                if (config.connectionClose) response.putHeader("Connection", "close")
                response.putHeader("X-Bytes-Received", received.toString())
                response.end(io.vertx.core.buffer.Buffer.buffer(uploadAckBytes))
            }
            req.exceptionHandler { ctx.fail(it) }
        }

        // Multipart parsing requires Vert.x's BodyHandler — register only on
        // this route so the rest of the bench surface keeps the streaming
        // pattern (BodyHandler buffers the request body).
        router.post("/multipart-upload")
            .handler(io.vertx.ext.web.handler.BodyHandler.create())
            .handler { ctx ->
                val uploads = ctx.fileUploads()
                val partCount = uploads.size
                val totalBytes = uploads.sumOf { it.size() }
                val response = ctx.response().putHeader("Content-Type", "text/plain")
                if (config.connectionClose) response.putHeader("Connection", "close")
                response.putHeader("X-Parts-Received", partCount.toString())
                response.putHeader("X-Bytes-Received", totalBytes.toString())
                response.end(io.vertx.core.buffer.Buffer.buffer(uploadAckBytes))
            }

        // Method-mix endpoint: register one handler per supported method.
        listOf(
            io.vertx.core.http.HttpMethod.GET,
            io.vertx.core.http.HttpMethod.POST,
            io.vertx.core.http.HttpMethod.PUT,
            io.vertx.core.http.HttpMethod.DELETE,
            io.vertx.core.http.HttpMethod.PATCH,
            io.vertx.core.http.HttpMethod.HEAD,
            io.vertx.core.http.HttpMethod.OPTIONS,
        ).forEach { method ->
            router.route(method, "/method-echo").handler { ctx ->
                val response = ctx.response().putHeader("Content-Type", "text/plain")
                if (config.connectionClose) response.putHeader("Connection", "close")
                response.putHeader("X-Echo-Method", ctx.request().method().name())
                response.end(io.vertx.core.buffer.Buffer.buffer(uploadAckBytes))
            }
        }
        // Path-parameter endpoint: GET /items/:id replies with X-Item-Id.
        router.get("/items/:id").handler { ctx ->
            val id = ctx.pathParam("id")
            val response = ctx.response().putHeader("Content-Type", "text/plain")
            if (config.connectionClose) response.putHeader("Connection", "close")
            response.putHeader("X-Item-Id", id)
            response.end(io.vertx.core.buffer.Buffer.buffer(uploadAckBytes))
        }

        // Vertx SSE — `response.write(frame)` × N + `response.end()`.
        // Audit during PR #442 measured the `vertx` SSE row at
        // ~2× the per-frame keel ceiling, not the 3-4× inflation that
        // `netty-raw` / `zig-bench` / `rust-bench` showed before their
        // fixes — so Vertx's HttpServerResponse already delivers chunks
        // close to per-frame on its own. There is no public per-write
        // flush API on Vertx HttpServerResponse without blocking the
        // EventLoop on `Future<Void>`, and the residual coalescing is
        // framework-internal (does not affect any keel engine row).
        // Change only if a future measurement shows Vertx drifting
        // away from the keel ceiling.
        router.get("/sse-stream").handler { ctx ->
            val count = ctx.request().getParam("count")?.toIntOrNull() ?: BENCHMARK_SSE_DEFAULT_COUNT
            val size = ctx.request().getParam("size")?.toIntOrNull() ?: BENCHMARK_SSE_DEFAULT_SIZE
            val frame = io.vertx.core.buffer.Buffer.buffer("data: ${"x".repeat(size)}\n\n")
            val response = ctx.response()
                .putHeader("Content-Type", "text/event-stream")
                .setChunked(true)
            if (config.connectionClose) response.putHeader("Connection", "close")
            for (i in 0 until count) {
                response.write(frame)
            }
            response.end()
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
        // Top-level --compression overrides the engine-specific knob: setting
        // it true at the BenchmarkConfig layer is the bench harness's "all
        // engines on" signal, while engine-specific config keeps a finer
        // dial for tuning runs (different compression levels, etc.).
        when {
            config.compression -> serverOptions.setCompressionSupported(true)
            v.compressionSupported != null -> serverOptions.setCompressionSupported(v.compressionSupported)
        }
        v.compressionLevel?.let { serverOptions.setCompressionLevel(it) }
        v.idleTimeout?.let { serverOptions.setIdleTimeout(it) }
        if (config.tls != null) {
            serverOptions.setSsl(true)
            serverOptions.setKeyCertOptions(
                PemKeyCertOptions()
                    .setCertPath(BENCHMARK_CERT_PATH)
                    .setKeyPath(BENCHMARK_KEY_PATH),
            )
        }

        val latch = CountDownLatch(1)
        val httpServer = vertx.createHttpServer(serverOptions)
            .requestHandler(router)
            // WebSocket echo: bind at server level so the upgrade is handled
            // before the route dispatcher runs. Use Vert.x's
            // textMessageHandler / binaryMessageHandler — these auto-reassemble
            // RFC 6455 fragmented messages (text fin=0 → continuation × N →
            // continuation fin=1) into a complete message before invoking the
            // handler. The lower-level frameHandler exposes individual frames
            // including continuations, which would force per-engine fragment
            // reassembly logic for the bench.
            .webSocketHandler { ws ->
                if (ws.path() == "/ws-echo") {
                    ws.textMessageHandler { msg -> ws.writeFinalTextFrame(msg) }
                    ws.binaryMessageHandler { buf -> ws.writeFinalBinaryFrame(buf) }
                    ws.exceptionHandler { ws.close() }
                } else {
                    ws.close()
                }
            }
        httpServer
            .listen()
            .onSuccess { server ->
                println("Vert.x server started on port ${server.actualPort()}")
                latch.countDown()
            }
            .onFailure { err ->
                System.err.println("Failed to start Vert.x server: ${err.message}")
                latch.countDown()
            }

        latch.await()
        return { vertx.close() }
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
