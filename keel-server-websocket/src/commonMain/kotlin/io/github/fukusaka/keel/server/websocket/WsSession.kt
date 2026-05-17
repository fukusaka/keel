package io.github.fukusaka.keel.server.websocket

import io.github.fukusaka.keel.codec.websocket.WsCloseCode
import io.github.fukusaka.keel.codec.websocket.WsFrame
import kotlinx.coroutines.channels.ReceiveChannel

/** A WebSocket handler block — runs against an open [WsSession]. */
public typealias WebSocketHandler = suspend WsSession.() -> Unit

/**
 * A bidirectional WebSocket session driven by keel's
 * [WsFrameDecoder][io.github.fukusaka.keel.codec.websocket.WsFrameDecoder] /
 * [WsFrameEncoder][io.github.fukusaka.keel.codec.websocket.WsFrameEncoder]
 * pipeline handlers.
 *
 * Handed to user code by [runWebSocketUpgrade] once an `Upgrade: websocket`
 * request has completed the RFC 6455 handshake. The session lives until
 * the handler returns or throws — at which point the closing handshake is
 * sent and the connection torn down.
 *
 * ### Receive ([incoming])
 *
 * Application data frames (`TEXT`, `BINARY`, fragmented `CONTINUATION`)
 * are delivered through [incoming]. Control frames are handled by the
 * implementation and not exposed here:
 *
 * - `PING` is auto-replied with a matching `PONG`.
 * - `PONG` is dropped.
 * - `CLOSE` from the peer closes [incoming]; the implementation echoes
 *   a `CLOSE` back and the handler should return.
 *
 * Fragment reassembly is **not** performed. A consumer that wants whole
 * messages must collect `CONTINUATION` frames until `fin = true`. The
 * raw-frame surface keeps zero-copy potential and stays close to the
 * pipeline layer.
 *
 * ### Send ([send])
 *
 * [send] writes a single frame to the pipeline and flushes it. The
 * caller owns the [WsFrame] and its `payload`; the encoder allocates
 * the wire-format `IoBuf`, so the input frame is not consumed.
 *
 * ### Lifecycle ([close])
 *
 * [close] sends a CLOSE frame with [code] and [reason] and closes
 * [incoming]. Subsequent [close] / [send] calls are no-ops on an
 * already-closed session. The handler's coroutine itself is **not**
 * cancelled — it observes the close by [incoming] returning EOF and
 * should return normally; structured cancellation across cooperative
 * sub-jobs is the handler's responsibility (e.g. wrap in
 * `coroutineScope { ... }`).
 */
public interface WsSession {

    /**
     * Channel of inbound application-data frames (TEXT / BINARY /
     * CONTINUATION). Closes when the peer sends CLOSE, the connection
     * drops, or [close] is invoked.
     */
    public val incoming: ReceiveChannel<WsFrame>

    /** Sends one frame to the peer and flushes the underlying channel. */
    public suspend fun send(frame: WsFrame)

    /** Sends CLOSE and shuts the session down. Safe to call multiple times. */
    public suspend fun close(code: WsCloseCode = WsCloseCode.NORMAL_CLOSURE, reason: String = "")
}
