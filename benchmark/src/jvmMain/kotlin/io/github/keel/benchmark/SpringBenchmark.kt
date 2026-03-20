package io.github.keel.benchmark

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Bean
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.*

/**
 * Spring Boot WebFlux benchmark server.
 *
 * Uses functional routing with Reactor Netty for minimal framework overhead.
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
}

private val springLargePayload = "x".repeat(102_400)

fun startSpring(config: BenchmarkConfig) {
    val sp = config.engineConfig as? EngineConfig.Spring ?: EngineConfig.Spring()
    val props = mutableMapOf<String, Any>(
        "server.port" to config.port.toString(),
    )
    if (config.connectionClose) {
        props["server.netty.idle-timeout"] = "0s"
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
