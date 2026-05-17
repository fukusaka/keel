package io.github.fukusaka.keel.server.websocket

import io.github.fukusaka.keel.server.http.KeelHttpServerBuilder

/**
 * Registers a WebSocket endpoint at [path] on the `keelHttpServer { }`
 * builder.
 *
 * A request to [path] whose `Upgrade` header names `websocket` is taken
 * over by [handler], which runs against an open [WsSession] until it
 * returns; the closing handshake and connection teardown are automatic
 * (see [WebSocketUpgrade]).
 *
 * [path] shares the `Router` pattern syntax — `:name` parameters and a
 * trailing `*` work — so a non-WebSocket request to the same path is
 * still resolved as an ordinary route or answered `404`. Parameters
 * bound by the pattern are exposed on [WsSession.pathParameters].
 *
 * ```
 * val server = keelHttpServer(engine) {
 *     webSocket("/echo") {
 *         for (frame in incoming) send(frame)
 *     }
 *     webSocket("/chat/:room") {
 *         val room = pathParameters["room"]
 *         for (frame in incoming) send(frame)
 *     }
 * }
 * ```
 */
public fun KeelHttpServerBuilder.webSocket(path: String, handler: WebSocketHandler) {
    upgrade(path, WebSocketUpgrade(handler))
}
