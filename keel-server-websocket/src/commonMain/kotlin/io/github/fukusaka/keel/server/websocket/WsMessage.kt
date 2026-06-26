package io.github.fukusaka.keel.server.websocket

import io.github.fukusaka.keel.buf.IoBufChunks

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
 * - [Binary] carries the raw payload bytes in a heap [ByteArray].
 * - [BinaryChunks] carries the payload as pooled [IoBufChunks] (zero-copy),
 *   mirroring the HTTP side's pooled `receiveChunk` delivery.
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

    /**
     * A complete BINARY message whose payload is carried as pooled
     * [IoBufChunks] (zero-copy), avoiding the heap-`ByteArray` copy of
     * [Binary]. Mirrors the HTTP side's pooled
     * [io.github.fukusaka.keel.server.http.HttpCall.receiveChunk] delivery.
     *
     * **Ownership**: on the receive path ([WsSession.incoming]) the consumer
     * owns [chunks] and MUST call `chunks.release()` once done — the pooled
     * backing is not freed until then. On [WsSession.send] the session takes
     * ownership and releases [chunks] after the frame is written.
     */
    public class BinaryChunks(public val chunks: IoBufChunks) : WsMessage {
        override fun toString(): String = "BinaryChunks(${chunks.totalSize} bytes)"
    }
}
