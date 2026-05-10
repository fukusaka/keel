package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.server.ktor.compression.KeelCompression
import io.github.fukusaka.keel.server.ktor.compression.deflate
import io.github.fukusaka.keel.server.ktor.compression.gzip
import io.ktor.server.application.Application
import io.ktor.server.application.install

/**
 * Native actual: no-op at the Application layer.
 *
 * Native compression is wired in two different places depending on the
 * engine variant, so this shared hook does nothing — each engine starter
 * chooses the appropriate wiring:
 *
 * - **`Keel*` engines** (kqueue / nwconnection / epoll / io-uring) use
 *   keel's codec-http pipeline directly. Compression is wired at the
 *   **engine pipeline level** via
 *   `KeelApplicationEngine.Configuration.pipelineCustomizer` +
 *   [keelNativeCompressionCustomizer]. The pipeline-level
 *   `CompressionHandler` operates on `HttpResponseHead` / `HttpBody`
 *   keel pipeline messages directly, no channel ↔ ByteArray bridge.
 *   See `KeelKqueueEngine`, `KeelNwConnectionEngine`, `KeelEpollEngine`,
 *   `KeelIoUringEngine` for the wiring.
 *
 * - **`KeelCio*` engines** (cio-keel-kqueue / -nwconnection / -epoll /
 *   -io-uring) use ktor-http-cio's raw byte-channel parser which
 *   bypasses keel's pipeline messages. Compression is wired at the
 *   **application layer** via [installKeelCioCompression] which installs
 *   the `KeelCompression` plugin from `keel-server-ktor-base/nativeMain`.
 *
 * Two paths are kept because the engine-pipeline approach is faster
 * (no channel bridge overhead) on `Keel*`, while the application-layer
 * approach is the only option for `KeelCio*` (the pipeline-level
 * `CompressionHandler` cannot intercept ktor-http-cio's wire output).
 *
 * Scenarios that don't request compression (`/hello` / `/large` without
 * `Accept-Encoding`) remain bit-identical to the pre-compression
 * baseline because the negotiation step short-circuits when the client
 * doesn't ask.
 */
@Suppress("UnusedReceiverParameter", "UnusedParameter")
internal actual fun Application.installBenchmarkCompression(enabled: Boolean) {
    // No-op (see KDoc).
}

/**
 * KeelCio*-specific Native compression hook: install the `KeelCompression`
 * plugin (Native counterpart to ktor-server-compression's `Compression`).
 *
 * Called from the 4 `KeelCio*` engine starters' `module { ... }` block
 * because the pipeline-level `CompressionHandler` route used by `Keel*`
 * engines cannot intercept ktor-http-cio's raw byte-channel output.
 *
 * `Keel*` engines do **not** call this — they use `pipelineCustomizer`
 * + [keelNativeCompressionCustomizer] which is faster (no channel ↔
 * ByteArray bridge overhead).
 */
internal fun Application.installKeelCioCompression(enabled: Boolean) {
    if (!enabled) return
    install(KeelCompression) {
        gzip()
        deflate()
    }
}
