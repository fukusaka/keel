package io.github.fukusaka.keel.tls

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.pipeline.ChannelDuplexHandler
import io.github.fukusaka.keel.pipeline.ChannelHandlerContext

/**
 * Pipeline handler that applies TLS record protection.
 *
 * Sits between HEAD (transport I/O) and application handlers in the pipeline:
 * ```
 * HEAD ↔ TlsHandler ↔ HttpDecoder ↔ HttpEncoder ↔ Router ↔ TAIL
 * ```
 *
 * **Inbound** ([onRead]): receives ciphertext from the transport, calls
 * [TlsCodec.unprotect] to decrypt, and propagates plaintext downstream.
 *
 * **Outbound** ([onWrite]): receives plaintext from application handlers,
 * calls [TlsCodec.protect] to encrypt, and propagates ciphertext to HEAD.
 *
 * **Handshake**: driven automatically. When [unprotect] returns [TlsResult.NEED_WRAP],
 * the handler calls [protect] to produce the handshake response and flushes it.
 * On handshake completion, fires [TlsHandshakeComplete] via [propagateUserEvent].
 *
 * **Accumulate buffer**: for efficiency, incoming ciphertext is passed directly
 * to [unprotect] when no partial TLS record is buffered (zero-copy fast path).
 * Only when [unprotect] returns [TlsResult.NEED_MORE_INPUT] are unconsumed
 * bytes copied into an accumulate buffer for the next [onRead].
 *
 * @param codec The TLS codec for this connection. Ownership transferred to handler;
 *              [TlsCodec.close] is called in [handlerRemoved].
 */
class TlsHandler(
    private val codec: TlsCodec,
) : ChannelDuplexHandler {

    private var ctx: ChannelHandlerContext? = null
    private var accumulate: IoBuf? = null
    private var handshakeNotified = false

    override fun handlerAdded(ctx: ChannelHandlerContext) {
        this.ctx = ctx
    }

    override fun handlerRemoved(ctx: ChannelHandlerContext) {
        accumulate?.release()
        accumulate = null
        codec.close()
        this.ctx = null
    }

    // --- Inbound: ciphertext → plaintext ---

    override fun onRead(ctx: ChannelHandlerContext, msg: Any) {
        if (msg !is IoBuf) {
            ctx.propagateRead(msg)
            return
        }
        try {
            processInbound(ctx, msg)
        } finally {
            msg.release()
        }
    }

    private fun processInbound(ctx: ChannelHandlerContext, cipherBuf: IoBuf) {
        // Fast path: no accumulated partial record — use cipherBuf directly.
        // Slow path: append to accumulate buffer, then unprotect from accumulate.
        val input = mergeWithAccumulate(ctx, cipherBuf)

        while (input.readableBytes > 0) {
            val plainBuf = ctx.allocator.allocate(OUTPUT_BUF_SIZE)
            val result = codec.unprotect(input, plainBuf)
            input.readerIndex += result.bytesConsumed

            when (result.status) {
                TlsResult.OK -> {
                    checkHandshakeComplete(ctx)
                    if (plainBuf.readableBytes > 0) {
                        ctx.propagateRead(plainBuf)
                    } else {
                        plainBuf.release()
                    }
                    // Continue loop — there may be more TLS records in input.
                }
                TlsResult.NEED_MORE_INPUT -> {
                    plainBuf.release()
                    saveAccumulate(ctx, input)
                    return
                }
                TlsResult.NEED_WRAP -> {
                    plainBuf.release()
                    flushHandshakeResponse(ctx)
                    // After sending handshake response, retry unprotect.
                }
                TlsResult.BUFFER_OVERFLOW -> {
                    plainBuf.release()
                    ctx.propagateError(
                        TlsException(
                            "Output buffer overflow during unprotect",
                            TlsErrorCategory.BUFFER_ERROR,
                        )
                    )
                    return
                }
                TlsResult.CLOSED -> {
                    plainBuf.release()
                    ctx.propagateInactive()
                    return
                }
            }
        }

        // All input consumed — release accumulate if we were using it.
        releaseAccumulate()
    }

    /**
     * Merges incoming cipherBuf with any accumulated partial record.
     *
     * Fast path (no accumulate): returns cipherBuf directly — no copy.
     * Slow path: appends cipherBuf into accumulate, returns accumulate.
     */
    private fun mergeWithAccumulate(ctx: ChannelHandlerContext, cipherBuf: IoBuf): IoBuf {
        val acc = accumulate ?: return cipherBuf
        // Append new data to existing accumulate buffer.
        // copyTo advances both source.readerIndex and dest.writerIndex.
        if (acc.writableBytes < cipherBuf.readableBytes) {
            // Grow: allocate a new buffer large enough for both.
            val newSize = acc.readableBytes + cipherBuf.readableBytes
            val newBuf = ctx.allocator.allocate(newSize)
            acc.copyTo(newBuf, acc.readableBytes)
            acc.release()
            cipherBuf.copyTo(newBuf, cipherBuf.readableBytes)
            accumulate = newBuf
            return newBuf
        }
        cipherBuf.copyTo(acc, cipherBuf.readableBytes)
        return acc
    }

    /**
     * Saves unconsumed input bytes to accumulate buffer for the next onRead.
     *
     * If [input] is the original cipherBuf (fast path), copies remaining bytes
     * into a new accumulate buffer. If [input] is already the accumulate buffer,
     * compacts it in place.
     */
    private fun saveAccumulate(ctx: ChannelHandlerContext, input: IoBuf) {
        val remaining = input.readableBytes
        if (remaining == 0) {
            releaseAccumulate()
            return
        }
        if (input === accumulate) {
            // Already in accumulate — compact in place.
            input.compact()
        } else {
            // Fast path → slow path transition: copy remaining bytes.
            // copyTo advances both input.readerIndex and acc.writerIndex.
            val acc = ctx.allocator.allocate(remaining)
            input.copyTo(acc, remaining)
            accumulate = acc
        }
    }

    private fun releaseAccumulate() {
        accumulate?.release()
        accumulate = null
    }

    // --- Outbound: plaintext → ciphertext ---

    override fun onWrite(ctx: ChannelHandlerContext, msg: Any) {
        if (msg !is IoBuf) {
            ctx.propagateWrite(msg)
            return
        }
        try {
            processOutbound(ctx, msg)
        } finally {
            msg.release()
        }
    }

    private fun processOutbound(ctx: ChannelHandlerContext, plainBuf: IoBuf) {
        while (plainBuf.readableBytes > 0) {
            val cipherBuf = ctx.allocator.allocate(OUTPUT_BUF_SIZE)
            val result = codec.protect(plainBuf, cipherBuf)
            plainBuf.readerIndex += result.bytesConsumed

            if (cipherBuf.readableBytes > 0) {
                ctx.propagateWrite(cipherBuf)
            } else {
                cipherBuf.release()
            }

            if (result.status != TlsResult.OK) break
        }
        checkHandshakeComplete(ctx)
    }

    override fun onFlush(ctx: ChannelHandlerContext) {
        ctx.propagateFlush()
    }

    override fun onClose(ctx: ChannelHandlerContext) {
        // Send close_notify via protect.
        codec.close()
        ctx.propagateClose()
    }

    // --- Handshake support ---

    /**
     * Flushes pending handshake response by calling [TlsCodec.protect] with
     * empty plaintext until the codec has no more data to send.
     *
     * Handles errors from protect (TlsException, BUFFER_OVERFLOW, CLOSED)
     * by propagating to the pipeline, ensuring the connection is cleaned up
     * on handshake failures such as certificate rejection or protocol errors.
     */
    private fun flushHandshakeResponse(ctx: ChannelHandlerContext) {
        val emptyBuf = ctx.allocator.allocate(0)
        try {
            var iterations = 0
            while (true) {
                val cipherBuf = ctx.allocator.allocate(OUTPUT_BUF_SIZE)
                val result = try {
                    codec.protect(emptyBuf, cipherBuf)
                } catch (e: TlsException) {
                    // Handshake failure (certificate rejected, protocol error, etc.).
                    // The codec may have written a fatal alert to cipherBuf — flush it
                    // before propagating the error so the peer receives the alert.
                    if (cipherBuf.readableBytes > 0) {
                        ctx.propagateWrite(cipherBuf)
                        ctx.propagateFlush()
                    } else {
                        cipherBuf.release()
                    }
                    ctx.propagateError(e)
                    return
                }
                if (cipherBuf.readableBytes > 0) {
                    ctx.propagateWrite(cipherBuf)
                    ctx.propagateFlush()
                } else {
                    cipherBuf.release()
                }
                when (result.status) {
                    TlsResult.OK, TlsResult.NEED_MORE_INPUT -> break
                    TlsResult.NEED_WRAP -> {
                        if (result.bytesProduced == 0) {
                            ctx.propagateError(TlsException(
                                "Handshake flush stalled: NEED_WRAP with 0 bytes produced",
                                TlsErrorCategory.PROTOCOL_ERROR,
                            ))
                            return
                        }
                        if (++iterations >= MAX_FLUSH_ITERATIONS) {
                            ctx.propagateError(TlsException(
                                "Handshake flush exceeded $MAX_FLUSH_ITERATIONS iterations",
                                TlsErrorCategory.PROTOCOL_ERROR,
                            ))
                            return
                        }
                    }
                    TlsResult.BUFFER_OVERFLOW -> {
                        ctx.propagateError(TlsException(
                            "Output buffer overflow during handshake flush",
                            TlsErrorCategory.BUFFER_ERROR,
                        ))
                        return
                    }
                    TlsResult.CLOSED -> {
                        ctx.propagateInactive()
                        return
                    }
                }
            }
        } finally {
            emptyBuf.release()
        }
        checkHandshakeComplete(ctx)
    }

    private fun checkHandshakeComplete(ctx: ChannelHandlerContext) {
        if (!handshakeNotified && codec.isHandshakeComplete) {
            handshakeNotified = true
            ctx.propagateUserEvent(
                TlsHandshakeComplete(
                    negotiatedProtocol = codec.negotiatedProtocol,
                    cipherSuite = null,
                )
            )
        }
    }

    companion object {
        // TLS record max: 16384 payload + 5 header + 256 expansion (CBC) = ~16645.
        // 17408 = 17 * 1024, comfortably above TLS record max.
        private const val OUTPUT_BUF_SIZE = 17 * 1024

        // Defense-in-depth: bounds total flushHandshakeResponse iterations.
        // A TLS 1.2 flight is typically 2-5 KB; 64 × 17 KB = 1 MB far exceeds
        // any realistic handshake. Complements the bytesProduced == 0 stall check.
        private const val MAX_FLUSH_ITERATIONS = 64
    }
}
