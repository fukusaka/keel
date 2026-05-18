package io.github.fukusaka.keel.codec.websocket

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import io.github.fukusaka.keel.pipeline.TypedInboundHandler
import kotlinx.io.Buffer

/**
 * Pipeline handler that decodes incoming [IoBuf] bytes into [WsFrame]
 * messages (RFC 6455 §5.2).
 *
 * Sits on the inbound side of the pipeline after the WebSocket handshake
 * has switched the connection from HTTP/1.1 to WS framing. Each [IoBuf]
 * delivered via [TypedInboundHandler.onReadTyped] is appended to an
 * internal kotlinx-io [Buffer], then complete frames are extracted one by
 * one via [tryParseFrame] and propagated to the next inbound handler as
 * [WsFrame] events. Partial frames (when a TCP segment splits in the
 * middle of a frame) remain in the buffer and continue parsing on the
 * next inbound chunk.
 *
 * **Frame size validation**: [maxFramePayloadSize] (default 16 MiB)
 * caps the per-frame payload length. A peer sending a longer length
 * field triggers [WsCodecException] before the payload bytes are read,
 * which prevents a malicious or buggy peer from forcing the server to
 * buffer arbitrary memory. Tune up for applications that legitimately
 * need very large frames; pair with a future `WsFrameAggregator` for
 * fragmented-message limits.
 *
 * **Masking validation**: server-mode (`requireClientMasking = true`,
 * the default) rejects any unmasked client→server frame per RFC 6455
 * §5.1. Client-mode (`requireClientMasking = false`) accepts both,
 * useful when this same decoder is reused on the client side reading
 * server frames (which MUST NOT be masked — the validation tightens
 * naturally because the parser already unmasks based on the frame's
 * own mask bit).
 *
 * The handler is stateful (the kotlinx-io buffer accumulates between
 * onRead callbacks) and thus must run on a single thread — typically
 * the EventLoop of the underlying transport. It is intended as a
 * per-connection handler installed alongside [WsFrameEncoder] via
 * `PipelinedChannel.addWsServerCodec()`.
 *
 * @property maxFramePayloadSize maximum per-frame payload size in bytes
 *   (default 16 MiB). A frame whose advertised length exceeds this is
 *   rejected with [WsCodecException] before any payload is read.
 * @property requireClientMasking when true (default), unmasked inbound
 *   frames trigger [WsCodecException]. RFC 6455 §5.1 mandates client
 *   frames be masked; servers must close the connection on violation.
 * @property allowRsv1 when true, accept frames with RSV1=1 — the
 *   `permessage-deflate` compressed-message marker (RFC 7692 §7.2).
 *   Set true only when the handshake negotiated `permessage-deflate`;
 *   defaults to false so a server with no extension stays strict.
 */
public class WsFrameDecoder(
    private val maxFramePayloadSize: Long = DEFAULT_MAX_FRAME_PAYLOAD_SIZE,
    private val requireClientMasking: Boolean = true,
    private val allowRsv1: Boolean = false,
) : TypedInboundHandler<IoBuf>(IoBuf::class, autoRelease = true) {

    /**
     * Accumulates inbound bytes across [IoBuf] boundaries. Each call to
     * [onReadTyped] copies the chunk's readable bytes here, then the
     * decoder repeatedly extracts complete frames until the buffer
     * contains less than one full frame.
     */
    private val buffer: Buffer = Buffer()

    override fun onReadTyped(ctx: PipelineHandlerContext, msg: IoBuf) {
        val n = msg.readableBytes
        if (n > 0) {
            // Copy IoBuf bytes into the internal Buffer. We can't share
            // memory with IoBuf because the buffer must outlive a single
            // chunk (a frame straddling chunk boundaries needs both
            // copies present until parsing completes), and IoBuf's
            // refcount semantics don't extend to kotlinx-io Buffer
            // segments.
            val bytes = ByteArray(n)
            msg.readByteArray(bytes, 0, n)
            buffer.write(bytes)
        }
        drainCompleteFrames(ctx)
    }

    private fun drainCompleteFrames(ctx: PipelineHandlerContext) {
        while (true) {
            val frame = tryParseFrame() ?: return
            if (requireClientMasking && frame.maskKey == null && !frame.opcode.isControl) {
                // Allow control frames either way (parser is permissive),
                // but require a mask on the data frames a server expects
                // from a client.
                throw WsCodecException("Unmasked client frame received (RFC 6455 §5.1)")
            }
            ctx.propagateRead(frame)
        }
    }

    /**
     * Attempts to extract one complete frame from [buffer] without
     * consuming bytes that belong to a not-yet-complete frame.
     *
     * The flow is "peek → measure → if-ready commit": [Buffer.peek]
     * produces a transient view that advances independently of the
     * underlying buffer, so we can read header bytes to compute the
     * total frame size without losing them on the partial-frame branch.
     * When the underlying buffer holds at least the computed total, we
     * call [parseFrame] on the buffer itself, which consumes exactly
     * those bytes.
     *
     * @return the decoded [WsFrame] if a complete frame is available,
     *   or `null` if more bytes are needed.
     */
    @Suppress("ReturnCount")
    private fun tryParseFrame(): WsFrame? {
        if (buffer.size < 2) return null
        val peek = buffer.peek()
        val byte0 = peek.readByte().toInt() and 0xFF
        val byte1 = peek.readByte().toInt() and 0xFF
        val masked = (byte1 and 0x80) != 0
        val payloadLen7 = byte1 and 0x7F

        var headerSize = 2L
        val payloadLength: Long = when (payloadLen7) {
            EXTENDED_LENGTH_16 -> {
                if (buffer.size < headerSize + 2) return null
                headerSize += 2
                val hi = peek.readByte().toInt() and 0xFF
                val lo = peek.readByte().toInt() and 0xFF
                ((hi shl 8) or lo).toLong()
            }
            EXTENDED_LENGTH_64 -> {
                if (buffer.size < headerSize + 8) return null
                headerSize += 8
                var len = 0L
                repeat(8) { len = (len shl 8) or (peek.readByte().toInt() and 0xFF).toLong() }
                len
            }
            else -> payloadLen7.toLong()
        }

        if (payloadLength < 0L || payloadLength > maxFramePayloadSize) {
            throw WsCodecException(
                "Frame payload length $payloadLength exceeds limit $maxFramePayloadSize " +
                    "(byte0=0x${byte0.toString(16)}, payloadLen7=$payloadLen7)",
            )
        }
        val maskSize = if (masked) MASK_KEY_SIZE else 0
        val totalSize = headerSize + maskSize + payloadLength
        if (buffer.size < totalSize) return null

        // Full frame available — parseFrame consumes from `buffer`,
        // advancing past this frame's bytes and leaving any subsequent
        // chunk in place for the next iteration.
        return parseFrame(buffer, allowRsv1)
    }

    public companion object {
        /**
         * 16 MiB default cap on a single frame's payload. Matches the
         * maximum a typical browser sends without server-side
         * negotiation; tune higher for media / file streaming workloads
         * where larger frames are expected.
         */
        public const val DEFAULT_MAX_FRAME_PAYLOAD_SIZE: Long = 16L * 1024 * 1024

        /** Sentinel in `payload-len-7` indicating a 16-bit extended length follows. */
        private const val EXTENDED_LENGTH_16 = 126

        /** Sentinel in `payload-len-7` indicating a 64-bit extended length follows. */
        private const val EXTENDED_LENGTH_64 = 127

        /** Mask key length in bytes when the mask bit is set (RFC 6455 §5.2). */
        private const val MASK_KEY_SIZE = 4
    }
}

/**
 * Exception type for WebSocket codec violations surfaced through the
 * pipeline. The decoder propagates this via [PipelineHandlerContext.propagateError]
 * so connection-level handlers can map it to a close frame
 * ([WsCloseCode.PROTOCOL_ERROR] / [WsCloseCode.MESSAGE_TOO_BIG]) and
 * tear down the connection.
 */
public class WsCodecException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
