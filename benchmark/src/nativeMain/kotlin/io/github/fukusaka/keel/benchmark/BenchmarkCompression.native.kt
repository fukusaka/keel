package io.github.fukusaka.keel.benchmark

import io.ktor.server.application.Application

/**
 * Native actual: no-op at the Application layer.
 *
 * `ktor-server-compression` is JVM-only, so Native Ktor adapters cannot
 * install the Compression plugin at the Application level via
 * `Application.install(Compression)`. Native `ktor-keel-*` engines wire
 * compression at the **engine pipeline level** instead, via
 * `KeelApplicationEngine.Configuration.pipelineCustomizer` +
 * [keelNativeCompressionCustomizer]. See `KeelKqueueEngine`,
 * `KeelNwConnectionEngine`, `KeelEpollEngine`, `KeelIoUringEngine`
 * for the wiring.
 *
 * The `KeelCio*` variants use ktor-http-cio's raw byte-channel parser
 * which bypasses keel's pipeline messages — Native compression for
 * those engines is a separate integration (`plan.md` `Compression
 * backend 拡張` follow-up).
 *
 * Scenarios that don't request compression (`/hello` / `/large` without
 * `Accept-Encoding`) remain bit-identical to the pre-compression
 * baseline because the negotiation step short-circuits when the client
 * doesn't ask.
 */
@Suppress("UnusedReceiverParameter", "UnusedParameter")
internal actual fun Application.installBenchmarkCompression(enabled: Boolean) {
    // No-op (see KDoc). Engine-level wiring lives in each Native
    // ktor-keel-* EngineBenchmark's start() via pipelineCustomizer.
}
