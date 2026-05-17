package io.github.fukusaka.keel.server.http

import io.github.fukusaka.keel.pipeline.PipelinedChannel

/**
 * A protocol the server hands a connection over to when an HTTP request
 * asks to switch protocols.
 *
 * The dominant — and, on HTTP/1.1, effectively only — case is WebSocket
 * (RFC 6455): a `GET` carrying `Upgrade: websocket`. `keel-server-http`
 * provides this hook; the concrete WebSocket implementation lives in the
 * `keel-server-websocket` module as a `WebSocketUpgrade : UpgradeProtocol`.
 *
 * An `UpgradeProtocol` is bound to a path with
 * [KeelHttpServerBuilder.upgrade] (or a higher-level DSL such as
 * `webSocket(path) { }`), so it sits in the same [Router] as regular
 * routes and shares its `:name` path-parameter matching. When a request's
 * path resolves to an upgrade route **and** its `Upgrade` header token
 * equals [name], dispatch goes to [upgrade] instead of the route handler.
 *
 * **Connection takeover**: [upgrade] receives the raw [PipelinedChannel]
 * and is expected to perform the whole switch itself — send the
 * `101 Switching Protocols` response, swap the HTTP codec on the
 * channel's pipeline for the new protocol's codec, and run the
 * upgraded-protocol session to completion. This single hand-off mirrors
 * how a real upgrade sequence composes (handshake → codec swap → session
 * loop → close), which a split `handshake()` / `installAfterHandshake()`
 * pair cannot express.
 *
 * **Out of scope**: HTTP `CONNECT`-method tunnelling (a forward-proxy
 * feature) is not dispatched here — but the connection-takeover mechanism
 * is deliberately not welded to the `Upgrade` header, so a future
 * `CONNECT` path can reuse it. Server-Sent Events are a normal streaming
 * response ([HttpCall.respondStream]), not an upgrade.
 */
public interface UpgradeProtocol {

    /**
     * The `Upgrade` header token this protocol answers — for example
     * `"websocket"`. Matched case-insensitively against the request's
     * `Upgrade` header.
     */
    public val name: String

    /**
     * Takes the connection over for [call]'s upgrade request.
     *
     * Called on the handler coroutine (the EventLoop thread) once the
     * route resolved to this protocol and the `Upgrade` token matched
     * [name]. The implementation performs the protocol handshake
     * (typically `101 Switching Protocols` via [HttpCall.respondStream]),
     * swaps the HTTP codec on [channel]'s pipeline, and runs the
     * upgraded-protocol session until it ends.
     *
     * [call] still exposes the request line, headers and
     * [HttpCall.pathParameters] (so a `webSocket("/chat/:room")` handler
     * can read `:room`). [channel] is the raw pipeline channel, needed to
     * mutate the codec stack.
     */
    public suspend fun upgrade(call: HttpCall, channel: PipelinedChannel)
}
