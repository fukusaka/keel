package io.github.fukusaka.keel.server.ktor.websocket

import io.github.fukusaka.keel.codec.http.HttpBodyEnd
import io.github.fukusaka.keel.codec.http.HttpHeaderName
import io.github.fukusaka.keel.codec.http.HttpHeaders
import io.github.fukusaka.keel.codec.http.HttpRequestHead
import io.github.fukusaka.keel.codec.http.HttpResponseHead
import io.github.fukusaka.keel.codec.http.HttpStatus
import io.github.fukusaka.keel.codec.http.HttpVersion
import io.github.fukusaka.keel.codec.websocket.WsCloseCode
import io.github.fukusaka.keel.codec.websocket.WsFrame
import io.github.fukusaka.keel.codec.websocket.WsOpcode
import io.github.fukusaka.keel.codec.websocket.addWsServerCodec
import io.github.fukusaka.keel.codec.websocket.computeAcceptKey
import io.github.fukusaka.keel.codec.websocket.validateClientKey
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.pipeline.SuspendMessageBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Returns true if [this] looks like a valid RFC 6455 §4.1 client
 * handshake — `Upgrade: websocket`, `Connection: Upgrade`, valid 16-byte
 * `Sec-WebSocket-Key`, and `Sec-WebSocket-Version: 13`. Header matching
 * is case-insensitive and tolerant of comma-separated `Connection` values.
 */
internal fun HttpRequestHead.isWebSocketUpgrade(): Boolean {
    if (!headers[HttpHeaderName.UPGRADE].equalsIgnoreCase("websocket")) return false
    val connection = headers[HttpHeaderName.CONNECTION] ?: return false
    if (!connection.split(',').any { it.trim().equalsIgnoreCase("upgrade") }) return false
    if (headers["Sec-WebSocket-Version"] != "13") return false
    val key = headers["Sec-WebSocket-Key"] ?: return false
    return validateClientKey(key)
}

private fun String?.equalsIgnoreCase(other: String): Boolean =
    this != null && this.equals(other, ignoreCase = true)

/**
 * Performs the server side of the WebSocket upgrade and runs [handler]
 * against a [WsSession] backed by the keel WS codec.
 *
 * Sequence:
 * 1. Send `101 Switching Protocols` head with the computed
 *    `Sec-WebSocket-Accept` (BODYLESS streaming via [HttpResponseHead] +
 *    [HttpBodyEnd.EMPTY]).
 * 2. Remove the HTTP/1.1 codec stack (`encoder` / `decoder` /
 *    `aggregator` / `bridge`) from the pipeline.
 * 3. Install [addWsServerCodec] and a fresh [SuspendMessageBridge] for
 *    [WsFrame].
 * 4. Build a [WsSessionImpl], launch its forwarding pump, and invoke
 *    [handler] on the session.
 * 5. After [handler] returns (or throws), perform the closing
 *    handshake. If the peer initiated the close (the pump captured a
 *    CLOSE frame in [WsSessionImpl.peerCloseFrame]), echo their
 *    code and reason back; otherwise send a fresh CLOSE with
 *    [WsCloseCode.NORMAL_CLOSURE]. Either branch is a no-op when the
 *    handler already drove [WsSession.close] explicitly. The
 *    underlying connection close is handled by the surrounding
 *    [KeelCodecConnectionHandler][io.github.fukusaka.keel.server.ktor.KeelCodecConnectionHandler].
 *
 * The pre-condition for entering this function is
 * [HttpRequestHead.isWebSocketUpgrade] = true.
 */
internal suspend fun runWebSocketUpgrade(
    channel: PipelinedChannel,
    head: HttpRequestHead,
    scope: CoroutineScope,
    handler: WsHandler,
) {
    val clientKey = head.headers["Sec-WebSocket-Key"]
        ?: error("Sec-WebSocket-Key missing — caller must validate via isWebSocketUpgrade()")
    val acceptKey = computeAcceptKey(clientKey)

    // (1) 101 head + bodyless terminator routed through the existing
    // HttpResponseEncoder (BODYLESS streaming mode added in #411).
    val responseHead = HttpResponseHead(
        status = HttpStatus(101),
        version = HttpVersion.HTTP_1_1,
        headers = HttpHeaders.of(
            HttpHeaderName.UPGRADE to "websocket",
            HttpHeaderName.CONNECTION to "Upgrade",
            "Sec-WebSocket-Accept" to acceptKey,
        ),
    )
    withContext(channel.ioDispatcher) {
        channel.pipeline.requestWrite(responseHead)
        channel.pipeline.requestWrite(HttpBodyEnd.EMPTY)
        channel.pipeline.requestFlush()
    }
    channel.flush()

    // (2) + (3) Swap codec under the EventLoop so pipeline mutation is
    // single-threaded. Remove HTTP handlers in reverse install order;
    // tolerate missing entries (e.g. if `aggregateBody = false` ever
    // becomes the default for upgrade-friendly setups).
    val frameBridge = SuspendMessageBridge(WsFrame::class)
    withContext(channel.ioDispatcher) {
        runCatching { channel.pipeline.remove("bridge") }
        runCatching { channel.pipeline.remove("aggregator") }
        runCatching { channel.pipeline.remove("decoder") }
        runCatching { channel.pipeline.remove("encoder") }
        channel.addWsServerCodec()
        channel.pipeline.addLast("ws-bridge", frameBridge)
    }

    // (4) Run the user handler with the session. Forwarding pump runs
    // concurrently to feed `incoming` and process control frames; we
    // cancel it after the handler returns.
    val session = WsSessionImpl(channel, frameBridge)
    val pump = scope.launch { session.runForward() }
    try {
        session.handler()
    } finally {
        // (5) Close handshake — runs after the user handler returns,
        // so any frames the user `send`s before observing `incoming`
        // close are guaranteed to have been queued to the pipeline
        // before the CLOSE goes out (RFC 6455 §5.5.1 ordering). If
        // the peer initiated the close, echo their code/reason; if
        // we are tearing down for any other reason, send NORMAL.
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
