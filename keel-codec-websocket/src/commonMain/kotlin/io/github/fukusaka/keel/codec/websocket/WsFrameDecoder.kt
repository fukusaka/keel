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
 * **Ending**: once the connection has ended (`onInactive`) the decoder
 * decodes nothing more. The ending can be raised from inside the decoder's
 * own downstream dispatch of a frame — a handler closing the channel on the
 * peer's CLOSE, say — with the parse loop still running; the rest of that
 * read, later reads, and reads the pipeline replays from its journal are then
 * left undecoded, no frame is emitted and no pooled payload is allocated for
 * them, and a frame left part-parsed is dropped. A frame decoded after the
 * ending would reach nobody who can act on it: the connection is over -- a
 * server's frame bridge is closed by then and releases it unread, and a bare
 * consumer would be handed a frame it can no longer answer. A peer's CLOSE precedes its
 * FIN and reads are delivered before the read side's closing is, so the
 * closing handshake is decoded before the ending, not after.
 *
 * The handler is stateful (the kotlinx-io buffer accumulates between
 * onRead callbacks) and thus must run on a single thread — typically
 * the EventLoop of the underlying transport. It is intended as a
 * per-connection handler installed alongside [WsFrameEncoder] via
 * `PipelinedChannel.addWsServerCodec()`.
 *
 * **Inbound zero-copy fast path ([poolDataPayloads])**: when enabled, the
 * common case — the internal kotlinx-io [Buffer] is empty *and* the input
 * [IoBuf] already holds one or more complete frames — is parsed straight
 * out of the input buffer. Each data frame's payload is copied (and
 * unmasked) into a fresh pooled [IoBuf] obtained from the pipeline's
 * allocator and emitted as [WsFrame.inboundPayload], skipping the
 * pooled-IoBuf→kotlinx-io-`Buffer`→`ByteArray` round-trip the slow path
 * makes. The trailing partial frame (and every subsequent chunk until it
 * completes) falls back to the [Buffer] slow path, so straddling frames and
 * correctness are unchanged. Control frames and empty data frames keep their
 * `ByteArray` [WsFrame.payload] even on the fast path (their payload is tiny
 * and the session pump reads it as bytes). When disabled (the default) every
 * frame uses the `ByteArray` slow path, so consumers that read
 * [WsFrame.payload] directly are unaffected.
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
 * @property poolDataPayloads when true, decode data-frame payloads into
 *   pooled [WsFrame.inboundPayload] buffers via the zero-copy fast path
 *   described above instead of always producing a heap [WsFrame.payload].
 *   The downstream consumer then **owns** each emitted frame's
 *   `inboundPayload` and must release it. Defaults to false; the server
 *   codec installer enables it because its consumer (`WsFrameAggregator`)
 *   knows the pooled ownership contract.
 */
public class WsFrameDecoder(
    private val maxFramePayloadSize: Long = DEFAULT_MAX_FRAME_PAYLOAD_SIZE,
    private val requireClientMasking: Boolean = true,
    private val allowRsv1: Boolean = false,
    private val poolDataPayloads: Boolean = false,
) : TypedInboundHandler<IoBuf>(IoBuf::class, autoRelease = true) {

    /**
     * Accumulates inbound bytes across [IoBuf] boundaries. Each call to
     * [onReadTyped] copies the chunk's readable bytes here, then the
     * decoder repeatedly extracts complete frames until the buffer
     * contains less than one full frame.
     */
    private val buffer: Buffer = Buffer()

    /**
     * Whether the connection has ended. The decoder's only lifecycle state:
     * set by [onInactive], never cleared, and read at the top of every read,
     * after every emitted frame -- the ending can be raised from inside that
     * frame's dispatch, and the loop must not look for the next one -- and
     * before the fast path stashes a trailing partial frame for a next chunk
     * that will not come.
     */
    private var ended: Boolean = false

    /**
     * Bytes in the slow-path accumulator: what has been copied in and not
     * yet parsed. While a read is being drained that includes whole frames
     * not yet reached; once it is drained, only a frame still incomplete --
     * or, when a [WsCodecException] aborted the drain, the whole frames
     * behind the bad one as well, which the next read parses; and from the
     * ending on, always zero -- the seam the module's tests use to pin that
     * nothing is held or copied after the connection has ended.
     */
    internal val pendingBytes: Long get() = buffer.size

    override fun onInactive(ctx: PipelineHandlerContext) {
        ended = true
        // A frame left part-parsed will never complete; the accumulator is
        // heap-owned, so dropping it is the whole of the release.
        buffer.clear()
        ctx.propagateInactive()
    }

    override fun onReadTyped(ctx: PipelineHandlerContext, msg: IoBuf) {
        // Nothing after the ending is decoded; the read is released by the
        // base handler as always.
        if (ended) return
        if (poolDataPayloads && buffer.size == 0L) {
            // Fast path: parse complete frames straight out of the input
            // IoBuf while the slow-path accumulator is empty. Each call to
            // [tryFastEmit] consumes one complete frame from `msg` and emits
            // it; it returns false once the remaining bytes are less than a
            // full frame -- or the loop stops first because that frame's
            // dispatch ended the connection.
            while (!ended && msg.readableBytes > 0 && tryFastEmit(ctx, msg)) {
                // loop until a partial frame remains
            }
            // Stash the trailing partial frame in the slow-path Buffer so the
            // next chunk continues parsing it. The IoBuf is auto-released
            // after this method returns; the pooled payloads emitted above
            // are independent copies that outlive it. Nothing is stashed
            // after the ending: there is no next chunk to continue with.
            val remaining = msg.readableBytes
            if (!ended && remaining > 0) {
                val bytes = ByteArray(remaining)
                msg.readByteArray(bytes, 0, remaining)
                buffer.write(bytes)
            }
            return
        }
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
        // Re-checked after every emit: a frame's dispatch can end the
        // connection, and the frames behind it are then not decoded. The
        // ending also empties the accumulator, which stops this loop on its
        // own; the check states the contract where it applies, and holds if
        // the accumulator is ever left standing.
        while (!ended) {
            val frame = tryParseFrame() ?: return
            emit(ctx, frame)
        }
    }

    /**
     * Validates client masking (server mode) and propagates [frame] to the
     * next inbound handler. Shared by the slow path and the fast path so the
     * RFC 6455 §5.1 masking check is applied identically; the fast path runs
     * this check before allocating a pooled payload (see [tryFastEmit]), so
     * here it only re-guards the slow-path frames.
     */
    private fun emit(ctx: PipelineHandlerContext, frame: WsFrame) {
        if (requireClientMasking && frame.maskKey == null && !frame.opcode.isControl) {
            // Allow control frames either way (parser is permissive),
            // but require a mask on the data frames a server expects
            // from a client.
            throw WsCodecException("Unmasked client frame received (RFC 6455 §5.1)")
        }
        ctx.propagateRead(frame)
    }

    /**
     * Attempts to consume one complete frame from [msg] (the pooled input
     * buffer) and emit it. Returns true when a frame was consumed and
     * propagated, false when the remaining bytes do not yet form a complete
     * frame (the caller then stashes them in the slow-path [Buffer]).
     *
     * Header measurement mirrors [tryParseFrame]; [decodeFastFrame] then
     * validates and builds the frame.
     */
    @Suppress("ReturnCount")
    private fun tryFastEmit(ctx: PipelineHandlerContext, msg: IoBuf): Boolean {
        val available = msg.readableBytes
        if (available < 2) return false
        val base = msg.readerIndex
        val byte0 = msg.getByte(base).toInt() and 0xFF
        val byte1 = msg.getByte(base + 1).toInt() and 0xFF
        val masked = (byte1 and 0x80) != 0
        val payloadLen7 = byte1 and 0x7F

        var headerSize = 2
        val payloadLength: Long = when (payloadLen7) {
            EXTENDED_LENGTH_16 -> {
                if (available < headerSize + 2) return false
                headerSize = 4
                val hi = msg.getByte(base + 2).toInt() and 0xFF
                val lo = msg.getByte(base + 3).toInt() and 0xFF
                ((hi shl 8) or lo).toLong()
            }
            EXTENDED_LENGTH_64 -> {
                if (available < headerSize + 8) return false
                headerSize = 10
                var len = 0L
                repeat(8) { len = (len shl 8) or (msg.getByte(base + 2 + it).toInt() and 0xFF).toLong() }
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
        if (available < headerSize.toLong() + maskSize + payloadLength) return false

        ctx.propagateRead(decodeFastFrame(ctx, msg, base, headerSize, payloadLength.toInt()))
        return true
    }

    /**
     * Decodes the validated, fully-available frame at [base] in [msg] (header
     * length already measured as [headerSize], payload length as [len]).
     * Mirrors [parseFrame]: derives `fin` / RSV / opcode / mask bit from the
     * two header bytes, enforces the RSV and control-frame rules and (server
     * mode) client masking, then builds the frame — non-empty data into a
     * fresh pooled [WsFrame.inboundPayload] via [copyToPooled], control /
     * empty payloads into a heap `ByteArray`.
     */
    private fun decodeFastFrame(
        ctx: PipelineHandlerContext,
        msg: IoBuf,
        base: Int,
        headerSize: Int,
        len: Int,
    ): WsFrame {
        val byte0 = msg.getByte(base).toInt() and 0xFF
        val masked = (msg.getByte(base + 1).toInt() and 0x80) != 0
        val fin = (byte0 and 0x80) != 0
        val rsv1 = (byte0 and 0x40) != 0
        val rsv2 = (byte0 and 0x20) != 0
        val rsv3 = (byte0 and 0x10) != 0
        require((allowRsv1 || !rsv1) && !rsv2 && !rsv3) {
            "Reserved bits invalid (rsv1=$rsv1, rsv2=$rsv2, rsv3=$rsv3): " +
                "RSV2/RSV3 must be 0; RSV1 is permitted only when permessage-deflate is negotiated"
        }
        val opcode = WsOpcode.fromCode(byte0 and 0x0F)
        if (opcode.isControl) {
            require(fin) { "Control frames must not be fragmented (fin must be true)" }
            require(len <= CONTROL_FRAME_MAX_PAYLOAD_SIZE) {
                "Control frame payload must not exceed $CONTROL_FRAME_MAX_PAYLOAD_SIZE bytes, got $len"
            }
        }

        // Commit: advance past the header, read the mask key, then the payload.
        msg.readerIndex = base + headerSize
        val maskKey: Int? = if (masked) {
            var key = 0
            repeat(MASK_KEY_SIZE) { key = (key shl 8) or (msg.readByte().toInt() and 0xFF) }
            key
        } else {
            null
        }
        // Validate masking before allocating the payload so an unmasked client
        // data frame fails the connection without leaking a buffer.
        if (requireClientMasking && maskKey == null && !opcode.isControl) {
            throw WsCodecException("Unmasked client frame received (RFC 6455 §5.1)")
        }

        return if (!opcode.isControl && len > 0) {
            // Data frame: copy + unmask straight into a fresh pooled IoBuf so
            // the payload never round-trips through a kotlinx-io Buffer or a
            // heap ByteArray. The frame owns this buffer; the consumer releases
            // it (or transfers it to an IoBufChunks for the app).
            WsFrame(
                fin = fin,
                rsv1 = rsv1,
                rsv2 = rsv2,
                rsv3 = rsv3,
                opcode = opcode,
                maskKey = maskKey,
                inboundPayload = copyToPooled(ctx, msg, len, maskKey),
            )
        } else {
            // Control frame or empty data frame: read into a heap ByteArray
            // (same as the slow path) and unmask in place.
            val bytes = ByteArray(len)
            if (len > 0) {
                msg.readByteArray(bytes, 0, len)
                if (maskKey != null) unmask(bytes, maskKey)
            }
            WsFrame(
                fin = fin,
                rsv1 = rsv1,
                rsv2 = rsv2,
                rsv3 = rsv3,
                opcode = opcode,
                maskKey = maskKey,
                payload = bytes,
            )
        }
    }

    /**
     * Allocates a fresh pooled [IoBuf] of [len] bytes and copies + unmasks the
     * payload into it from [msg]. Releases the buffer if the copy throws, so
     * the fast path never leaks on a partial read; otherwise transfers
     * ownership of the returned buffer to the caller's frame.
     */
    private fun copyToPooled(ctx: PipelineHandlerContext, msg: IoBuf, len: Int, maskKey: Int?): IoBuf {
        val pooled = ctx.allocator.allocate(len)
        try {
            copyUnmasked(msg, pooled, len, maskKey)
        } catch (t: Throwable) {
            pooled.release()
            throw t
        }
        return pooled
    }

    /**
     * Copies [len] bytes from [src] (at its current reader index) into [dst],
     * unmasking with [maskKey] (RFC 6455 §5.3) in the same pass. A null
     * [maskKey] (client-mode unmasked frame) is a plain bulk copy. The XOR
     * key derivation matches [unmask] so the fast path and [parseFrame]
     * produce byte-identical payloads.
     */
    private fun copyUnmasked(src: IoBuf, dst: IoBuf, len: Int, maskKey: Int?) {
        if (maskKey == null) {
            src.copyTo(dst, len)
            return
        }
        val k0 = (maskKey shr 24) and 0xFF
        val k1 = (maskKey shr 16) and 0xFF
        val k2 = (maskKey shr 8) and 0xFF
        val k3 = maskKey and 0xFF
        for (i in 0 until len) {
            val k = when (i and 3) {
                0 -> k0
                1 -> k1
                2 -> k2
                else -> k3
            }
            dst.writeByte((src.readByte().toInt() xor k).toByte())
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

        /**
         * Maximum control-frame payload length (RFC 6455 §5.5): PING / PONG /
         * CLOSE payloads must fit in the 7-bit length field's "small" range.
         */
        private const val CONTROL_FRAME_MAX_PAYLOAD_SIZE = 125
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
