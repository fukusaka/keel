package io.github.fukusaka.keel.server.ktor.websocket

import io.github.fukusaka.keel.server.websocket.WsSession
import io.ktor.server.application.Application
import io.ktor.util.AttributeKey

/** A WebSocket handler block — runs inside the [WsSession]'s scope. */
internal typealias WsHandler = suspend WsSession.() -> Unit

/**
 * Path-keyed table of [WsHandler]s, one per `keelWebSocket(...)` call.
 *
 * Lookup is exact-match on the request URI's path component (query
 * stripped). Path parameters and method matching are intentionally not
 * supported — this DSL bypasses Ktor's `routing { ... }` tree to keep
 * the upgrade path simple. Apps that need richer matching can wire the
 * handler manually inside a regular Ktor route, but the common WS
 * use-cases (`/ws-echo`, `/chat`, etc.) are covered by exact match.
 */
internal class WsRoutes {
    private val handlers = mutableMapOf<String, WsHandler>()

    fun register(path: String, handler: WsHandler) {
        require(path.startsWith("/")) { "WebSocket path must start with '/': $path" }
        require(handlers.put(path, handler) == null) {
            "WebSocket path already registered: $path"
        }
    }

    /** Returns the handler registered for [requestUri]'s path, or null. */
    fun lookup(requestUri: String): WsHandler? {
        val pathEnd = requestUri.indexOfAny(charArrayOf('?', '#'))
        val path = if (pathEnd >= 0) requestUri.substring(0, pathEnd) else requestUri
        return handlers[path]
    }
}

internal val WsRoutesAttributeKey = AttributeKey<WsRoutes>("KeelWebSocketRoutes")

internal fun Application.wsRoutes(): WsRoutes {
    val existing = attributes.getOrNull(WsRoutesAttributeKey)
    if (existing != null) return existing
    val routes = WsRoutes()
    attributes.put(WsRoutesAttributeKey, routes)
    return routes
}

/**
 * Registers a keel-native WebSocket handler at [path].
 *
 * Activates when an HTTP/1.1 request arrives with `Upgrade: websocket`
 * (RFC 6455 §4.1) and an URI whose path component (query stripped)
 * matches [path] exactly. The connection bypasses the Ktor application
 * pipeline: the keel adapter performs the handshake, swaps the HTTP
 * codec for [addWsServerCodec][io.github.fukusaka.keel.codec.websocket.addWsServerCodec],
 * builds a [WsSession] and invokes [handler] on the session.
 *
 * Re-registering the same [path] throws — call once per path.
 *
 * Example:
 * ```kotlin
 * embeddedServer(Keel) {
 *     engine = NioEngine()
 *     connector { port = 8080 }
 * }.apply {
 *     application.keelWebSocket("/echo") {
 *         for (frame in incoming) send(frame)
 *     }
 * }.start(wait = true)
 * ```
 *
 * Use the standard Ktor `routing { get("/path") { ... } }` DSL for
 * regular HTTP routes alongside; only requests with `Upgrade: websocket`
 * AND a registered path are diverted from Ktor's pipeline.
 */
public fun Application.keelWebSocket(path: String, handler: suspend WsSession.() -> Unit) {
    wsRoutes().register(path, handler)
}
