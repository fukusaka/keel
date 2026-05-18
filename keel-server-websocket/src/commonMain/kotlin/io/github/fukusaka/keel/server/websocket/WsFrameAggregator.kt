package io.github.fukusaka.keel.server.websocket

import io.github.fukusaka.keel.codec.websocket.WsFrame
import io.github.fukusaka.keel.codec.websocket.WsOpcode

/**
 * Upper bound on a reassembled WebSocket message, in bytes (16 MiB).
 *
 * Caps the buffer an in-progress fragmented message may accumulate so a
 * peer cannot exhaust memory by streaming `CONTINUATION` frames without
 * ever setting `fin`. A message whose joined payload would exceed this
 * fails the connection with CLOSE `1009` (message too big).
 *
 * Hardcoded per the YAGNI policy — same approach as the HTTP `Range`
 * count cap. If a configurable limit is ever needed it can be added
 * additively via the `webSocket { }` builder without a breaking change.
 */
public const val MAX_WS_MESSAGE_SIZE: Int = 16 * 1024 * 1024

/**
 * Outcome of feeding one data frame to [WsFrameAggregator.feed].
 *
 * A pure result type — no exceptions on the protocol-error path — so the
 * session pump can map each variant to its RFC 6455 CLOSE code without a
 * try/catch.
 */
internal sealed interface WsAggregateResult {

    /** The frame was accepted but the message is still incomplete. */
    data object Incomplete : WsAggregateResult

    /** The frame completed a message; [message] is ready for delivery. */
    data class Completed(val message: WsMessage) : WsAggregateResult

    /**
     * The frame violated RFC 6455 fragmentation rules. [closeCode] is the
     * status code the connection must be failed with: `1002` for an
     * orphan `CONTINUATION` or an interleaved new data message, `1009`
     * when the accumulated message would exceed [MAX_WS_MESSAGE_SIZE].
     */
    data class ProtocolError(val closeCode: Int, val reason: String) : WsAggregateResult
}

/**
 * Pure state machine that reassembles WebSocket data frames into whole
 * messages per RFC 6455 §5.4.
 *
 * [feed] accepts data frames only — `TEXT`, `BINARY`, `CONTINUATION`.
 * Control frames (`PING` / `PONG` / `CLOSE`) interleave fragments freely
 * (§5.4) and are handled by the session pump *before* the aggregator, so
 * they must never reach [feed]; passing one is a programming error.
 *
 * The instance holds the partially-collected payload of the message in
 * progress. It is **not** thread-safe — the session pump drives it from
 * a single coroutine. Being a pure object with no I/O, it is exhaustively
 * unit-testable by feeding [WsFrame]s directly.
 */
internal class WsFrameAggregator {

    /** Opcode of the message in progress, or null when none is open. */
    private var messageOpcode: WsOpcode? = null

    /** Accumulated payload of the message in progress. */
    private var buffer = ByteArray(0)

    /**
     * Feeds one data frame and reports whether a message completed.
     *
     * @param frame a data frame (`TEXT` / `BINARY` / `CONTINUATION`).
     * @throws IllegalArgumentException if [frame] is a control frame.
     */
    fun feed(frame: WsFrame): WsAggregateResult {
        require(!frame.opcode.isControl) {
            "WsFrameAggregator.feed accepts data frames only, got ${frame.opcode}"
        }
        return when (frame.opcode) {
            WsOpcode.CONTINUATION -> feedContinuation(frame)
            WsOpcode.TEXT, WsOpcode.BINARY -> feedDataStart(frame)
            // isData covers exactly CONTINUATION/TEXT/BINARY; unreachable.
            else -> error("Unreachable data opcode: ${frame.opcode}")
        }
    }

    private fun feedDataStart(frame: WsFrame): WsAggregateResult {
        if (messageOpcode != null) {
            // RFC 6455 §5.4: a new TEXT/BINARY before the current
            // message's fin is an interleaved data message — forbidden.
            return protocolError("Interleaved ${frame.opcode} frame while a message is unfinished")
        }
        // The size cap applies to every message, fragmented or not — a
        // single oversized frame must fail just as a long fragment chain does.
        val sizeError = checkSize(frame.payload.size.toLong())
        if (sizeError != null) return sizeError
        return if (frame.fin) {
            completeMessage(frame.opcode, frame.payload)
        } else {
            messageOpcode = frame.opcode
            buffer = frame.payload.copyOf()
            WsAggregateResult.Incomplete
        }
    }

    private fun feedContinuation(frame: WsFrame): WsAggregateResult {
        val opcode = messageOpcode
            ?: return protocolError("CONTINUATION frame with no message in progress")
        val sizeError = checkSize(buffer.size.toLong() + frame.payload.size)
        if (sizeError != null) {
            reset()
            return sizeError
        }
        buffer += frame.payload
        return if (frame.fin) {
            val payload = buffer
            reset()
            completeMessage(opcode, payload)
        } else {
            WsAggregateResult.Incomplete
        }
    }

    /**
     * Builds the final [WsMessage]. A TEXT payload is decoded with
     * strict UTF-8 — invalid input yields a `1007` protocol error
     * (RFC 6455 §8.1) rather than a lossy decode.
     */
    private fun completeMessage(opcode: WsOpcode, payload: ByteArray): WsAggregateResult =
        when (opcode) {
            WsOpcode.TEXT -> {
                val text = decodeUtf8OrNull(payload)
                    ?: return WsAggregateResult.ProtocolError(
                        closeCode = INVALID_PAYLOAD_CODE,
                        reason = "TEXT message payload is not valid UTF-8",
                    )
                WsAggregateResult.Completed(WsMessage.Text(text))
            }
            else -> WsAggregateResult.Completed(WsMessage.Binary(payload))
        }

    /**
     * Returns a `1009` [WsAggregateResult.ProtocolError] when [total]
     * bytes would exceed [MAX_WS_MESSAGE_SIZE], or null when within limit.
     */
    private fun checkSize(total: Long): WsAggregateResult? =
        if (total > MAX_WS_MESSAGE_SIZE) {
            WsAggregateResult.ProtocolError(
                closeCode = MESSAGE_TOO_BIG_CODE,
                reason = "Message exceeds $MAX_WS_MESSAGE_SIZE bytes",
            )
        } else {
            null
        }

    /** Drops the in-progress state and returns a `1002` protocol error. */
    private fun protocolError(reason: String): WsAggregateResult {
        reset()
        return WsAggregateResult.ProtocolError(closeCode = PROTOCOL_ERROR_CODE, reason = reason)
    }

    private fun reset() {
        messageOpcode = null
        buffer = ByteArray(0)
    }

    companion object {
        /** RFC 6455 §7.4.1 — protocol error. */
        const val PROTOCOL_ERROR_CODE: Int = 1002

        /** RFC 6455 §7.4.1 — message too big. */
        const val MESSAGE_TOO_BIG_CODE: Int = 1009

        /** RFC 6455 §7.4.1 / §8.1 — invalid frame payload data. */
        const val INVALID_PAYLOAD_CODE: Int = 1007
    }
}

/**
 * Decodes [bytes] as strict UTF-8, returning null when the input is not
 * well-formed.
 *
 * `ByteArray.decodeToString()` is lenient by default — it substitutes
 * the replacement character for malformed sequences. RFC 6455 §8.1
 * mandates that a TEXT message with invalid UTF-8 fail the connection,
 * so the aggregator needs a strict, reject-on-error decode.
 */
internal fun decodeUtf8OrNull(bytes: ByteArray): String? =
    runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }
        .getOrNull()
