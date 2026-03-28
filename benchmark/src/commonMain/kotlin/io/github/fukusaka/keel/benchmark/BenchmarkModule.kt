package io.github.fukusaka.keel.benchmark

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Shared Ktor routing module for benchmark servers.
 *
 * Provides identical endpoints across all engine configurations
 * so throughput differences reflect only engine overhead.
 *
 * @param connectionClose if true, add `Connection: close` header to force
 *   per-request TCP connections (used by keel-equiv profile)
 */
fun Application.benchmarkModule(connectionClose: Boolean = false) {
    if (connectionClose) {
        intercept(ApplicationCallPipeline.Plugins) {
            call.response.headers.append("Connection", "close")
        }
    }
    routing {
        get("/hello") {
            call.respondBytes(helloPayloadBytes, ContentType.Text.Plain)
        }
        get("/large") {
            call.respondBytes(largePayloadBytes, ContentType.Text.Plain)
        }
    }
}

/** Size of the /large endpoint payload (100KB). */
const val LARGE_PAYLOAD_SIZE = 102_400

/** 100KB text payload, pre-allocated to avoid allocation during benchmarks. */
private val largePayload = "x".repeat(LARGE_PAYLOAD_SIZE)

/** Pre-encoded payloads to measure pure I/O performance without per-request String.encodeToByteArray(). */
private val helloPayloadBytes = "Hello, World!".encodeToByteArray()
private val largePayloadBytes = largePayload.encodeToByteArray()
