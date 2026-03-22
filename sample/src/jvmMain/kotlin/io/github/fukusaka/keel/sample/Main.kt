package io.github.fukusaka.keel.sample

import io.github.fukusaka.keel.ktor.Keel
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Minimal Ktor + keel hello world server.
 *
 * Usage: ./gradlew :sample:run
 */
fun main() {
    embeddedServer(Keel, port = 8080) {
        routing {
            get("/") {
                call.respondText("Hello from keel!")
            }
        }
    }.start(wait = true)
}
