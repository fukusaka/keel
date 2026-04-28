package io.github.fukusaka.keel.benchmark

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.deflate
import io.ktor.server.plugins.compression.gzip

/**
 * JVM actual: install gzip + deflate via Ktor's Compression plugin.
 *
 * The plugin negotiates `Accept-Encoding` per request and falls through
 * to an uncompressed response when the client doesn't ask for one — so
 * other bench scenarios (`/hello`, `/large` without `Accept-Encoding`)
 * remain bit-identical to the pre-compression baseline.
 */
internal actual fun Application.installBenchmarkCompression(enabled: Boolean) {
    if (!enabled) return
    install(Compression) {
        gzip()
        deflate()
    }
}
