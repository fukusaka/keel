package io.github.keel.benchmark

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Shared Ktor routing module for benchmark servers.
 *
 * Provides identical endpoints across all engine configurations
 * so throughput differences reflect only engine overhead.
 */
fun Application.benchmarkModule() {
    routing {
        get("/hello") {
            call.respondText("Hello, World!")
        }
        get("/large") {
            call.respondText(largePayload)
        }
    }
}

/** 100KB text payload, pre-allocated to avoid allocation during benchmarks. */
private val largePayload = "x".repeat(102_400)
