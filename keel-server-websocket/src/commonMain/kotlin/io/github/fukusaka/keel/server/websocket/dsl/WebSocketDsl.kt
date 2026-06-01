package io.github.fukusaka.keel.server.websocket.dsl

import io.github.fukusaka.keel.compression.CompressionCodec
import io.github.fukusaka.keel.compression.Strategy
import io.github.fukusaka.keel.server.dsl.KeelServerDsl
import io.github.fukusaka.keel.server.http.dsl.KeelHttpServerBuilder
import io.github.fukusaka.keel.server.http.dsl.RouteGroupBuilder
import io.github.fukusaka.keel.server.websocket.WebSocketHandler
import io.github.fukusaka.keel.server.websocket.WebSocketUpgrade
import io.github.fukusaka.keel.server.websocket.WsDeflateConfig
import io.github.fukusaka.keel.server.websocket.WsDeflateOptions
import io.github.fukusaka.keel.server.websocket.WsSession

/**
 * Per-endpoint `permessage-deflate` override inside a `webSockets { }`
 * group.
 *
 * A `webSockets(codec) { }` group sets a default deflate configuration;
 * each `webSocket(...)` endpoint may override it:
 *
 * - [Inherit] — use the group default (the param's own default).
 * - [Disabled] — turn compression off for this one endpoint, even when
 *   the group enabled it.
 * - [Custom] — use endpoint-specific [WsDeflateOptions].
 *
 * A three-way type rather than a nullable `WsDeflateOptions?` because
 * `null` cannot distinguish "inherit the group default" from "explicitly
 * disabled" — both would collapse to the same value.
 */
public sealed interface WsDeflateOverride {

    /** Inherit the group's deflate configuration (the default). */
    public data object Inherit : WsDeflateOverride

    /** Disable `permessage-deflate` for this endpoint only. */
    public data object Disabled : WsDeflateOverride

    /**
     * Use endpoint-specific [options], overriding the group default.
     *
     * @property options the deflate options for this endpoint.
     */
    public data class Custom(val options: WsDeflateOptions) : WsDeflateOverride
}

/**
 * Mutable builder for [WsDeflateOptions], used by the `deflate { }`
 * sub-block of `webSockets { }`.
 *
 * @see WsDeflateOptions for the meaning of each field.
 */
@KeelServerDsl
public class WsDeflateOptionsBuilder internal constructor() {

    /** See [WsDeflateOptions.contextTakeover]. */
    public var contextTakeover: Boolean = WsDeflateOptions.Default.contextTakeover

    /** See [WsDeflateOptions.threshold]. */
    public var threshold: Int = WsDeflateOptions.Default.threshold

    /** See [WsDeflateOptions.level]. */
    public var level: Int = WsDeflateOptions.Default.level

    /** See [WsDeflateOptions.strategy]. */
    public var strategy: Strategy = WsDeflateOptions.Default.strategy

    internal fun build(): WsDeflateOptions =
        WsDeflateOptions(contextTakeover = contextTakeover, threshold = threshold, level = level, strategy = strategy)
}

/**
 * Builder for a group of WebSocket endpoints, created by
 * [webSockets].
 *
 * Endpoints registered with [webSocket] share the group's compression
 * configuration: when [webSockets] was given a [CompressionCodec], the
 * `deflate { }` sub-block tunes the group default and each endpoint may
 * override it with its `deflate` parameter. Without a codec, the group
 * runs no compression and `deflate { }` is a misuse error.
 *
 * @property compressionCodec the group's compression backend, or null
 *   for a no-compression group.
 */
@KeelServerDsl
public class WebSocketsBuilder internal constructor(
    private val compressionCodec: CompressionCodec?,
) {

    /** Group-default deflate options; only meaningful with a codec. */
    private var groupOptions: WsDeflateOptions = WsDeflateOptions.Default

    /** Collected endpoint registrations: path → upgrade protocol. */
    private val endpoints = mutableListOf<Pair<String, WebSocketUpgrade>>()

    /**
     * Tunes the group-default `permessage-deflate` options.
     *
     * Only valid when [webSockets] was given a [CompressionCodec] —
     * calling it on a no-compression group is a builder misuse and
     * throws [IllegalStateException], since the options would have no
     * codec to apply to.
     */
    public fun deflate(configure: WsDeflateOptionsBuilder.() -> Unit) {
        checkNotNull(compressionCodec) {
            "deflate { } requires webSockets(codec) — no compression codec was provided to this group"
        }
        groupOptions = WsDeflateOptionsBuilder().apply(configure).build()
    }

    /**
     * Registers a WebSocket endpoint at [path].
     *
     * A request to [path] whose `Upgrade` header names `websocket` is
     * taken over by [handler], which runs against an open [WsSession]
     * until it returns; the closing handshake and teardown are automatic.
     *
     * [path] shares the `Router` pattern syntax — `:name` parameters and
     * a trailing `*` work — so a non-WebSocket request to the same path
     * is still resolved as an ordinary route or answered `404`.
     *
     * @param path the route pattern.
     * @param deflate per-endpoint compression override.
     *   [WsDeflateOverride.Inherit] (the default) uses the group config;
     *   [WsDeflateOverride.Disabled] turns compression off for this
     *   endpoint; [WsDeflateOverride.Custom] supplies endpoint-specific
     *   options. Ignored when the group has no codec.
     * @param handler the session handler.
     */
    public fun webSocket(
        path: String,
        deflate: WsDeflateOverride = WsDeflateOverride.Inherit,
        handler: WebSocketHandler,
    ) {
        endpoints.add(path to WebSocketUpgrade(handler, resolveDeflateConfig(deflate)))
    }

    /**
     * Resolves the effective [WsDeflateConfig] for one endpoint given
     * its [override] and the group's codec / default options. Returns
     * null (no compression) when the group has no codec or the endpoint
     * disabled compression.
     */
    private fun resolveDeflateConfig(override: WsDeflateOverride): WsDeflateConfig? {
        val codec = compressionCodec ?: return null
        return when (override) {
            is WsDeflateOverride.Disabled -> null
            is WsDeflateOverride.Inherit -> WsDeflateConfig(codec, groupOptions)
            is WsDeflateOverride.Custom -> WsDeflateConfig(codec, override.options)
        }
    }

    /** Snapshot of the collected endpoints for [webSockets] to register. */
    internal fun endpoints(): List<Pair<String, WebSocketUpgrade>> = endpoints.toList()
}

/**
 * Registers a group of WebSocket endpoints on the `keelHttpServer { }`
 * builder.
 *
 * ```
 * keelHttpServer(engine) {
 *     webSockets {                                  // no compression
 *         webSocket("/echo") { for (m in incoming) send(m) }
 *     }
 *     webSockets(DeflateCodec) {                    // permessage-deflate
 *         deflate { contextTakeover = false; threshold = 1024; level = -1 }
 *         webSocket("/chat") { for (m in incoming) send(m) }
 *         webSocket("/raw", deflate = WsDeflateOverride.Disabled) { ... }
 *     }
 * }
 * ```
 *
 * @param compressionCodec the compression backend shared by every
 *   endpoint in the group, or null (the default) to run the group
 *   without `permessage-deflate`. Pass e.g. `DeflateCodec` from
 *   `keel-compression-zlib`.
 * @param configure the group body — `deflate { }` to tune compression
 *   and `webSocket(...)` to register endpoints.
 */
public fun KeelHttpServerBuilder.webSockets(
    compressionCodec: CompressionCodec? = null,
    configure: WebSocketsBuilder.() -> Unit,
) {
    val builder = WebSocketsBuilder(compressionCodec).apply(configure)
    for ((path, upgradeProtocol) in builder.endpoints()) {
        upgrade(path, upgradeProtocol)
    }
}

/**
 * Registers a group of WebSocket endpoints inside a `route(prefix) { }`
 * group, so the endpoints inherit the group's path prefix and its
 * `install`ed middleware.
 *
 * The group counterpart of [KeelHttpServerBuilder.webSockets] — it builds
 * the same [WebSocketsBuilder] and registers each endpoint through
 * [RouteGroupBuilder.upgrade], which prefixes the path and wraps the
 * upgrade hand-off with the group middleware (auth / logging run before
 * the WebSocket handshake).
 *
 * ```
 * keelHttpServer(engine) {
 *     route("/api/v1") {
 *         install { call, next -> /* auth */ next() }
 *         webSockets(DeflateCodec) {
 *             webSocket("/chat") { for (m in incoming) send(m) }   // /api/v1/chat
 *         }
 *     }
 * }
 * ```
 *
 * @param compressionCodec the compression backend shared by the group, or
 *   null (the default) to run without `permessage-deflate`.
 * @param configure the group body — see [WebSocketsBuilder].
 */
public fun RouteGroupBuilder.webSockets(
    compressionCodec: CompressionCodec? = null,
    configure: WebSocketsBuilder.() -> Unit,
) {
    val builder = WebSocketsBuilder(compressionCodec).apply(configure)
    for ((path, upgradeProtocol) in builder.endpoints()) {
        upgrade(path, upgradeProtocol)
    }
}
