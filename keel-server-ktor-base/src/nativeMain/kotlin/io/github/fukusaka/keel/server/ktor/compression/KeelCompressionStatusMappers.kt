package io.github.fukusaka.keel.server.ktor.compression

import io.github.fukusaka.keel.codec.http.RequestDecompressionLimitException
import io.github.fukusaka.keel.codec.http.UnknownEncodingPolicy
import io.github.fukusaka.keel.codec.http.UnsupportedContentEncodingException
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.response.respond

/**
 * Opt-in [StatusPagesConfig] mapper that converts the inbound
 * compression-related exceptions thrown by [KeelCompression] (Native
 * plugin) **and** keel-codec-http's [io.github.fukusaka.keel.codec.http.HttpRequestDecompressionHandler]
 * into the appropriate HTTP status codes:
 *
 * | Exception                              | HTTP status |
 * |----------------------------------------|-------------|
 * | [RequestDecompressionLimitException]   | 413 Payload Too Large |
 * | [UnsupportedContentEncodingException] (`policy = UnsupportedMediaType`) | 415 Unsupported Media Type |
 * | [UnsupportedContentEncodingException] (`policy = BadRequest`)            | 400 Bad Request |
 *
 * `Passthrough` policy never throws so it is never mapped here; it is
 * unreachable and we do not emit a status for it.
 *
 * Usage:
 *
 * ```kotlin
 * embeddedServer(KeelCio, port = 8080) {
 *     install(StatusPages) { installKeelCompressionStatusMappers() }
 *     install(KeelCompression) { gzip(); deflate() }
 *     routing { ... }
 * }
 * ```
 *
 * Opt-in (rather than auto-installed by [KeelCompression]) follows the
 * Ktor convention of letting users compose plugins explicitly. The two
 * exception types live in `keel-codec-http` so a single mapper covers
 * both the Ktor plugin path and the `HttpRequestDecompressionHandler`
 * codec path.
 *
 * **Test strategy**: no standalone contract test. The mapping is registered as
 * `StatusPagesConfig` exception handlers, only reachable by throwing the
 * exceptions through an installed `StatusPages` plugin — which needs a Ktor
 * test application (`ktor-server-test-host`, not a dependency of this module).
 * The exceptions themselves are unit-tested where they are raised
 * (`keel-codec-http`'s `HttpRequestDecompressionLimitsTest`), and the mapping
 * is exercised through the engine integration tests that install this mapper.
 */
public fun StatusPagesConfig.installKeelCompressionStatusMappers() {
    exception<RequestDecompressionLimitException> { call, cause ->
        call.respond(HttpStatusCode.PayloadTooLarge, cause.message ?: "")
    }
    exception<UnsupportedContentEncodingException> { call, cause ->
        val status = when (cause.policy) {
            UnknownEncodingPolicy.UnsupportedMediaType -> HttpStatusCode.UnsupportedMediaType
            UnknownEncodingPolicy.BadRequest -> HttpStatusCode.BadRequest
            // Passthrough does not throw — fall back to a defensive 500
            // so the contract is total even if a future code path raises.
            UnknownEncodingPolicy.Passthrough -> HttpStatusCode.InternalServerError
        }
        call.respond(status, cause.message ?: "")
    }
}
