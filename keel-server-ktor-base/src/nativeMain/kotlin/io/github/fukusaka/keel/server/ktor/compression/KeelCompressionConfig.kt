package io.github.fukusaka.keel.server.ktor.compression

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
 * Scope is **response compression only** — request decompression
 * (`Content-Encoding` on the request body) is a separate plugin
 * (`KeelContentEncodingPlugin`) tracked as a follow-up PR. ktor's
 * `Compression` plugin combines both directions; keel splits them so
 * each plugin has a single responsibility and either can be omitted
 * without forcing the other.
 *
 * @see KeelCompressionPlugin
 */
@KtorDsl
public class KeelCompressionConfig : KeelConditionsHolderBuilder {

    /**
     * Registered encoders keyed by `Content-Encoding` token (e.g. `gzip`,
     * `deflate`, `identity`). Lookup is performed against the client's
     * `Accept-Encoding` header to negotiate per-response.
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
