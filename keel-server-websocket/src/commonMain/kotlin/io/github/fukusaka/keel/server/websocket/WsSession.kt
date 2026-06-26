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
 * ### Receive ([onMessage] / [incoming])
 *
 * Whole application **messages** ([WsMessage.Text] / [WsMessage.Binary] /
 * [WsMessage.BinaryChunks]) are delivered. Fragmented messages are
 * reassembled per RFC 6455 §5.4 — a consumer never sees `CONTINUATION`
 * frames. Control frames are handled by the implementation and not exposed:
 *
 * - `PING` is auto-replied with a matching `PONG`.
 * - `PONG` is dropped.
 * - `CLOSE` from the peer closes the stream; the implementation echoes
 *   a `CLOSE` back and the handler should return.
 *
 * A TEXT message is delivered only once its payload has been validated
 * as UTF-8 (RFC 6455 §8.1); a fragmentation violation or an invalid
 * TEXT payload fails the connection with the matching CLOSE code
 * (`1002` / `1007` / `1009`) and closes the stream.
 *
 * **Prefer [onMessage]**: a binary message may arrive as a pooled
 * [WsMessage.BinaryChunks] (zero-copy), whose chunks the consumer must
 * release. [onMessage] is the leak-safe default — it releases each
 * message's pooled chunks after the block returns (suppressed when the
 * block forwards the message via [send]). Iterating [incoming] directly is
 * the raw form: the consumer then owns every delivered [WsMessage.BinaryChunks]
 * and MUST `chunks.release()` it (or hand it to [send], which transfers
 * ownership).
 *
 * ### Send ([send])
 *
 * [send] writes to the pipeline and flushes it. The frame-level
 * [send] overload lets the caller control fragmentation directly;
 * [send] of a [WsMessage] / [String] / [ByteArray] sends a single
 * unfragmented frame. The caller owns any [WsFrame] and its `payload`;
 * the encoder allocates the wire-format `IoBuf`, so the input is not
 * consumed.
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
     * Path parameters bound by the `Router` for the matched WebSocket
     * route: each `:name` pattern segment maps to the corresponding
     * request segment, and a trailing `*` wildcard maps the key `"*"`
     * to the remaining path. Empty when the route pattern has no
     * parameters.
     *
     * For `webSocket("/chat/:room")` matched by a request to
     * `/chat/general`, `pathParameters["room"]` is `"general"`. Mirrors
     * [HttpCall.pathParameters][io.github.fukusaka.keel.server.http.HttpCall.pathParameters].
     */
    public val pathParameters: Map<String, String>

    /**
     * Channel of inbound application messages, with fragmented
     * messages reassembled per RFC 6455 §5.4. Closes when the peer
     * sends CLOSE, the connection drops, [close] is invoked, or a
     * protocol error fails the connection.
     *
     * **Ownership**: a [WsMessage.BinaryChunks] delivered here is the
     * consumer's to release (`chunks.release()`), or to hand to [send]
     * (which transfers ownership to the transport). Forgetting both leaks
     * the pooled backing — use [onMessage] to have that handled for you.
     */
    public val incoming: ReceiveChannel<WsMessage>

    /**
     * Consumes [incoming] messages, invoking [block] for each, and — the
     * leak-safe part — **releases a [WsMessage.BinaryChunks]' pooled chunks
     * after [block] returns** so a consumer never has to manage pooled
     * lifetimes by hand.
     *
     * Inside [block] the message (and a `BinaryChunks`' pooled buffers) is
     * valid to read. To **echo** a message, call [send] with it inside the
     * block: ownership of a `BinaryChunks`' chunks transfers to the transport
     * and the post-block release is suppressed, so a plain
     * `onMessage { send(it) }` neither leaks nor double-frees. Anything the
     * block does not send is released for it. The block MUST NOT retain the
     * message past its return — the pooled buffers may be recycled
     * immediately after.
     *
     * Returns when [incoming] closes (peer CLOSE, connection drop, [close],
     * or a protocol error). Do not also iterate [incoming] directly — both
     * consume the same stream.
     *
     * The default implementation releases each [WsMessage.BinaryChunks]'
     * chunks after the block (sufficient for implementations that never
     * deliver pooled messages); the keel server session overrides it to also
     * suppress the release when the block forwarded the message via [send].
     */
    public suspend fun onMessage(block: suspend (WsMessage) -> Unit) {
        for (message in incoming) {
            try {
                block(message)
            } finally {
                (message as? WsMessage.BinaryChunks)?.chunks?.release()
            }
        }
    }

    /** Sends one frame to the peer and flushes the underlying channel. */
    public suspend fun send(frame: WsFrame)

    /** Sends [message] as a single unfragmented data frame. */
    public suspend fun send(message: WsMessage)

    /** Sends [text] as a single unfragmented TEXT message. */
    public suspend fun send(text: String)

    /** Sends [bytes] as a single unfragmented BINARY message. */
    public suspend fun send(bytes: ByteArray)

    /** Sends CLOSE and shuts the session down. Safe to call multiple times. */
    public suspend fun close(code: WsCloseCode = WsCloseCode.NORMAL_CLOSURE, reason: String = "")
}
