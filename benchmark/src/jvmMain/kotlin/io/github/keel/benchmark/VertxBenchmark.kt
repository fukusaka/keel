package io.github.keel.benchmark

import io.vertx.core.Vertx
import io.vertx.ext.web.Router
import java.util.concurrent.CountDownLatch

/**
 * Vert.x HTTP server for benchmarking.
 *
 * Uses Vert.x core + vertx-web Router for routing.
 * Single-verticle deployment for simplicity.
 */
private val vertxLargePayload = "x".repeat(102_400)

fun startVertx(port: Int) {
    val vertx = Vertx.vertx()
    val router = Router.router(vertx)

    router.get("/hello").handler { ctx ->
        ctx.response()
            .putHeader("Content-Type", "text/plain")
            .end("Hello, World!")
    }

    router.get("/large").handler { ctx ->
        ctx.response()
            .putHeader("Content-Type", "text/plain")
            .end(vertxLargePayload)
    }

    val latch = CountDownLatch(1)
    vertx.createHttpServer()
        .requestHandler(router)
        .listen(port)
        .onSuccess { server ->
            println("Vert.x server started on port ${server.actualPort()}")
        }
        .onFailure { err ->
            System.err.println("Failed to start Vert.x server: ${err.message}")
            latch.countDown()
        }

    latch.await()
}
