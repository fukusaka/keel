package io.github.fukusaka.keel.server.websocket

import io.github.fukusaka.keel.codec.http.HttpBodyEnd
import io.github.fukusaka.keel.codec.http.HttpHeaderName
import io.github.fukusaka.keel.codec.http.HttpHeaders
import io.github.fukusaka.keel.codec.http.HttpResponse
import io.github.fukusaka.keel.codec.http.HttpResponseHead
import io.github.fukusaka.keel.codec.http.HttpStatus
import io.github.fukusaka.keel.codec.http.HttpVersion
import io.github.fukusaka.keel.codec.websocket.WsCloseCode
import io.github.fukusaka.keel.codec.websocket.WsFrame
import io.github.fukusaka.keel.codec.websocket.WsOpcode
import io.github.fukusaka.keel.codec.websocket.addWsServerCodec
import io.github.fukusaka.keel.codec.websocket.computeAcceptKey
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.pipeline.SuspendMessageBridge
import io.github.fukusaka.keel.server.http.HttpCall
import io.github.fukusaka.keel.server.http.UpgradeProtocol
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Pipeline handler name of the WebSocket frame bridge installed by [runWebSocketUpgrade]. */
private const val WS_BRIDGE_NAME = "ws-bridge"

/**
 * Pipeline handler names of every HTTP/1.1 codec stage any keel HTTP
 * server installs ahead of its dispatch handler — `keel-server-http`
 * (`decoder` / `encoder` / `http-server`) and the ktor adapter
 * (`decoder` / `encoder` / `aggregator` / `bridge`). [runWebSocketUpgrade]
 * removes each before installing the WS codec; `runCatching` tolerates
 * the names a given server does not use.
 */
private val HTTP_CODEC_HANDLER_NAMES = listOf("http-server", "bridge", "aggregator", "decoder", "encoder")

/**
 * Performs the server side of the WebSocket upgrade on [channel] and runs
 * [handler] against an open [WsSession].
 *
 * Sequence (RFC 6455 §4.2 + the session lifecycle):
 *
 * 1. `101 Switching Protocols` head with the computed `Sec-WebSocket-Accept`,
 *    written through the HTTP encoder still in the pipeline.
 * 2. Pipeline codec swap — remove the HTTP/1.1 stack, install the WS frame
 *    codec ([addWsServerCodec]) plus a [SuspendMessageBridge] of [WsFrame].
 * 3. Run [handler] on a [WsSession], with a forwarding pump filtering
 *    control frames (PING → PONG, CLOSE capture) concurrently.
 * 4. Closing handshake — echo the peer's CLOSE if it initiated, otherwise
 *    send [WsCloseCode.NORMAL_CLOSURE].
 *
 * Precondition: [requestHeaders] is a valid handshake — see
 * [isWebSocketUpgrade]. This function returns only when the session ends,
 * so it takes over [channel] for its whole lifetime.
 *
 * @param pathParameters route path parameters to expose on
 *   [WsSession.pathParameters]; defaults to empty for callers (such as
 *   the ktor adapter) that route WebSockets without `Router` parameters.
 */
public suspend fun runWebSocketUpgrade(
    channel: PipelinedChannel,
    requestHeaders: HttpHeaders,
    handler: WebSocketHandler,
    pathParameters: Map<String, String> = emptyMap(),
) {
    val clientKey = requestHeaders[SEC_WEBSOCKET_KEY]
        ?: error("Sec-WebSocket-Key missing — caller must validate via isWebSocketUpgrade()")
    val responseHead = HttpResponseHead(
        status = HttpStatus.SWITCHING_PROTOCOLS,
        version = HttpVersion.HTTP_1_1,
        headers = HttpHeaders.of(
            HttpHeaderName.UPGRADE to "websocket",
            HttpHeaderName.CONNECTION to "Upgrade",
            SEC_WEBSOCKET_ACCEPT to computeAcceptKey(clientKey),
        ),
    )
    val frameBridge = SuspendMessageBridge(WsFrame::class)
    withContext(channel.ioDispatcher) {
        // (1) 101 head + bodyless terminator through the HttpResponseEncoder.
        channel.pipeline.requestWrite(responseHead)
        channel.pipeline.requestWrite(HttpBodyEnd.EMPTY)
        channel.pipeline.requestFlush()
        // (2) Swap codec under the EventLoop so pipeline mutation is
        // single-threaded.
        for (name in HTTP_CODEC_HANDLER_NAMES) {
            runCatching { channel.pipeline.remove(name) }
        }
        channel.addWsServerCodec()
        channel.pipeline.addLast(WS_BRIDGE_NAME, frameBridge)
    }

    // (3) Run the handler; the control-frame pump runs concurrently as a
    // child coroutine, cancelled once the handler returns.
    try {
        coroutineScope {
            val session = WsSessionImpl(channel, frameBridge, pathParameters)
            val pump = launch { session.runForward() }
            try {
                session.handler()
            } finally {
                // (4) Closing handshake — after the handler returns, so any
                // frames it sent are queued before CLOSE (RFC 6455 §5.5.1).
                val peerClose = session.peerCloseFrame
                runCatching {
                    if (peerClose != null) {
                        session.sendRaw(WsFrame(fin = true, opcode = WsOpcode.CLOSE, payload = peerClose.payload))
                    } else {
                        session.close(WsCloseCode.NORMAL_CLOSURE)
                    }
                }
                pump.cancel()
            }
        }
    } finally {
        // The WebSocket session is the whole post-upgrade life of the
        // connection — once it ends, close the channel. `runWebSocketUpgrade`
        // owns this fd: no HTTP dispatch handler remains to do it.
        channel.close()
    }
}

/** `Sec-WebSocket-Accept` response header (RFC 6455 §4.2.2). */
private const val SEC_WEBSOCKET_ACCEPT = "Sec-WebSocket-Accept"

/**
 * An [UpgradeProtocol] that takes over an `Upgrade: websocket` request and
 * runs [handler] against the resulting [WsSession].
 *
 * Register it on the `keelHttpServer { }` builder via [webSocket]. The
 * upgrade is dispatched as the terminal of the middleware chain, so
 * middleware (auth / CORS / logging) runs before the handshake.
 */
public class WebSocketUpgrade(private val handler: WebSocketHandler) : UpgradeProtocol {

    override val name: String get() = "websocket"

    override suspend fun upgrade(call: HttpCall, channel: PipelinedChannel) {
        if (!call.headers.isWebSocketUpgrade()) {
            call.respond(HttpResponse.of(HttpStatus.BAD_REQUEST, "Invalid WebSocket handshake"))
            return
        }
        runWebSocketUpgrade(channel, call.headers, handler, call.pathParameters)
    }
}
