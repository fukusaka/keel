package io.github.fukusaka.keel.benchmark

import io.ktor.server.application.Application

/**
 * Native actual: no-op.
 *
 * `ktor-server-compression` only publishes a JVM artefact, so Native
 * Ktor adapters (`ktor-keel-kqueue` / `-nwconnection` / `-epoll` /
 * `-io-uring`) cannot install the Compression plugin from common code.
 * The bench's `compression` scenario surfaces the gap on the leaderboard
 * as a missing `Content-Encoding` check failure rather than silently
 * reporting wire-uncompressed throughput as a "compression" data point.
 */
@Suppress("UnusedReceiverParameter", "UnusedParameter")
internal actual fun Application.installBenchmarkCompression(enabled: Boolean) {
    // No-op (see KDoc).
}
