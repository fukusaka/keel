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
    val props = mutableMapOf<String, Any>(
        "server.port" to config.port.toString(),
    )
    if (config.connectionClose) {
        // Reactor Netty does not have a direct "connection close" setting,
        // but we can set idle timeout to 0 to force close after each response.
        props["server.netty.idle-timeout"] = "0s"
    }
    config.threads?.let {
        props["reactor.netty.ioWorkerCount"] = it.toString()
    }

    val app = SpringApplication(SpringBenchmarkApp::class.java)
    app.setDefaultProperties(props)
    app.run()
}
