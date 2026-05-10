package io.github.fukusaka.keel.server.ktor.compression

import io.github.fukusaka.keel.codec.http.RequestDecompressionLimitException
import io.github.fukusaka.keel.codec.http.UnknownEncodingPolicy
import io.github.fukusaka.keel.codec.http.UnsupportedContentEncodingException
import io.ktor.http.HeaderValue
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.compressed
import io.ktor.http.parseHeaderValue
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.Hook
import io.ktor.server.application.PipelineCall
import io.ktor.server.application.RouteScopedPlugin
import io.ktor.server.application.call
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.request.acceptEncoding
import io.ktor.server.response.ApplicationSendPipeline
import io.ktor.util.logging.KtorSimpleLogger
import io.ktor.util.pipeline.PipelineContext
import io.ktor.utils.io.ByteReadChannel

/**
 * Native-only Ktor plugin providing **both** response compression and
 * request body decompression, backed by `keel-compression-zlib`.
 *
 * Closes the Native KeelCio* gap: ktor-server-compression is JVM-only,
 * and on Native ktor's stock `GZipEncoder` / `DeflateEncoder` are
 * identity-only no-op stubs. With this plugin installed, a Native ktor
 * server (e.g. backed by KeelCio) honours `Accept-Encoding` on incoming
 * requests, emits compressed `Content-Encoding: gzip` / `deflate`
 * response payloads, **and** decodes `Content-Encoding`-tagged request
 * bodies before they reach the route handler.
 *
 * ## API parity with ktor-server-compression
 *
 * The API surface mirrors `io.ktor.server.plugins.compression.Compression`
 * including the `mode = Mode.All` (default) / `Mode.CompressResponse` /
 * `Mode.DecompressRequest` knob, so users can write the same DSL on
 * Native:
 *
 * ```kotlin
 * embeddedServer(KeelCio, port = 8080) {
 *     install(KeelCompression) {
 *         gzip()
 *         deflate()
 *         minimumSize(1024)
 *         // Inbound zip-bomb defence (defaults shown):
 *         decompressionLimit = 1L * 1024 * 1024  // 1 MiB
 *         ratioLimit = 100
 *         ratioBurst = 3
 *     }
 *     routing { get("/") { call.respondText("hello") } }
 * }
 * ```
 *
 * ## Inbound (request) side
 *
 * Hooks `onCallReceive` to decode the request body when `Content-Encoding`
 * is present and the encoding is registered via [KeelCompressionConfig.encoders].
 * Strips `Content-Encoding` + `Content-Length` from the request head so
 * downstream handlers see a plain body.
 *
 * Limit enforcement (zip-bomb defence) mirrors keel-codec-http's
 * [io.github.fukusaka.keel.codec.http.HttpRequestDecompressionHandler]:
 *
 * - Absolute decoded-byte cap ([KeelCompressionConfig.decompressionLimit])
 *   → [RequestDecompressionLimitException] with reason `AbsoluteSizeExceeded`.
 * - Decoded:input ratio cap ([KeelCompressionConfig.ratioLimit] +
 *   [KeelCompressionConfig.ratioBurst]) → [RequestDecompressionLimitException]
 *   with reason `RatioExceeded`.
 *
 * Both exception types are shared with `keel-codec-http`'s handler so a
 * single status-page mapper covers both invocation paths. The plugin
 * itself does **not** convert exceptions to HTTP status codes; callers
 * opt in via `install(StatusPages) { installKeelCompressionStatusMappers() }`.
 *
 * ## JVM
 *
 * On JVM continue to use `install(Compression)` from ktor-server-compression
 * — this plugin is `nativeMain`-only and does not appear in the JVM
 * source set. ktor's stock `Compression(mode = Mode.All)` already covers
 * both directions on JVM.
 *
 * @see KeelCompressionConfig
 * @see KeelGZipEncoder
 * @see KeelDeflateEncoder
 */
public val KeelCompression: RouteScopedPlugin<KeelCompressionConfig> = createRouteScopedPlugin(
    name = "KeelCompression",
    createConfiguration = ::KeelCompressionConfig,
) {
    if (pluginConfig.encoders.none()) {
        pluginConfig.default()
    }
    val options = pluginConfig.buildOptions()

    on(KeelContentEncodingHook) { call ->
        if (!options.mode.response) return@on
        encode(call, options)
    }
    onCallReceive { call ->
        if (!options.mode.request) return@onCallReceive
        decode(call, options)
    }
}

private val LOGGER = KtorSimpleLogger("io.github.fukusaka.keel.server.ktor.compression.KeelCompression")

/**
 * Internal hook that intercepts [ApplicationSendPipeline.ContentEncoding]
 * and exposes a `transformBody { ... }` method to the handler.
 *
 * Mirrors ktor-server-compression's `ContentEncoding` hook. Reused here
 * so the plugin's body reads identically.
 */
internal object KeelContentEncodingHook : Hook<suspend KeelContentEncodingHook.Context.(PipelineCall) -> Unit> {

    class Context(private val pipelineContext: PipelineContext<Any, PipelineCall>) {
        fun transformBody(block: (OutgoingContent) -> OutgoingContent?) {
            val transformed = block(pipelineContext.subject as OutgoingContent)
            if (transformed != null) {
                pipelineContext.subject = transformed
            }
        }
    }

    override fun install(
        pipeline: ApplicationCallPipeline,
        handler: suspend Context.(PipelineCall) -> Unit,
    ) {
        pipeline.sendPipeline.intercept(ApplicationSendPipeline.ContentEncoding) {
            handler(Context(this), call)
        }
    }
}

/**
 * Negotiate an encoder based on the request's `Accept-Encoding` and
 * encoder priorities, then transform the response body via
 * [OutgoingContent.compressed].
 *
 * Mirrors ktor-server-compression's response encode path. The header
 * rewrite (`Content-Encoding`, `Vary`, `Content-Length` strip) happens
 * automatically inside `compressed()` — keel does not duplicate that
 * logic.
 */
private fun KeelContentEncodingHook.Context.encode(
    call: PipelineCall,
    options: KeelCompressionOptions,
) {
    val acceptEncodingRaw = call.request.acceptEncoding()
    if (acceptEncodingRaw == null) {
        LOGGER.trace("Skip compression for ${call.request.local.uri}: no Accept-Encoding")
        return
    }

    val comparator = compareBy<Pair<KeelCompressionEncoderConfig, HeaderValue>>(
        { it.second.quality },
        { it.first.priority },
    ).reversed()

    val encoders = parseHeaderValue(acceptEncodingRaw)
        .filter { it.value == "*" || it.value in options.encoders }
        .flatMap { header ->
            when (header.value) {
                "*" -> options.encoders.values.map { it to header }
                else -> options.encoders[header.value]?.let { listOf(it to header) } ?: emptyList()
            }
        }
        .sortedWith(comparator)
        .map { it.first }

    if (encoders.isEmpty()) {
        LOGGER.trace("Skip compression for ${call.request.local.uri}: no matching encoder")
        return
    }

    transformBody { message ->
        if (options.conditions.any { !it.invoke(call, message) }) {
            LOGGER.trace("Skip compression for ${call.request.local.uri}: global preconditions not met")
            return@transformBody null
        }
        if (message.headers[HttpHeaders.ContentEncoding] != null) {
            LOGGER.trace("Skip compression for ${call.request.local.uri}: already encoded")
            return@transformBody null
        }
        val chosen = encoders.firstOrNull { e -> e.conditions.all { it.invoke(call, message) } }
        if (chosen == null) {
            LOGGER.trace("Skip compression for ${call.request.local.uri}: no encoder satisfies conditions")
            return@transformBody null
        }
        LOGGER.trace("Encoding response for ${call.request.local.uri} with ${chosen.encoder.name}")
        // Pass the call's coroutineContext so the encoder's GlobalScope.writer
        // job inherits the call's Job as parent. When the call is cancelled
        // (client disconnect, server shutdown, route timeout), the encoder
        // pump receives CancellationException and runs its finally{} cleanup
        // (session.close + IoBuf.release) instead of leaking as an orphan
        // GlobalScope coroutine.
        message.compressed(chosen.encoder, call.coroutineContext)
    }
}

/**
 * Inbound (request body) decompression — mirrors ktor-server-compression's
 * decode path with the dual-gate zip-bomb defence wired in.
 *
 * Looks up the registered encoder by `Content-Encoding` token, strips
 * `Content-Encoding` + `Content-Length` from the request head, and
 * replaces the request body with a decoder-wrapped channel. For
 * [KeelContentEncoder] (the keel-compression-zlib backed adapters) the
 * underlying [io.github.fukusaka.keel.compression.Decoder] is driven
 * directly so the configured limits + burst tracking apply. For any
 * other [io.ktor.util.ContentEncoder] (custom user-registered) the
 * default unbounded `decode` path is used with a warn log — the keel
 * SPI is not visible to those.
 */
@OptIn(io.ktor.utils.io.InternalAPI::class)
private suspend fun io.ktor.server.application.OnCallReceiveContext<KeelCompressionConfig>.decode(
    call: PipelineCall,
    options: KeelCompressionOptions,
) {
    val raw = call.request.headers[HttpHeaders.ContentEncoding] ?: return
    val tokens = parseHeaderValue(raw).map { it.value.lowercase() }.filter { it.isNotEmpty() }
    if (tokens.isEmpty() || tokens.all { it == "identity" }) return
    // Reject multi-value Content-Encoding for now — chained decoders are
    // an explicit follow-up (RFC 9110 §8.4 allows them; keel mirrors the
    // codec-http handler's "single value" stance for v1).
    if (tokens.size > 1) {
        LOGGER.trace(
            "Multi-value Content-Encoding '$raw' on ${call.request.local.uri}: " +
                "applying unknown-encoding policy (chained decoders are a future extension)",
        )
        applyUnknownEncodingPolicy(raw, options.unknownEncodingPolicy)
        return
    }
    // After the guards above we know `tokens.size == 1` and the single
    // token is not "identity" (the all-identity branch has already
    // returned).
    val token = tokens.single()
    val encoderConfig = options.encoders[token] ?: run {
        LOGGER.trace("Unknown Content-Encoding '$token' on ${call.request.local.uri}")
        applyUnknownEncodingPolicy(token, options.unknownEncodingPolicy)
        return
    }
    LOGGER.trace("Decoding request body for ${call.request.local.uri} with encoding '$token'")
    // Strip Content-Encoding + Content-Length from the request head so
    // downstream handlers see the request as if uncompressed (decoded
    // length is unknown until the decoder emits FINISHED).
    call.request.setHeader(HttpHeaders.ContentEncoding, null)
    call.request.setHeader(HttpHeaders.ContentLength, null)
    transformBody { body ->
        wrapDecodingChannel(encoderConfig.encoder, body, options, call.coroutineContext)
    }
}

private fun applyUnknownEncodingPolicy(token: String, policy: UnknownEncodingPolicy) {
    when (policy) {
        UnknownEncodingPolicy.Passthrough -> {
            // Forward body untouched; route handler is responsible for
            // inspecting Content-Encoding and decoding (or rejecting).
        }
        UnknownEncodingPolicy.UnsupportedMediaType,
        UnknownEncodingPolicy.BadRequest,
        -> throw UnsupportedContentEncodingException(token, policy)
    }
}

/**
 * Wrap [body] in a decoder-driven [ByteReadChannel]. When [encoder] is a
 * [KeelContentEncoder], drive the underlying keel SPI directly with
 * configured limits + burst tracking. Otherwise fall back to ktor's
 * stock [io.ktor.util.ContentEncoder.decode] with a warn log (no limit
 * enforcement is possible through that interface).
 */
private fun wrapDecodingChannel(
    encoder: io.ktor.util.ContentEncoder,
    body: ByteReadChannel,
    options: KeelCompressionOptions,
    coroutineContext: kotlin.coroutines.CoroutineContext,
): ByteReadChannel = if (encoder is KeelContentEncoder) {
    keelDecodeWithLimits(
        decoder = encoder.keelDecoder,
        source = body,
        decompressionLimit = options.decompressionLimit,
        ratioLimit = options.ratioLimit,
        ratioBurst = options.ratioBurst,
        coroutineContext = coroutineContext,
    )
} else {
    LOGGER.warn(
        "Encoder '${encoder.name}' is not a KeelContentEncoder; " +
            "decompression limits are NOT enforced — caller responsibility.",
    )
    encoder.decode(body, coroutineContext)
}
