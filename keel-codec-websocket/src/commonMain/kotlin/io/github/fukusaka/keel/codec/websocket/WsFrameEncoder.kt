package io.github.fukusaka.keel.codec.websocket

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.pipeline.OutboundHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext

/**
 * Pipeline handler that encodes [WsFrame] messages into wire-format
 * [IoBuf] (RFC 6455 §5.2).
 *
 * Sits on the outbound side of the pipeline after the WebSocket
 * handshake. Each [WsFrame] received via [onWrite] is serialised
 * directly into a fresh exact-sized [IoBuf] — header bytes, extended
 * length, mask key, and payload — and propagated outbound toward the
 * transport.
 *
 * Non-frame messages (e.g. raw [IoBuf] written directly by an
 * application handler) pass through unchanged so application code that
 * bypasses the codec stays compatible.
 *
 * **Zero extra copies**: writes go straight into the IoBuf via
 * [IoBuf.writeByte] / [IoBuf.writeByteArray]. The only unavoidable
 * data copy is `frame.payload` (a `ByteArray`) → IoBuf bytes; that is
 * inherent to [WsFrame.payload] being a `ByteArray` and matches the
 * single-copy pattern used by the HTTP response encoder for a
 * fixed-length response body. No kotlinx-io `Buffer` intermediate and
 * no scratch `ByteArray` between Buffer and IoBuf.
 *
 * **Server vs client**: server frames MUST NOT be masked (RFC 6455
 * §5.1) and the [WsFrame.maskKey] should therefore be `null`. The
 * encoder does not enforce this — callers are trusted to construct
 * frames with the correct mask state for their role. Masked frames
 * apply the mask byte-by-byte during the payload write so no scratch
 * buffer is needed.
 */
public class WsFrameEncoder : OutboundHandler {

    override fun onWrite(ctx: PipelineHandlerContext, msg: Any) {
        if (msg !is WsFrame) {
            ctx.propagateWrite(msg)
            return
        }
        val wireSize = calculateWireSize(msg)
        val ioBuf = ctx.allocator.allocate(wireSize)
        try {
            writeFrameTo(msg, ioBuf)
        } catch (t: Throwable) {
            ioBuf.release()
            throw t
        }
        ctx.propagateWrite(ioBuf)
    }

    /**
     * Returns the exact byte count `frame` will occupy on the wire so
     * the IoBuf is allocated once at the right size — no over-allocation
     * and no resize.
     */
    private fun calculateWireSize(frame: WsFrame): Int {
        var size = HEADER_SIZE
        val payloadLen = frame.payload.size
        size += when {
            payloadLen <= PAYLOAD_LEN_7BIT_MAX -> 0
            payloadLen <= PAYLOAD_LEN_16BIT_MAX -> EXT_LEN_16
            else -> EXT_LEN_64
        }
        if (frame.maskKey != null) size += MASK_KEY_SIZE
        size += payloadLen
        return size
    }

    /**
     * Writes the frame's wire bytes directly into [buf]. Caller is
     * responsible for sizing the IoBuf (via [calculateWireSize]) and
     * releasing on exception (the [onWrite] try/catch covers it).
     */
    @Suppress("CyclomaticComplexMethod") // RFC 6455 §5.2 has 3 length classes × 2 mask states.
    private fun writeFrameTo(frame: WsFrame, buf: IoBuf) {
        // Byte 0: FIN (bit 7) + RSV1-3 (bits 6-4) + opcode (bits 3-0).
        var byte0 = frame.opcode.code and OPCODE_MASK
        if (frame.fin) byte0 = byte0 or BIT_FIN
        if (frame.rsv1) byte0 = byte0 or BIT_RSV1
        if (frame.rsv2) byte0 = byte0 or BIT_RSV2
        if (frame.rsv3) byte0 = byte0 or BIT_RSV3
        buf.writeByte(byte0.toByte())

        // Byte 1: MASK (bit 7) + payload-len-7 (bits 6-0).
        val payloadLen = frame.payload.size
        val masked = frame.maskKey != null
        val maskBit = if (masked) BIT_MASK else 0
        when {
            payloadLen <= PAYLOAD_LEN_7BIT_MAX -> {
                buf.writeByte((maskBit or payloadLen).toByte())
            }
            payloadLen <= PAYLOAD_LEN_16BIT_MAX -> {
                buf.writeByte((maskBit or PAYLOAD_LEN_SENTINEL_16).toByte())
                // Big-endian 16-bit length.
                buf.writeByte(((payloadLen shr SHIFT_8) and BYTE_MASK).toByte())
                buf.writeByte((payloadLen and BYTE_MASK).toByte())
            }
            else -> {
                buf.writeByte((maskBit or PAYLOAD_LEN_SENTINEL_64).toByte())
                // Big-endian 64-bit length. Java `Int` caps at 2 GiB so
                // the high 4 bytes are always zero — write them
                // unconditionally to match RFC 6455's 8-byte field.
                buf.writeByte(0)
                buf.writeByte(0)
                buf.writeByte(0)
                buf.writeByte(0)
                buf.writeByte(((payloadLen ushr SHIFT_24) and BYTE_MASK).toByte())
                buf.writeByte(((payloadLen ushr SHIFT_16) and BYTE_MASK).toByte())
                buf.writeByte(((payloadLen ushr SHIFT_8) and BYTE_MASK).toByte())
                buf.writeByte((payloadLen and BYTE_MASK).toByte())
            }
        }

        // Mask key (if present) followed by payload — masked frames
        // XOR each payload byte with the corresponding mask byte during
        // the write so no temporary masked buffer is allocated.
        if (frame.maskKey != null) {
            val key = frame.maskKey
            buf.writeByte((key ushr SHIFT_24).toByte())
            buf.writeByte(((key ushr SHIFT_16) and BYTE_MASK).toByte())
            buf.writeByte(((key ushr SHIFT_8) and BYTE_MASK).toByte())
            buf.writeByte((key and BYTE_MASK).toByte())
            // Per-byte masking. For 16 MiB payload that's 16M XORs;
            // measurable but unavoidable when sending masked frames
            // (the mask is part of the wire format). On the server
            // side `frame.maskKey` is typically null and this branch
            // is skipped.
            val maskBytes = byteArrayOf(
                (key ushr SHIFT_24).toByte(),
                ((key ushr SHIFT_16) and BYTE_MASK).toByte(),
                ((key ushr SHIFT_8) and BYTE_MASK).toByte(),
                (key and BYTE_MASK).toByte(),
            )
            val payload = frame.payload
            for (i in payload.indices) {
                buf.writeByte((payload[i].toInt() xor maskBytes[i and MASK_MOD_4].toInt()).toByte())
            }
        } else {
            // Unmasked (server) path: bulk copy payload directly into
            // the IoBuf via the platform-optimised `writeByteArray`.
            if (frame.payload.isNotEmpty()) {
                buf.writeByteArray(frame.payload, 0, frame.payload.size)
            }
        }
    }

    private companion object {
        // --- Frame layout sizes (RFC 6455 §5.2) ---
        private const val HEADER_SIZE = 2
        private const val MASK_KEY_SIZE = 4
        private const val EXT_LEN_16 = 2
        private const val EXT_LEN_64 = 8

        // --- Length encoding sentinels and bounds ---
        private const val PAYLOAD_LEN_7BIT_MAX = 125
        private const val PAYLOAD_LEN_16BIT_MAX = 65535
        private const val PAYLOAD_LEN_SENTINEL_16 = 126
        private const val PAYLOAD_LEN_SENTINEL_64 = 127

        // --- Bit fields ---
        private const val BIT_FIN = 0x80
        private const val BIT_RSV1 = 0x40
        private const val BIT_RSV2 = 0x20
        private const val BIT_RSV3 = 0x10
        private const val BIT_MASK = 0x80
        private const val OPCODE_MASK = 0x0F
        private const val BYTE_MASK = 0xFF

        // --- Bit shift counts ---
        private const val SHIFT_8 = 8
        private const val SHIFT_16 = 16
        private const val SHIFT_24 = 24

        /** `i and MASK_MOD_4` is equivalent to `i % 4` for non-negative i. */
        private const val MASK_MOD_4 = 0x3
    }
}
