package io.github.fukusaka.keel.server.websocket

/**
 * A whole WebSocket application message, reassembled from one or more
 * frames per RFC 6455 §5.4.
 *
 * [WsSession.incoming] delivers [WsMessage]s rather than raw
 * [WsFrame][io.github.fukusaka.keel.codec.websocket.WsFrame]s:
 * `CONTINUATION` fragments are joined by [WsFrameAggregator] before a
 * message surfaces to user code. Control frames (`PING` / `PONG` /
 * `CLOSE`) never appear here — the session pump handles them.
 *
 * The two variants mirror the data opcodes:
 *
 * - [Text] carries an already-UTF-8-validated [String]. A message whose
 *   bytes are not valid UTF-8 fails the connection with CLOSE `1007`
 *   (RFC 6455 §8.1) and is never delivered.
 * - [Binary] carries the raw payload bytes.
 */
public sealed interface WsMessage {

    /**
     * A complete TEXT message. [text] has already been validated as
     * UTF-8 by the session pump, so consumers receive a well-formed
     * [String].
     */
    public data class Text(val text: String) : WsMessage

    /** A complete BINARY message carrying the raw payload [bytes]. */
    public class Binary(public val bytes: ByteArray) : WsMessage {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Binary) return false
            return bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int = bytes.contentHashCode()

        override fun toString(): String = "Binary(${bytes.size} bytes)"
    }
}
