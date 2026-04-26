package io.github.fukusaka.keel.sample

import io.github.fukusaka.keel.engine.nio.NioEngine
import io.github.fukusaka.keel.ktor.Keel
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Minimal Ktor + keel hello world server.
 *
 * The Ktor adapter is engine-neutral; the application picks which keel
 * engine to drive I/O with by setting `engine = ...` in the `configure`
 * block. This sample uses [NioEngine] for cross-platform JVM portability;
 * production deployments may prefer `EpollEngine` (Linux),
 * `KqueueEngine` (macOS), or `NettyEngine` depending on the target.
 *
 * Usage: ./gradlew :sample:run
 */
fun main() {
    embeddedServer(Keel, configure = {
        engine = NioEngine()
        connector { port = 8080 }
    }) {
        routing {
            get("/") {
                call.respondText("Hello from keel!")
            }
        }
    }.start(wait = true)
}
