package io.github.fukusaka.keel.codec.websocket

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.IoBufChunks
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
 * [IoBuf.writeByte] / [IoBuf.writeByteArray]. For a [WsFrame.payload]
 * `ByteArray` the only copy is that array → IoBuf bytes (inherent to the
 * payload being a `ByteArray`, matching the HTTP response encoder's
 * fixed-length-body pattern). A frame carrying [WsFrame.payloadChunks]
 * (e.g. `permessage-deflate` output) skips that copy entirely: the header
 * goes into a small IoBuf and the pooled payload chunks are gather-written
 * as-is. No kotlinx-io `Buffer` intermediate, no scratch `ByteArray`.
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
        val chunks = msg.payloadChunks
        if (chunks != null) {
            writeChunkedFrame(ctx, msg, chunks)
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
     * Gather-writes a frame whose payload is already a list of pooled
     * [IoBuf] chunks ([WsFrame.payloadChunks]): the header goes into a
     * small fresh IoBuf (sized from [IoBufChunks.totalSize]) and each
     * payload chunk is propagated as-is. The transport coalesces them into
     * one `writev` and releases each — so the compressed bytes never copy
     * into a contiguous `ByteArray`. Server-outbound only (unmasked, per
     * the [WsFrame] invariant). The frame owns the chunks; on any failure
     * before hand-off the un-propagated chunks are released here.
     */
    private fun writeChunkedFrame(ctx: PipelineHandlerContext, frame: WsFrame, chunks: IoBufChunks) {
        val headerBuf = ctx.allocator.allocate(headerSize(frame, chunks.totalSize))
        try {
            writeHeader(frame, headerBuf, chunks.totalSize)
        } catch (t: Throwable) {
            headerBuf.release()
            chunks.release()
            throw t
        }
        ctx.propagateWrite(headerBuf)
        var i = 0
        try {
            while (i < chunks.chunkCount) {
                ctx.propagateWrite(chunks.chunkAt(i))
                i++
            }
        } catch (t: Throwable) {
            while (i < chunks.chunkCount) {
                chunks.chunkAt(i).release()
                i++
            }
            throw t
        }
    }

    /**
     * Returns the exact byte count `frame` will occupy on the wire so
     * the IoBuf is allocated once at the right size — no over-allocation
     * and no resize.
     */
    private fun calculateWireSize(frame: WsFrame): Int =
        headerSize(frame, frame.payload.size) + frame.payload.size

    /** Byte count of the frame header (control byte + length + optional mask key). */
    private fun headerSize(frame: WsFrame, payloadLen: Int): Int {
        var size = HEADER_SIZE
        size += when {
            payloadLen <= PAYLOAD_LEN_7BIT_MAX -> 0
            payloadLen <= PAYLOAD_LEN_16BIT_MAX -> EXT_LEN_16
            else -> EXT_LEN_64
        }
        if (frame.maskKey != null) size += MASK_KEY_SIZE
        return size
    }

    /**
     * Writes the frame's wire bytes directly into [buf]. Caller is
     * responsible for sizing the IoBuf (via [calculateWireSize]) and
     * releasing on exception (the [onWrite] try/catch covers it).
     */
    private fun writeFrameTo(frame: WsFrame, buf: IoBuf) {
        writeHeader(frame, buf, frame.payload.size)

        // Mask key already written by writeHeader; now the payload — masked
        // frames XOR each byte with the corresponding mask byte during the
        // write so no temporary masked buffer is allocated.
        val key = frame.maskKey
        if (key != null) {
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

    /**
     * Writes the frame header — control byte, [payloadLen] length field
     * (7 / 16 / 64-bit per RFC 6455 §5.2), and the 4-byte mask key when
     * [WsFrame.maskKey] is set — into [buf]. The payload follows
     * separately (inline for [payload], or as gather-written chunks for
     * [WsFrame.payloadChunks]), so [payloadLen] is passed explicitly
     * rather than read from `frame.payload`.
     */
    @Suppress("CyclomaticComplexMethod") // RFC 6455 §5.2 has 3 length classes × 2 mask states.
    private fun writeHeader(frame: WsFrame, buf: IoBuf, payloadLen: Int) {
        // Byte 0: FIN (bit 7) + RSV1-3 (bits 6-4) + opcode (bits 3-0).
        var byte0 = frame.opcode.code and OPCODE_MASK
        if (frame.fin) byte0 = byte0 or BIT_FIN
        if (frame.rsv1) byte0 = byte0 or BIT_RSV1
        if (frame.rsv2) byte0 = byte0 or BIT_RSV2
        if (frame.rsv3) byte0 = byte0 or BIT_RSV3
        buf.writeByte(byte0.toByte())

        // Byte 1: MASK (bit 7) + payload-len-7 (bits 6-0).
        val maskBit = if (frame.maskKey != null) BIT_MASK else 0
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

        // Mask key (server frames are unmasked, so this is skipped).
        val key = frame.maskKey
        if (key != null) {
            buf.writeByte((key ushr SHIFT_24).toByte())
            buf.writeByte(((key ushr SHIFT_16) and BYTE_MASK).toByte())
            buf.writeByte(((key ushr SHIFT_8) and BYTE_MASK).toByte())
            buf.writeByte((key and BYTE_MASK).toByte())
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
