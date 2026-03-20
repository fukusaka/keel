package io.github.keel.benchmark

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
 * which is set by [startSpring] before launching the application.
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

private val springLargePayload = "x".repeat(102_400)

fun startSpring(config: BenchmarkConfig) {
    val sp = config.engineConfig as? EngineConfig.Spring ?: EngineConfig.Spring()
    val props = mutableMapOf<String, Any>(
        "server.port" to config.port.toString(),
    )
    if (config.connectionClose) {
        props["server.netty.idle-timeout"] = "0s"
        System.setProperty("benchmark.connection-close", "true")
    }
    // Common socket → Spring properties
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
