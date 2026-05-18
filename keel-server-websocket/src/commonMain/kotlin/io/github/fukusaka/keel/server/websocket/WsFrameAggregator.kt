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
 * Inflates one `permessage-deflate` compressed message payload
 * (RFC 7692 §7.2.2).
 *
 * Injected into [WsFrameAggregator] so the aggregator stays a pure
 * fragment-reassembly state machine with no compression-backend
 * dependency. Implemented by [WsPermessageDeflate.decompress].
 */
internal fun interface WsMessageInflater {
    /**
     * Decompresses [compressed] (the assembled raw-DEFLATE message
     * payload) into the original bytes.
     *
     * @throws io.github.fukusaka.keel.compression.DecompressionException
     *   on malformed input.
     */
    fun inflate(compressed: ByteArray): ByteArray
}

/**
 * Pure-ish state machine that reassembles WebSocket data frames into
 * whole messages per RFC 6455 §5.4, with optional `permessage-deflate`
 * decompression (RFC 7692 §7.2.2).
 *
 * [feed] accepts data frames only — `TEXT`, `BINARY`, `CONTINUATION`.
 * Control frames (`PING` / `PONG` / `CLOSE`) interleave fragments freely
 * (§5.4) and are handled by the session pump *before* the aggregator, so
 * they must never reach [feed]; passing one is a programming error.
 *
 * **Compression**: the RSV1 bit of the *opening* frame of a message
 * marks it as DEFLATE-compressed (RFC 7692 §7.2). When [inflater] is
 * non-null the assembled payload is inflated before the [WsMessage] is
 * built and TEXT UTF-8 validation runs on the *decompressed* bytes. An
 * RSV1=1 frame with no [inflater] configured is a protocol error
 * (RFC 6455 §5.2 — a reserved bit set with no negotiated extension);
 * the connection fails with CLOSE `1002`. The [MAX_WS_MESSAGE_SIZE] cap
 * applies to the *decompressed* size as a zip-bomb defence — a message
 * inflating past the cap fails with CLOSE `1009`.
 *
 * The instance holds the partially-collected payload of the message in
 * progress. It is **not** thread-safe — the session pump drives it from
 * a single coroutine. Being a pure object with no I/O, it is exhaustively
 * unit-testable by feeding [WsFrame]s directly.
 *
 * @param inflater decompressor for `permessage-deflate` messages, or
 *   null when the connection negotiated no extension.
 */
internal class WsFrameAggregator(
    private val inflater: WsMessageInflater? = null,
) {

    /** Opcode of the message in progress, or null when none is open. */
    private var messageOpcode: WsOpcode? = null

    /** Accumulated payload of the message in progress. */
    private var buffer = ByteArray(0)

    /**
     * RSV1 bit of the message's opening frame — true when the message
     * is `permessage-deflate` compressed (RFC 7692 §7.2). Only meaningful
     * while [messageOpcode] is non-null.
     */
    private var messageCompressed = false

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
        // RFC 7692 §7.2: RSV1 on the opening frame marks the message as
        // compressed. RSV1 set with no negotiated extension is a §5.2
        // protocol error.
        if (frame.rsv1 && inflater == null) {
            return protocolError("RSV1 set on ${frame.opcode} frame but permessage-deflate was not negotiated")
        }
        // The size cap applies to every message, fragmented or not — a
        // single oversized frame must fail just as a long fragment chain
        // does. For a compressed message this caps the *compressed*
        // accumulation; the decompressed size is re-checked on completion.
        val sizeError = checkSize(frame.payload.size.toLong())
        if (sizeError != null) return sizeError
        return if (frame.fin) {
            completeMessage(frame.opcode, frame.payload, frame.rsv1)
        } else {
            messageOpcode = frame.opcode
            messageCompressed = frame.rsv1
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
            val compressed = messageCompressed
            reset()
            completeMessage(opcode, payload, compressed)
        } else {
            WsAggregateResult.Incomplete
        }
    }

    /**
     * Builds the final [WsMessage]. When [compressed] is true the
     * assembled [payload] is inflated first (RFC 7692 §7.2.2) and the
     * decompressed size re-checked against [MAX_WS_MESSAGE_SIZE] (a
     * zip-bomb defence). A TEXT payload is decoded with strict UTF-8 on
     * the decompressed bytes — invalid input yields a `1007` protocol
     * error (RFC 6455 §8.1) rather than a lossy decode.
     */
    private fun completeMessage(opcode: WsOpcode, payload: ByteArray, compressed: Boolean): WsAggregateResult {
        val bytes = if (compressed) {
            val inflated = inflateOrError(payload) ?: return WsAggregateResult.ProtocolError(
                closeCode = PROTOCOL_ERROR_CODE,
                reason = "permessage-deflate decompression failed",
            )
            val sizeError = checkSize(inflated.size.toLong())
            if (sizeError != null) return sizeError
            inflated
        } else {
            payload
        }
        return when (opcode) {
            WsOpcode.TEXT -> {
                val text = decodeUtf8OrNull(bytes)
                    ?: return WsAggregateResult.ProtocolError(
                        closeCode = INVALID_PAYLOAD_CODE,
                        reason = "TEXT message payload is not valid UTF-8",
                    )
                WsAggregateResult.Completed(WsMessage.Text(text))
            }
            else -> WsAggregateResult.Completed(WsMessage.Binary(bytes))
        }
    }

    /**
     * Inflates [payload] via [inflater], returning null when the
     * backend rejects the input as malformed (mapped to a `1002`
     * protocol error by the caller). [inflater] is non-null whenever a
     * message is flagged compressed — [feedDataStart] rejects an RSV1
     * frame upfront when no inflater is configured.
     */
    private fun inflateOrError(payload: ByteArray): ByteArray? {
        val decompressor = inflater ?: return null
        return runCatching { decompressor.inflate(payload) }.getOrNull()
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
        messageCompressed = false
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
