package io.github.fukusaka.keel.engine.netty

import io.github.fukusaka.keel.codec.http.HttpBodyEnd
import io.github.fukusaka.keel.codec.http.HttpHeaderName
import io.github.fukusaka.keel.codec.http.HttpHeaders
import io.github.fukusaka.keel.codec.http.HttpRequestHead
import io.github.fukusaka.keel.codec.http.HttpResponseHead
import io.github.fukusaka.keel.codec.http.HttpStatus
import io.github.fukusaka.keel.codec.http.HttpVersion
import io.github.fukusaka.keel.codec.websocket.WsFrame
import io.github.fukusaka.keel.codec.websocket.WsFrameDecoder
import io.github.fukusaka.keel.codec.websocket.WsFrameEncoder
import io.github.fukusaka.keel.codec.websocket.WsOpcode
import io.github.fukusaka.keel.codec.websocket.computeAcceptKey
import io.github.fukusaka.keel.pipeline.InboundHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext

/**
 * Test-only `InboundHandler` that mirrors `BenchmarkRoutingHandler`'s
 * `/ws-echo` semantics: process the HTTP→WS upgrade handshake, swap the
 * pipeline from HTTP codec to WS codec, then echo every text/binary
 * frame and respond to PING/CLOSE per the protocol.
 *
 * Shared between [NettyPipelineWsEchoTest] (integration smoke against a
 * real Netty engine + JDK HttpClient) and [NettyPipelineWsEchoSeamTest]
 * (deterministic seam test against a `TestIoTransport`). Both tests
 * exercise the same handler so a regression in either layer surfaces
 * uniformly; lifting the handler out of the integration test's private
 * scope avoids the seam test having to reimplement an equivalent.
 *
 * @param postUpgradeMode If true, the handler starts in echo mode and
 *   skips the HTTP upgrade dance — used by the seam test to drive
 *   `WsFrame` events directly without first staging an HTTP request.
 */
internal class WsEchoHandler(
    postUpgradeMode: Boolean = false,
) : InboundHandler {
    private var wsUpgradePending = false
    private var wsClientKey: String? = null
    private var wsEchoMode: Boolean = postUpgradeMode

    override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
        when (msg) {
            is HttpRequestHead -> {
                if (msg.path == "/ws-echo" && isWebSocketUpgrade(msg)) {
                    wsUpgradePending = true
                    wsClientKey = msg.headers["Sec-WebSocket-Key"]
                }
            }
            is HttpBodyEnd -> {
                if (wsUpgradePending) {
                    val acceptKey = computeAcceptKey(wsClientKey!!)
                    ctx.propagateWrite(
                        HttpResponseHead(
                            status = HttpStatus(101),
                            version = HttpVersion.HTTP_1_1,
                            headers = HttpHeaders.of(
                                HttpHeaderName.UPGRADE to "websocket",
                                HttpHeaderName.CONNECTION to "Upgrade",
                                "Sec-WebSocket-Accept" to acceptKey,
                            ),
                        ),
                    )
                    ctx.propagateWrite(HttpBodyEnd.EMPTY)
                    ctx.propagateFlush()
                    ctx.channel.pipeline.remove("decoder")
                    ctx.channel.pipeline.remove("encoder")
                    ctx.channel.pipeline.addBefore(ctx.name, "ws-encoder", WsFrameEncoder())
                    ctx.channel.pipeline.addBefore(ctx.name, "ws-decoder", WsFrameDecoder())
                    wsUpgradePending = false
                    wsClientKey = null
                    wsEchoMode = true
                }
                msg.content.release()
            }
            is WsFrame -> {
                if (wsEchoMode) {
                    when (msg.opcode) {
                        WsOpcode.CLOSE -> {
                            ctx.propagateWrite(
                                WsFrame(fin = true, opcode = WsOpcode.CLOSE, payload = msg.payload),
                            )
                            ctx.propagateFlush()
                            wsEchoMode = false
                        }
                        WsOpcode.PING -> {
                            ctx.propagateWrite(WsFrame.pong(msg.payload))
                            ctx.propagateFlush()
                        }
                        else -> {
                            val outgoing = if (msg.maskKey != null) msg.copy(maskKey = null) else msg
                            ctx.propagateWrite(outgoing)
                            ctx.propagateFlush()
                        }
                    }
                }
            }
            else -> ctx.propagateRead(msg)
        }
    }

    private fun isWebSocketUpgrade(head: HttpRequestHead): Boolean {
        val upgrade = head.headers[HttpHeaderName.UPGRADE] ?: return false
        if (!upgrade.equals("websocket", ignoreCase = true)) return false
        val connection = head.headers[HttpHeaderName.CONNECTION] ?: return false
        if (!connection.split(',').any { it.trim().equals("upgrade", ignoreCase = true) }) return false
        if (head.headers["Sec-WebSocket-Version"] != "13") return false
        return head.headers["Sec-WebSocket-Key"] != null
    }
}
