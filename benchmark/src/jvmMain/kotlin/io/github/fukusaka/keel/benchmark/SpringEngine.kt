package io.github.fukusaka.keel.benchmark

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.*
import org.springframework.web.server.WebFilter

/**
 * Spring Boot WebFlux benchmark server.
 *
 * Uses functional routing with Reactor Netty for minimal framework overhead.
 * Connection: close is controlled via the `benchmark.connection-close` system property,
 * which is set by [SpringEngine.start] before launching the application.
 */
@SpringBootApplication
open class SpringBenchmarkApp {

    @Bean
    open fun routes(): RouterFunction<ServerResponse> = router {
        GET("/hello") {
            ServerResponse.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .bodyValue("Hello, World!")
        }
        GET("/large") {
            ServerResponse.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .bodyValue(springLargePayload)
        }
    }

    /** Add Connection: close header to all responses when enabled. */
    @Bean
    open fun connectionCloseFilter(): WebFilter = WebFilter { exchange, chain ->
        if (System.getProperty("benchmark.connection-close") == "true") {
            exchange.response.headers.set(HttpHeaders.CONNECTION, "close")
        }
        chain.filter(exchange)
    }
}

private val springLargePayload = "x".repeat(LARGE_PAYLOAD_SIZE)

/** Spring Boot WebFlux / Reactor Netty settings. */
data class SpringEngineConfig(
    val maxKeepAliveRequests: Int? = null,
    val maxChunkSize: Int? = null,
    val maxInitialLineLength: Int? = null,
    val validateHeaders: Boolean? = null,
    val maxInMemorySize: Int? = null,
) : EngineConfig {
    override fun displayTo(sb: StringBuilder, engine: String) {
        val nettyMaxChunk = io.netty.handler.codec.http.HttpObjectDecoder.DEFAULT_MAX_CHUNK_SIZE
        val nettyMaxInitLine = io.netty.handler.codec.http.HttpObjectDecoder.DEFAULT_MAX_INITIAL_LINE_LENGTH
        val nettyValidateHeaders = io.netty.handler.codec.http.HttpObjectDecoder.DEFAULT_VALIDATE_HEADERS
        sb.appendLine("--- Engine-Specific (spring) ---")
        sb.fmtLine("max-keep-alive-req:", maxKeepAliveRequests?.toString() ?: "unlimited (default by Spring)")
        sb.fmtLine("max-chunk-size:", maxChunkSize?.toString() ?: "$nettyMaxChunk (default by Netty)")
        sb.fmtLine("max-initial-line-len:", maxInitialLineLength?.toString() ?: "$nettyMaxInitLine (default by Netty)")
        sb.fmtLine("validate-headers:", validateHeaders?.toString() ?: "$nettyValidateHeaders (default by Netty)")
        val springMaxInMemory = 256 * 1024
        sb.fmtLine("max-in-memory-size:", maxInMemorySize?.let { "$it bytes" } ?: "$springMaxInMemory bytes (default by Spring)")
    }

    override fun toString(): String = buildString {
        maxKeepAliveRequests?.let { append("maxKeepAliveRequests=$it") }
        validateHeaders?.let { if (isNotEmpty()) append(", "); append("validateHeaders=$it") }
    }
}

object SpringEngine : EngineBenchmark {

    override fun start(config: BenchmarkConfig) {
        val sp = config.engineConfig as? SpringEngineConfig ?: SpringEngineConfig()
        val props = mutableMapOf<String, Any>(
            "server.port" to config.port.toString(),
        )
        if (config.connectionClose) {
            props["server.netty.idle-timeout"] = "0s"
            System.setProperty("benchmark.connection-close", "true")
        }
        // Common socket -> Spring properties
        config.socket.threads?.let {
            props["reactor.netty.ioWorkerCount"] = it.toString()
        }
        // Spring-specific
        sp.maxKeepAliveRequests?.let {
            props["server.netty.max-keep-alive-requests"] = it.toString()
        }
        sp.maxChunkSize?.let {
            props["server.netty.max-chunk-size"] = it.toString()
        }
        sp.maxInitialLineLength?.let {
            props["server.netty.max-initial-line-length"] = it.toString()
        }
        sp.validateHeaders?.let {
            props["server.netty.validate-headers"] = it.toString()
        }
        sp.maxInMemorySize?.let {
            props["spring.codec.max-in-memory-size"] = it.toString()
        }

        val app = SpringApplication(SpringBenchmarkApp::class.java)
        app.setDefaultProperties(props)
        app.run()
    }

    // Reactor Netty already sets tcpNoDelay=true, reuseAddress=true
    override fun tunedSocket(s: SocketConfig, cpuCores: Int): SocketConfig = s.copy(
        backlog = s.backlog ?: TUNED_BACKLOG,
        threads = s.threads ?: cpuCores,
    )

    override fun tunedConfig(config: BenchmarkConfig, cpuCores: Int): BenchmarkConfig = config.copy(
        engineConfig = SpringEngineConfig(validateHeaders = false)
    )

    override fun mergeConfig(base: EngineConfig, args: Map<String, String>): EngineConfig {
        val b = base as? SpringEngineConfig ?: SpringEngineConfig()
        return SpringEngineConfig(
            maxKeepAliveRequests = args["max-keep-alive-requests"]?.toInt() ?: b.maxKeepAliveRequests,
            maxChunkSize = args["max-chunk-size"]?.toInt() ?: b.maxChunkSize,
            maxInitialLineLength = args["max-initial-line-length"]?.toInt() ?: b.maxInitialLineLength,
            validateHeaders = args["validate-headers"]?.toBooleanStrict() ?: b.validateHeaders,
            maxInMemorySize = args["max-in-memory-size"]?.toInt() ?: b.maxInMemorySize,
        )
    }

    override fun socketDefaults(os: OsSocketDefaults): SocketConfig.SocketDefaults {
        val cpuCores = availableProcessors()
        val reactorNettyThreads = System.getProperty("reactor.netty.ioWorkerCount")?.toIntOrNull()
            ?: cpuCores
        return SocketConfig.SocketDefaults(
            tcpNoDelay = "true (default by Reactor Netty)",
            reuseAddress = "true (default by Reactor Netty)",
            backlog = "${os.backlog} (default by OS, estimated)",
            sendBuffer = "${os.sendBuffer} bytes (default by OS)",
            receiveBuffer = "${os.receiveBuffer} bytes (default by OS)",
            threads = "$reactorNettyThreads (default by Reactor Netty, ioWorkerCount)",
        )
    }
}
