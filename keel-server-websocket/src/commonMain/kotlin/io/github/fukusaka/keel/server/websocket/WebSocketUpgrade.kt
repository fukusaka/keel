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
import io.github.fukusaka.keel.compression.CompressionCodec
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
 * Server-wide `permessage-deflate` configuration handed to
 * [runWebSocketUpgrade].
 *
 * Bundles the compression backend with the deflate knobs so one value
 * carries everything the upgrade flow needs to negotiate RFC 7692.
 *
 * @property codec the compression backend (e.g. `DeflateCodec` from
 *   `keel-compression-zlib`).
 * @property options the server-side deflate options.
 */
public class WsDeflateConfig(
    public val codec: CompressionCodec,
    public val options: WsDeflateOptions = WsDeflateOptions.Default,
)

/**
 * Performs the server side of the WebSocket upgrade on [channel] and runs
 * [handler] against an open [WsSession].
 *
 * Sequence (RFC 6455 §4.2 + RFC 7692 + the session lifecycle):
 *
 * 1. Negotiate `permessage-deflate` (RFC 7692 §7) when [deflateConfig] is
 *    set and the client offered the extension.
 * 2. `101 Switching Protocols` head with the computed `Sec-WebSocket-Accept`
 *    and, when compression is negotiated, a `Sec-WebSocket-Extensions`
 *    header — written through the HTTP encoder still in the pipeline.
 * 3. Pipeline codec swap — remove the HTTP/1.1 stack, install the WS frame
 *    codec ([addWsServerCodec], with `allowRsv1` when compression is on)
 *    plus a [SuspendMessageBridge] of [WsFrame].
 * 4. Run [handler] on a [WsSession], with a forwarding pump filtering
 *    control frames (PING → PONG, CLOSE capture) concurrently.
 * 5. Closing handshake — echo the peer's CLOSE if it initiated, otherwise
 *    send [WsCloseCode.NORMAL_CLOSURE].
 *
 * Precondition: [requestHeaders] is a valid handshake — see
 * [isWebSocketUpgrade]. This function returns only when the session ends,
 * so it takes over [channel] for its whole lifetime.
 *
 * @param pathParameters route path parameters to expose on
 *   [WsSession.pathParameters]; defaults to empty for callers (such as
 *   the ktor adapter) that route WebSockets without `Router` parameters.
 * @param deflateConfig server-wide `permessage-deflate` config, or null
 *   (the default) to run the connection without compression — the ktor
 *   adapter caller is unaffected by leaving this out.
 */
public suspend fun runWebSocketUpgrade(
    channel: PipelinedChannel,
    requestHeaders: HttpHeaders,
    handler: WebSocketHandler,
    pathParameters: Map<String, String> = emptyMap(),
    deflateConfig: WsDeflateConfig? = null,
) {
    val clientKey = requestHeaders.getString(SEC_WEBSOCKET_KEY)
        ?: error("Sec-WebSocket-Key missing — caller must validate via isWebSocketUpgrade()")

    // (1) Negotiate permessage-deflate against the request's extension offer.
    val extension = negotiatePermessageDeflate(
        extensionsHeader = requestHeaders.getString(SEC_WEBSOCKET_EXTENSIONS),
        codec = deflateConfig?.codec,
        options = deflateConfig?.options ?: WsDeflateOptions.Default,
    )
    val deflateEngine = when (extension) {
        is WsExtensionResult.None -> null
        is WsExtensionResult.Deflate -> WsPermessageDeflate(
            // deflateConfig is non-null whenever negotiation returns Deflate.
            codec = checkNotNull(deflateConfig).codec,
            options = extension.effectiveOptions,
            serverMaxWindowBits = extension.serverMaxWindowBits,
            clientMaxWindowBits = extension.clientMaxWindowBits,
        )
    }
    val compressionActive = deflateEngine != null

    val responseHeaders = HttpHeaders().apply {
        this[HttpHeaderName.UPGRADE] = "websocket"
        this[HttpHeaderName.CONNECTION] = "Upgrade"
        this[SEC_WEBSOCKET_ACCEPT] = computeAcceptKey(clientKey)
        if (extension is WsExtensionResult.Deflate) {
            this[SEC_WEBSOCKET_EXTENSIONS] = extension.responseHeaderValue
        }
    }
    val responseHead = HttpResponseHead(
        status = HttpStatus.SWITCHING_PROTOCOLS,
        version = HttpVersion.HTTP_1_1,
        headers = responseHeaders,
    )
    val frameBridge = SuspendMessageBridge(WsFrame::class)
    withContext(channel.ioDispatcher) {
        // (2) 101 head + bodyless terminator through the HttpResponseEncoder.
        channel.pipeline.requestWrite(responseHead)
        channel.pipeline.requestWrite(HttpBodyEnd.EMPTY)
        channel.pipeline.requestFlush()
        // (3) Swap codec under the EventLoop so pipeline mutation is
        // single-threaded. allowRsv1 is on only when compression is
        // negotiated — RFC 7692 §7.2 compressed frames carry RSV1=1.
        for (name in HTTP_CODEC_HANDLER_NAMES) {
            runCatching { channel.pipeline.remove(name) }
        }
        channel.addWsServerCodec(allowRsv1 = compressionActive)
        channel.pipeline.addLast(WS_BRIDGE_NAME, frameBridge)
    }

    // (4) Run the handler; the control-frame pump runs concurrently as a
    // child coroutine, cancelled once the handler returns.
    try {
        coroutineScope {
            val session = WsSessionImpl(channel, frameBridge, pathParameters, deflateEngine)
            val pump = launch { session.runForward() }
            try {
                session.handler()
            } finally {
                // (5) Closing handshake — after the handler returns, so any
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
                // Release the permessage-deflate engine's native state.
                session.releaseDeflate()
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
 * Register it on the `keelHttpServer { }` builder via the `webSockets { }`
 * DSL. The upgrade is dispatched as the terminal of the middleware chain,
 * so middleware (auth / CORS / logging) runs before the handshake.
 *
 * @param handler the WebSocket session handler.
 * @param deflateConfig per-endpoint `permessage-deflate` config, or null
 *   to run this endpoint without compression.
 */
public class WebSocketUpgrade(
    private val handler: WebSocketHandler,
    private val deflateConfig: WsDeflateConfig? = null,
) : UpgradeProtocol {

    override val name: String get() = "websocket"

    override suspend fun upgrade(call: HttpCall, channel: PipelinedChannel) {
        if (!call.headers.isWebSocketUpgrade()) {
            call.respond(HttpResponse.of(HttpStatus.BAD_REQUEST, "Invalid WebSocket handshake"))
            return
        }
        runWebSocketUpgrade(channel, call.headers, handler, call.pathParameters, deflateConfig)
    }
}
