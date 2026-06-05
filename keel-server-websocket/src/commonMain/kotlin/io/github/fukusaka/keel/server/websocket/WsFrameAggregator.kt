package io.github.fukusaka.keel.server.websocket

import io.github.fukusaka.keel.codec.websocket.WsFrame
import io.github.fukusaka.keel.codec.websocket.WsOpcode
import io.github.fukusaka.keel.compression.DecompressionLimitException
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import io.github.fukusaka.keel.logging.debug
import io.github.fukusaka.keel.logging.warn

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
 * @param logger connection-scoped logger used to record the **cause** of a
 *   decompression failure. The failure itself is surfaced to the peer as a
 *   generic close frame (the wire `reason` must not leak internal codec
 *   detail), so the actual exception — which is the only thing that tells an
 *   operator whether it was a corrupt back-reference, a context-takeover /
 *   windowBits negotiation bug, or genuine wire corruption — is logged here
 *   instead. Defaults to no-op for seam tests.
 */
internal class WsFrameAggregator(
    private val inflater: WsMessageInflater? = null,
    private val logger: Logger = NoopLoggerFactory.logger(""),
) {

    /** Opcode of the message in progress, or null when none is open. */
    private var messageOpcode: WsOpcode? = null

    /**
     * 1-based ordinal of the message currently being completed, used only to
     * identify *which* message failed in a decompression-fault log line on a
     * long-lived connection (M2). Incremented per completed message.
     */
    private var messageOrdinal: Long = 0

    /**
     * Fragments of the message in progress, kept as separate chunks and
     * joined once on the final frame ([joinChunks]). Appending a reference
     * here is O(1); the previous `accumulated += payload` per frame was
     * O(n²) — a 1 MiB message split into 256 × 4 KiB frames copied ~131 MiB
     * and dominated large-message CPU. The single join at completion copies
     * each byte exactly once.
     */
    private val chunks = ArrayList<ByteArray>()

    /** Running total of [chunks] sizes, for the [checkSize] cap. */
    private var bufferedSize = 0

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
            // The decoder hands each frame a freshly-allocated payload, so
            // retaining the reference (no copy) is safe — the join at
            // completion is the only copy.
            chunks.add(frame.payload)
            bufferedSize = frame.payload.size
            WsAggregateResult.Incomplete
        }
    }

    private fun feedContinuation(frame: WsFrame): WsAggregateResult {
        val opcode = messageOpcode
            ?: return protocolError("CONTINUATION frame with no message in progress")
        val sizeError = checkSize(bufferedSize.toLong() + frame.payload.size)
        if (sizeError != null) {
            reset()
            return sizeError
        }
        chunks.add(frame.payload)
        bufferedSize += frame.payload.size
        return if (frame.fin) {
            val payload = joinChunks()
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
        messageOrdinal++
        val bytes = if (compressed) {
            when (val r = inflate(payload)) {
                is InflateResult.Ok -> {
                    val sizeError = checkSize(r.bytes.size.toLong())
                    if (sizeError != null) return sizeError
                    r.bytes
                }
                // RFC 6455 §7.4.1: 1002 = protocol error (we cannot read the
                // bytes the peer sent, framing-level confusion equivalent).
                InflateResult.Malformed -> return WsAggregateResult.ProtocolError(
                    closeCode = PROTOCOL_ERROR_CODE,
                    reason = "permessage-deflate decompression failed",
                )
                // RFC 6455 §7.4.1: 1009 = message too big. The codec's
                // maxOutputSize cap can fire before the aggregator's own
                // size check on inflated.size — without the dispatch below
                // both paths collapsed to 1002, which mislead a client
                // about whether to retry with the same payload (no — still
                // too big) or treat it as a transient framing error (no —
                // semantic).
                InflateResult.TooBig -> return WsAggregateResult.ProtocolError(
                    closeCode = MESSAGE_TOO_BIG_CODE,
                    reason = "permessage-deflate decompression cap exceeded",
                )
            }
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
     * Inflates [payload] via [inflater], distinguishing the two failure
     * modes the WS close-code spec assigns separate values to:
     *
     * - [DecompressionLimitException] (codec's `maxOutputSize` /
     *   `maxRatio` cap) → [InflateResult.TooBig] → close `1009`.
     * - Any other [Throwable] (malformed DEFLATE bytes, bad dictionary,
     *   stray Z_DATA_ERROR, etc.) → [InflateResult.Malformed] → close
     *   `1002`.
     *
     * [inflater] is non-null whenever a message is flagged compressed —
     * [feedDataStart] rejects an RSV1 frame upfront when no inflater is
     * configured.
     */
    private fun inflate(payload: ByteArray): InflateResult {
        val decompressor = inflater ?: return InflateResult.Malformed
        // The peer only ever receives a generic close frame (the wire `reason`
        // must not leak internal codec detail), so the cause is logged here —
        // it is the only signal that tells an operator whether this was a
        // corrupt back-reference, a context-takeover / windowBits negotiation
        // bug, or genuine wire corruption, and on a long-lived connection the
        // message ordinal says which message failed.
        return try {
            InflateResult.Ok(decompressor.inflate(payload))
        } catch (e: DecompressionLimitException) {
            logger.debug { "permessage-deflate message #$messageOrdinal exceeded the decompression cap: ${e.message}" }
            InflateResult.TooBig
        } catch (e: Throwable) {
            logger.warn(e) { "permessage-deflate inflate failed on message #$messageOrdinal: ${e.message}" }
            InflateResult.Malformed
        }
    }

    private sealed interface InflateResult {
        data class Ok(val bytes: ByteArray) : InflateResult
        data object Malformed : InflateResult
        data object TooBig : InflateResult
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
        chunks.clear()
        bufferedSize = 0
    }

    /**
     * Concatenates [chunks] into one contiguous payload, copying each byte
     * exactly once. Called once per completed fragmented message.
     */
    private fun joinChunks(): ByteArray {
        if (chunks.size == 1) return chunks[0]
        val joined = ByteArray(bufferedSize)
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(joined, offset)
            offset += chunk.size
        }
        return joined
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
