package io.github.fukusaka.keel.server.http.dsl

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.codec.http.CompressionCondition
import io.github.fukusaka.keel.codec.http.CompressionHandler
import io.github.fukusaka.keel.codec.http.HttpRequestDecompressionHandler
import io.github.fukusaka.keel.codec.http.HttpResponseHead
import io.github.fukusaka.keel.codec.http.UnknownEncodingPolicy
import io.github.fukusaka.keel.compression.CompressionCodec
import io.github.fukusaka.keel.compression.CompressionRegistry
import io.github.fukusaka.keel.compression.DeflateTuning
import io.github.fukusaka.keel.compression.EncoderOptions
import io.github.fukusaka.keel.compression.Strategy
import io.github.fukusaka.keel.server.dsl.KeelServerDsl

/**
 * Outbound response compression + optional inbound request decompression
 * for a [KeelHttpServer], composed in a Ktor-style `compression { … }`
 * DSL block.
 *
 * Unlike a [Middleware], compression is a **pipeline handler** that
 * intercepts streaming `HttpResponseHead` + `HttpBody` chunks on their
 * way out (and `HttpRequestHead` + `HttpBody` on the way in, when
 * inbound decompression is enabled). A middleware sees the
 * application-level response object after the handler returns; the
 * compression layer must transform each chunk **before** the encoder
 * serialises it to wire bytes. Both layers can coexist — middleware
 * wraps the route handler invocation; compression wraps the pipeline's
 * byte stream.
 *
 * ## Usage
 *
 * ```
 * keelHttpServer(engine) {
 *     connector { host = "0.0.0.0"; port = 8080 }
 *
 *     compression {
 *         encoder(GzipCodec, priority = 1)
 *         encoder(DeflateCodec, priority = 0)
 *
 *         responseCondition {
 *             minContentLength = 1024
 *             excludeContentTypePrefix("image/", "video/", "audio/")
 *             custom { responseHead ->
 *                 responseHead.headers["X-No-Compress"] == null
 *             }
 *         }
 *
 *         requestDecompression {
 *             limit = 10L * 1024 * 1024
 *             ratioLimit = 100
 *         }
 *     }
 *
 *     get("/large") { call -> call.respond(LargeResponse) }
 *     post("/upload-stream") { call -> /* gzip body auto-decoded */ }
 * }
 * ```
 *
 * ## Pipeline placement
 *
 * When configured the install pass adds (in `addLast` order, so the
 * outbound traversal is right-to-left):
 *
 * ```
 * decoder → encoder → [requestDecompression] → [compression] → HttpServerHandler
 * ```
 *
 * - Outbound: `HttpServerHandler` writes a `HttpResponseHead` + body
 *   chunks → `[compression]` negotiates against the captured
 *   `Accept-Encoding`, transforms the chunks → `encoder` serialises to
 *   wire bytes.
 * - Inbound: `decoder` parses `HttpRequestHead` → `encoder` snoops →
 *   `[requestDecompression]` decodes the body if `Content-Encoding` is
 *   set → `[compression]` captures `Accept-Encoding` →
 *   `HttpServerHandler`.
 *
 * Either branch is no-op when its config is absent (no encoder
 * registered → no [CompressionHandler]; no `requestDecompression {}` →
 * no [HttpRequestDecompressionHandler]).
 */
@KeelServerDsl
public class CompressionBuilder internal constructor() {

    private val registry = CompressionRegistry()
    private var encoderCount: Int = 0
    private var conditionBuilder = CompressionConditionBuilder()
    private var requestDecompressionBuilder: RequestDecompressionBuilder? = null
    private var deflateTuning: DeflateTuning? = null

    /**
     * Registers [codec] (both encoder + decoder halves) with the given
     * tie-break [priority] (higher wins when multiple encodings share the
     * same `q` value in `Accept-Encoding`).
     *
     * Both the encoder and the decoder are registered so the same
     * [CompressionCodec] can serve outbound compression and inbound
     * decompression (when [requestDecompression] is enabled) — typical
     * server setups want both for the same codecs.
     */
    public fun encoder(codec: CompressionCodec, priority: Int = 0) {
        registry.register(codec, priority)
        encoderCount += 1
    }

    /**
     * Configures the per-response predicate that decides whether an
     * eligible response should actually be compressed. Defaults already
     * exclude pre-compressed MIME prefixes (`image/`, `video/`,
     * `audio/`, `application/zip`, etc.); the block adds to or overrides
     * those.
     */
    public fun responseCondition(configure: CompressionConditionBuilder.() -> Unit) {
        conditionBuilder.configure()
    }

    /**
     * Enables inbound request body decompression (`Content-Encoding`).
     * Without this block, requests with a `Content-Encoding` header are
     * passed through to the handler unchanged.
     *
     * The block configures the limits ([RequestDecompressionBuilder.limit] /
     * [RequestDecompressionBuilder.ratioLimit] /
     * [RequestDecompressionBuilder.ratioBurst]) and the
     * [RequestDecompressionBuilder.unknownEncoding] policy enforced by
     * the installed [HttpRequestDecompressionHandler].
     */
    public fun requestDecompression(configure: RequestDecompressionBuilder.() -> Unit) {
        val builder = RequestDecompressionBuilder().apply(configure)
        requestDecompressionBuilder = builder
    }

    /**
     * Tunes the DEFLATE-family encoders (`gzip` / `deflate`) registered via
     * [encoder]. The block sets a [DeflateTuning] (`windowBits` / `strategy`)
     * applied to every response the encoders compress; non-DEFLATE codecs
     * (e.g. a future zstd) ignore it. Omitting the block uses the backend
     * defaults.
     */
    public fun deflate(configure: DeflateTuningBuilder.() -> Unit) {
        deflateTuning = DeflateTuningBuilder().apply(configure).build()
    }

    internal fun build(): CompressionPipelineConfig? {
        if (encoderCount == 0 && requestDecompressionBuilder == null) {
            return null
        }
        return CompressionPipelineConfig(
            registry = registry,
            hasResponseEncoder = encoderCount > 0,
            responseCondition = conditionBuilder.build(),
            requestDecompression = requestDecompressionBuilder?.build(),
            // Keep flushMode = Sync (CompressionHandler default) for chunked streaming.
            encoderOptions = EncoderOptions(tuning = deflateTuning),
        )
    }
}

/**
 * Mutable builder for the DEFLATE-family encoder tuning of a
 * `compression { deflate { } }` block.
 *
 * `windowBits` is exposed here (unlike WebSocket, where it is negotiated)
 * because an HTTP response stream sets its own window. See [DeflateTuning].
 */
@KeelServerDsl
public class DeflateTuningBuilder internal constructor() {

    /** See [DeflateTuning.windowBits]. Null = backend default (15). */
    public var windowBits: Int? = null

    /** See [DeflateTuning.strategy]. */
    public var strategy: Strategy = Strategy.Default

    internal fun build(): DeflateTuning = DeflateTuning(windowBits = windowBits, strategy = strategy)
}

/**
 * Per-response compression predicate (the keel default already excludes
 * common pre-compressed MIME prefixes — this builder lets a server
 * tighten or extend that).
 *
 * - [minContentLength]: drop compression when `Content-Length` is set
 *   and below the threshold. 0 (default) disables the check.
 * - [excludeContentTypePrefix]: prefixes matched case-insensitively
 *   against the response `Content-Type`. Defaults documented on
 *   [CompressionCondition] (image / video / audio / zip families).
 * - [custom]: extra predicate run after the built-in checks; returning
 *   `false` skips compression for that response.
 */
@KeelServerDsl
public class CompressionConditionBuilder internal constructor() {

    /** Minimum `Content-Length` in bytes; 0 (default) disables the check. */
    public var minContentLength: Int = 0

    private val excludePrefixes: MutableList<String> = mutableListOf(
        "image/", "video/", "audio/",
        "application/zip", "application/gzip", "application/x-gzip",
        "application/x-7z-compressed", "application/x-rar-compressed",
        "application/x-bzip2", "application/zstd",
    )

    private var customPredicate: ((HttpResponseHead) -> Boolean)? = null

    /**
     * Replaces the default exclusion prefix list with [prefix]. Use to
     * narrow the defaults; pass nothing to clear the list entirely
     * (allow compression of all MIME types unless [custom] rejects).
     */
    public fun replaceContentTypeExclusions(vararg prefix: String) {
        excludePrefixes.clear()
        excludePrefixes.addAll(prefix)
    }

    /**
     * Adds additional `Content-Type` prefixes to skip on top of the
     * defaults. Compounding — does not replace earlier calls.
     */
    public fun excludeContentTypePrefix(vararg prefix: String) {
        excludePrefixes.addAll(prefix)
    }

    /** Custom per-response predicate. Returning `false` skips compression. */
    public fun custom(predicate: (HttpResponseHead) -> Boolean) {
        customPredicate = predicate
    }

    internal fun build(): CompressionCondition = CompressionCondition(
        minContentLength = minContentLength,
        skipMimeTypes = excludePrefixes.toList(),
        custom = customPredicate,
    )
}

/**
 * Inbound request decompression configuration (`Content-Encoding`).
 * Limits map directly onto [HttpRequestDecompressionHandler]
 * parameters — see that class's documentation for the security
 * rationale on each (zip-bomb defence, ratio limits, etc.).
 */
@KeelServerDsl
public class RequestDecompressionBuilder internal constructor() {

    /**
     * Maximum total decompressed body bytes per request. Defaults to
     * [HttpRequestDecompressionHandler.DEFAULT_DECOMPRESSION_LIMIT]
     * (1 MiB).
     */
    public var limit: Long = HttpRequestDecompressionHandler.DEFAULT_DECOMPRESSION_LIMIT

    /**
     * Maximum decoded-to-encoded ratio. Defaults to
     * [HttpRequestDecompressionHandler.DEFAULT_RATIO_LIMIT] (100:1).
     */
    public var ratioLimit: Int = HttpRequestDecompressionHandler.DEFAULT_RATIO_LIMIT

    /**
     * Initial bytes admitted before [ratioLimit] starts being enforced.
     * Defaults to [HttpRequestDecompressionHandler.DEFAULT_RATIO_BURST]
     * (3 chunks).
     */
    public var ratioBurst: Int = HttpRequestDecompressionHandler.DEFAULT_RATIO_BURST

    /**
     * Policy for `Content-Encoding` tokens this server has no decoder
     * for. Defaults to [UnknownEncodingPolicy.UnsupportedMediaType]
     * (rejects with 415).
     */
    public var unknownEncoding: UnknownEncodingPolicy = UnknownEncodingPolicy.UnsupportedMediaType

    internal fun build(): RequestDecompressionConfig = RequestDecompressionConfig(
        limit = limit,
        ratioLimit = ratioLimit,
        ratioBurst = ratioBurst,
        unknownEncoding = unknownEncoding,
    )
}

/**
 * Resolved compression configuration the [KeelHttpServer] passes to its
 * channel pipeline installer. `null` when the user didn't call
 * [compression] on the builder — the install pass then skips both
 * `[compression]` and `[requestDecompression]` handlers entirely.
 */
public class CompressionPipelineConfig internal constructor(
    public val registry: CompressionRegistry,
    public val hasResponseEncoder: Boolean,
    public val responseCondition: CompressionCondition,
    public val requestDecompression: RequestDecompressionConfig?,
    public val encoderOptions: EncoderOptions = EncoderOptions(),
) {

    internal fun installResponseEncoder(
        allocator: BufferAllocator,
    ): CompressionHandler = CompressionHandler(
        registry = registry,
        allocator = allocator,
        condition = responseCondition,
        defaultEncoderOptions = encoderOptions,
    )

    internal fun installRequestDecoder(
        allocator: BufferAllocator,
    ): HttpRequestDecompressionHandler? = requestDecompression?.let { req ->
        HttpRequestDecompressionHandler(
            registry = registry,
            allocator = allocator,
            decompressionLimit = req.limit,
            ratioLimit = req.ratioLimit,
            ratioBurst = req.ratioBurst,
            unknownEncodingPolicy = req.unknownEncoding,
        )
    }
}

/** Inbound decompression configuration; see [RequestDecompressionBuilder]. */
public data class RequestDecompressionConfig(
    val limit: Long,
    val ratioLimit: Int,
    val ratioBurst: Int,
    val unknownEncoding: UnknownEncodingPolicy,
)
