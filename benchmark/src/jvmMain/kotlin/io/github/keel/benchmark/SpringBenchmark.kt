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

fun startSpring(port: Int) {
    val app = SpringApplication(SpringBenchmarkApp::class.java)
    app.setDefaultProperties(mapOf("server.port" to port.toString()))
    app.run()
}
