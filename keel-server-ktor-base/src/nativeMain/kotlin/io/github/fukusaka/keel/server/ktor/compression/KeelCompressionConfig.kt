package io.github.fukusaka.keel.server.ktor.compression

import io.github.fukusaka.keel.codec.http.UnknownEncodingPolicy
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.ApplicationCall
import io.ktor.util.ContentEncoder
import io.ktor.utils.io.KtorDsl

/**
 * Configuration for [KeelCompressionPlugin].
 *
 * Mirrors ktor-server-compression's `CompressionConfig` API surface so
 * Native users can reuse the same DSL idioms (`gzip()`, `deflate()`,
 * `condition { ... }`, `minimumSize(...)`, `matchContentType(...)`,
 * `excludeContentType(...)`) they would write on the JVM.
 *
 * Like ktor's plugin, this single config covers **both directions**:
 *
 * - **Outbound** response compression — driven by [encoders] +
 *   [conditions] negotiated against `Accept-Encoding`.
 * - **Inbound** request body decompression — driven by the same
 *   [encoders] map (lookup by `Content-Encoding` token), with
 *   [decompressionLimit] / [ratioLimit] / [ratioBurst] zip-bomb
 *   defence and [unknownEncodingPolicy] for unregistered encodings.
 *
 * The active directions are gated by [mode] (default [Mode.All]).
 *
 * @see KeelCompressionPlugin
 */
@KtorDsl
public class KeelCompressionConfig : KeelConditionsHolderBuilder {

    /**
     * Specifies which directions the plugin handles. Mirrors ktor's
     * `CompressionConfig.Mode`.
     *
     * - [CompressResponse]: outbound only (response side).
     * - [DecompressRequest]: inbound only (request body side).
     * - [All]: both directions (default).
     */
    public enum class Mode(internal val request: Boolean, internal val response: Boolean) {
        CompressResponse(false, true),
        DecompressRequest(true, false),
        All(true, true),
    }

    /**
     * Active directions. Default [Mode.All] — matches ktor-server-compression
     * stock. Set to [Mode.CompressResponse] to opt out of inbound decoding;
     * [Mode.DecompressRequest] to opt out of outbound encoding.
     */
    public var mode: Mode = Mode.All

    /**
     * Absolute decoded byte cap per request (inbound side only).
     *
     * Mirrors `HttpRequestDecompressionHandler.DEFAULT_DECOMPRESSION_LIMIT`
     * (1 MiB, aligns with Nginx `client_max_body_size`). Set to
     * [Long.MAX_VALUE] to opt out (e.g. for streaming uploads).
     */
    public var decompressionLimit: Long = DEFAULT_DECOMPRESSION_LIMIT

    /**
     * Decoded:input ratio cap (inbound side only). Default 100. Set to
     * [Int.MAX_VALUE] to opt out.
     */
    public var ratioLimit: Int = DEFAULT_RATIO_LIMIT

    /**
     * Ratio-violation tolerance (inbound side only). Default **0** =
     * single-shot trip: the first chunk whose decoded:input ratio
     * exceeds [ratioLimit] aborts the request. Set to a positive value
     * only when transient high-ratio chunks (dictionary-heavy /
     * gzip-header-in-first-chunk streams) must be tolerated; a
     * positive budget is cumulative and not reset on recovery, so a
     * stream that intermittently violates still aborts after
     * `ratioBurst + 1` cumulative violations.
     */
    public var ratioBurst: Int = DEFAULT_RATIO_BURST

    /**
     * Behaviour for `Content-Encoding` tokens not in [encoders] (inbound
     * side only). Default [UnknownEncodingPolicy.UnsupportedMediaType]
     * (HTTP 415).
     */
    public var unknownEncodingPolicy: UnknownEncodingPolicy =
        UnknownEncodingPolicy.UnsupportedMediaType

    /**
     * Registered encoders keyed by `Content-Encoding` token (e.g. `gzip`,
     * `deflate`, `identity`). Used by both directions:
     *
     * - **Outbound**: lookup against client `Accept-Encoding` to
     *   negotiate per-response.
     * - **Inbound**: lookup against client `Content-Encoding` to decode
     *   the request body. The [io.ktor.util.ContentEncoder.name] token
     *   is the index for both.
     */
    public val encoders: MutableMap<String, KeelCompressionEncoderBuilder> = hashMapOf()

    override val conditions: MutableList<ApplicationCall.(OutgoingContent) -> Boolean> = arrayListOf()

    /**
     * Registers an [encoder] with optional [block] customization.
     *
     * @throws IllegalArgumentException if [encoder]'s name is already registered.
     */
    public fun encoder(encoder: ContentEncoder, block: KeelCompressionEncoderBuilder.() -> Unit = {}) {
        require(encoder.name !in encoders) {
            "Encoder ${encoder.name} is already registered"
        }
        encoders[encoder.name] = KeelCompressionEncoderBuilder(encoder).apply(block)
    }

    /**
     * Registers the default encoder set: gzip + deflate (with default conditions).
     *
     * Mirrors ktor's `default()`, but routes through keel-compression-zlib
     * backed encoders ([KeelGZipEncoder] / [KeelDeflateEncoder]).
     */
    public fun default() {
        gzip()
        deflate()
    }

    internal fun buildOptions(): KeelCompressionOptions = KeelCompressionOptions(
        encoders = encoders.mapValues { (_, builder) ->
            if (conditions.none() && builder.conditions.none()) {
                builder.applyDefaultConditions()
            }
            builder.build()
        },
        conditions = conditions.toList(),
        mode = mode,
        decompressionLimit = decompressionLimit,
        ratioLimit = ratioLimit,
        ratioBurst = ratioBurst,
        unknownEncodingPolicy = unknownEncodingPolicy,
    )
}

/**
 * Builder for an individual encoder's conditions + priority.
 */
@Suppress("MemberVisibilityCanBePrivate")
public class KeelCompressionEncoderBuilder internal constructor(
    public val encoder: ContentEncoder,
) : KeelConditionsHolderBuilder {

    override val conditions: ArrayList<ApplicationCall.(OutgoingContent) -> Boolean> = arrayListOf()

    /**
     * Higher = preferred when multiple `Accept-Encoding` tokens match.
     * Default 1.0 (deflate gets 0.9 by convention so gzip wins ties).
     */
    public var priority: Double = 1.0

    internal fun build(): KeelCompressionEncoderConfig =
        KeelCompressionEncoderConfig(encoder, conditions.toList(), priority)

    internal fun applyDefaultConditions() {
        excludeContentType(
            ContentType.Video.Any,
            ContentType.Image.JPEG,
            ContentType.Image.PNG,
            ContentType.Audio.Any,
            ContentType.MultiPart.Any,
            ContentType.Text.EventStream,
        )
        minimumSize(DEFAULT_MINIMUM_COMPRESSION_SIZE)
    }
}

/**
 * Holder for `condition`, `minimumSize`, `matchContentType`, `excludeContentType` extensions.
 */
public interface KeelConditionsHolderBuilder {
    public val conditions: MutableList<ApplicationCall.(OutgoingContent) -> Boolean>
}

/**
 * Adds a custom predicate. The response is compressed only when **all**
 * conditions return `true`.
 */
public fun KeelConditionsHolderBuilder.condition(
    predicate: ApplicationCall.(OutgoingContent) -> Boolean,
) {
    conditions.add(predicate)
}

/**
 * Skips compression when the response body is shorter than [minSize] bytes.
 *
 * The check is best-effort: if [OutgoingContent.contentLength] is `null`
 * (streaming response) the condition passes (compression proceeds).
 */
public fun KeelConditionsHolderBuilder.minimumSize(minSize: Long) {
    condition { content -> content.contentLength?.let { it >= minSize } ?: true }
}

/**
 * Compresses only when `Content-Type` matches one of [mimeTypes].
 */
public fun KeelConditionsHolderBuilder.matchContentType(vararg mimeTypes: ContentType) {
    condition { content ->
        val ct = content.contentType ?: return@condition false
        mimeTypes.any { ct.match(it) }
    }
}

/**
 * Skips compression when `Content-Type` matches one of [mimeTypes].
 *
 * Falls back to the response's pre-set `Content-Type` header if the
 * `OutgoingContent` itself does not carry one.
 */
public fun KeelConditionsHolderBuilder.excludeContentType(vararg mimeTypes: ContentType) {
    condition { content ->
        val ct = content.contentType
            ?: response.headers[HttpHeaders.ContentType]?.let { ContentType.parse(it) }
            ?: return@condition true
        mimeTypes.none { ct.match(it) }
    }
}

/**
 * Registers gzip encoding (priority 1.0).
 */
public fun KeelCompressionConfig.gzip(block: KeelCompressionEncoderBuilder.() -> Unit = {}) {
    encoder(KeelGZipEncoder, block)
}

/**
 * Registers deflate encoding (priority 0.9 by default — gzip wins ties).
 */
public fun KeelCompressionConfig.deflate(block: KeelCompressionEncoderBuilder.() -> Unit = {}) {
    encoder(KeelDeflateEncoder) {
        priority = DEFAULT_DEFLATE_PRIORITY
        block()
    }
}

/**
 * Built configuration consumed by [KeelCompressionPlugin]. Internal —
 * users construct via [KeelCompressionConfig.gzip] / [KeelCompressionConfig.deflate] / etc.
 */
internal data class KeelCompressionOptions(
    val encoders: Map<String, KeelCompressionEncoderConfig>,
    val conditions: List<ApplicationCall.(OutgoingContent) -> Boolean>,
    val mode: KeelCompressionConfig.Mode,
    val decompressionLimit: Long,
    val ratioLimit: Int,
    val ratioBurst: Int,
    val unknownEncodingPolicy: UnknownEncodingPolicy,
)

internal data class KeelCompressionEncoderConfig(
    val encoder: ContentEncoder,
    val conditions: List<ApplicationCall.(OutgoingContent) -> Boolean>,
    val priority: Double,
)

/**
 * Default minimum body size below which compression is skipped (matches
 * ktor's `DEFAULT_MINIMAL_COMPRESSION_SIZE`).
 */
internal const val DEFAULT_MINIMUM_COMPRESSION_SIZE: Long = 200L

/**
 * Default deflate priority — strictly lower than gzip's 1.0 so a client
 * that accepts both encodings receives gzip.
 */
internal const val DEFAULT_DEFLATE_PRIORITY: Double = 0.9

/**
 * Default absolute decoded byte cap for inbound request bodies — 1 MiB,
 * matches `HttpRequestDecompressionHandler.DEFAULT_DECOMPRESSION_LIMIT`
 * and Nginx `client_max_body_size`.
 */
public const val DEFAULT_DECOMPRESSION_LIMIT: Long = 1L * 1024 * 1024

/**
 * Default decoded:input ratio cap for inbound request bodies — 100,
 * matches `HttpRequestDecompressionHandler.DEFAULT_RATIO_LIMIT`.
 */
public const val DEFAULT_RATIO_LIMIT: Int = 100

/**
 * Default ratio-violation burst tolerance for inbound request bodies —
 * **0** (single-shot trip), matches
 * `HttpRequestDecompressionHandler.DEFAULT_RATIO_BURST`. The first
 * chunk whose decoded:input ratio exceeds [DEFAULT_RATIO_LIMIT] aborts;
 * raise to a positive value only when transient high-ratio chunks must
 * be tolerated.
 */
public const val DEFAULT_RATIO_BURST: Int = 0
