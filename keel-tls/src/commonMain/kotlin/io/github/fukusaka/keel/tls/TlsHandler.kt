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
            val plainBuf = ctx.allocator.allocate(TLS_RECORD_BUF_SIZE)
            val result = try {
                codec.unprotect(input, plainBuf)
            } catch (e: TlsException) {
                plainBuf.release()
                ctx.propagateError(e)
                return
            }
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
                    if (!flushHandshakeResponse(ctx)) return
                    // After sending handshake response, retry unprotect.
                }
                TlsResult.BUFFER_OVERFLOW -> {
                    plainBuf.release()
                    ctx.propagateError(
                        TlsException(
                            "Output buffer overflow during unprotect",
                            TlsErrorCategory.BUFFER_ERROR,
                        ),
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
        processOutbound(ctx, msg)
        // Do not release msg here. Outbound buffers follow the "caller manages
        // lifecycle" model: the original owner (e.g. BufferedSuspendSink) releases
        // the buffer after sink.write() returns. Releasing here would cause
        // double-release when the caller also releases, corrupting pooled buffer
        // state (ByteBuffer.limit not restored → IndexOutOfBoundsException).
    }

    private fun processOutbound(ctx: ChannelHandlerContext, plainBuf: IoBuf) {
        while (plainBuf.readableBytes > 0) {
            val cipherBuf = ctx.allocator.allocate(TLS_RECORD_BUF_SIZE)
            val result = try {
                codec.protect(plainBuf, cipherBuf)
            } catch (e: TlsException) {
                cipherBuf.release()
                ctx.propagateError(e)
                return
            }
            plainBuf.readerIndex += result.bytesConsumed

            if (cipherBuf.readableBytes > 0) {
                ctx.propagateWrite(cipherBuf)
            } else {
                cipherBuf.release()
            }

            when (result.status) {
                TlsResult.OK -> {
                    // Continue encoding remaining plaintext.
                }
                TlsResult.BUFFER_OVERFLOW -> {
                    // RFC 5246 §6.2.3 / RFC 8446 §5.2: a TLSCiphertext record
                    // the codec cannot fit into the buffer exceeds the
                    // protocol-mandated ceiling and must tear down the
                    // connection. Propagate a structured error so the
                    // downstream pipeline can close.
                    ctx.propagateError(
                        TlsException(
                            "Output buffer overflow during protect",
                            TlsErrorCategory.BUFFER_ERROR,
                        ),
                    )
                    return
                }
                TlsResult.CLOSED -> {
                    ctx.propagateInactive()
                    return
                }
                TlsResult.NEED_WRAP, TlsResult.NEED_MORE_INPUT -> {
                    // Codec is asking for a handshake flight or more input
                    // before it can continue encoding. Stop the encode loop
                    // for this call; the next handshake flush or inbound
                    // read will unblock it.
                    break
                }
            }
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
     *
     * @return true if handshake flush succeeded, false if an error was
     *         propagated (caller must stop processing).
     */
    private fun flushHandshakeResponse(ctx: ChannelHandlerContext): Boolean {
        val emptyBuf = ctx.allocator.allocate(0)
        try {
            var iterations = 0
            while (true) {
                val cipherBuf = ctx.allocator.allocate(TLS_RECORD_BUF_SIZE)
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
                    return false
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
                            ctx.propagateError(
                                TlsException(
                                    "Handshake flush stalled: NEED_WRAP with 0 bytes produced",
                                    TlsErrorCategory.PROTOCOL_ERROR,
                                ),
                            )
                            return false
                        }
                        if (++iterations >= MAX_FLUSH_ITERATIONS) {
                            ctx.propagateError(
                                TlsException(
                                    "Handshake flush exceeded $MAX_FLUSH_ITERATIONS iterations",
                                    TlsErrorCategory.PROTOCOL_ERROR,
                                ),
                            )
                            return false
                        }
                    }
                    TlsResult.BUFFER_OVERFLOW -> {
                        ctx.propagateError(
                            TlsException(
                                "Output buffer overflow during handshake flush",
                                TlsErrorCategory.BUFFER_ERROR,
                            ),
                        )
                        return false
                    }
                    TlsResult.CLOSED -> {
                        ctx.propagateInactive()
                        return false
                    }
                }
            }
        } finally {
            emptyBuf.release()
        }
        checkHandshakeComplete(ctx)
        return true
    }

    private fun checkHandshakeComplete(ctx: ChannelHandlerContext) {
        if (!handshakeNotified && codec.isHandshakeComplete) {
            handshakeNotified = true
            ctx.propagateUserEvent(
                TlsHandshakeComplete(
                    negotiatedProtocol = codec.negotiatedProtocol,
                    cipherSuite = null,
                ),
            )
        }
    }

    companion object {
        /**
         * Buffer capacity for a single TLS record, sized to cover every
         * reachable TLS 1.2 / TLS 1.3 record produced by a compliant peer
         * using an IANA-registered cipher suite that keel's TLS backends
         * (JSSE, OpenSSL, MbedTLS, AWS-LC) can negotiate.
         *
         * ### Per-variant wire record maxima
         *
         * | Variant                           |  Wire bytes | Source                          |
         * |-----------------------------------|------------:|---------------------------------|
         * | TLS 1.3 AEAD (any cipher suite)   |       16645 | [RFC 8446 §5.2][2] protocol cap |
         * | TLS 1.2 AEAD (AES-GCM/ChaCha20)   |      ~16413 | 16-byte tag + 5-byte header     |
         * | TLS 1.2 CBC + HMAC-SHA384         |       16709 | IV + MAC + max padding          |
         *
         * TLS 1.3 mandates AEAD and removes record-layer compression, so
         * its protocol ceiling (`TLSCiphertext.length <= 2^14 + 256`) is
         * exactly the reachable maximum — a sender may pad up to this
         * limit for length hiding per [RFC 8446 §5.4][4].
         *
         * TLS 1.2 has a looser protocol ceiling in [RFC 5246 §6.2.3][1]
         * (`TLSCiphertext.length <= 2^14 + 2048`), but the 2048-byte
         * expansion budget is unreachable in practice. [RFC 8449 §1][3]
         * itself notes that the expansion "is typically only 16 octets",
         * and the binding constraints are the per-cipher-suite maxima
         * above. The unused budget was reserved for future cipher suites
         * and optional TLS 1.2 compression (CRIME-deprecated in 2012, not
         * enabled by any keel backend); neither has materialised.
         *
         * `17 * 1024 = 17408` is the smallest 1 KiB-aligned value that
         * covers the largest reachable variant (TLS 1.2 CBC + SHA384 at
         * 16709 wire bytes) with ~700 bytes of margin, and also covers
         * the TLS 1.3 protocol ceiling (16645) with ~760 bytes of margin.
         *
         * ### Overflow behaviour
         *
         * If a non-compliant peer sends a record larger than any
         * negotiable cipher suite allows, or a legitimate peer uses
         * TLS 1.2 compression (neither keel nor any mainstream TLS
         * stack currently enables it), the codec returns
         * [TlsResult.BUFFER_OVERFLOW]. Every call site in this handler
         * ([processInbound], [processOutbound], [flushHandshakeResponse])
         * maps that status to [TlsException] with
         * [TlsErrorCategory.BUFFER_ERROR] and stops processing, which
         * matches the RFC 5246 §6.2.3 / RFC 8446 §5.2 mandate that a
         * receiver terminates the connection on `record_overflow`. The
         * downstream pipeline observes the error and tears the channel
         * down; the codec is responsible for emitting the on-wire
         * `record_overflow` alert via its own handshake / shutdown
         * state machine.
         *
         * ### Pool-miss note
         *
         * On JVM, 17408 exceeds the default 8 KiB pool slot of
         * [io.github.fukusaka.keel.buf.PooledDirectAllocator], so every
         * [TlsHandler] allocation on inbound, outbound, and handshake
         * paths falls back to a fresh `allocateDirect` + `Cleaner`.
         * Profiling on a JSSE-backed HTTPS workload showed this accounts
         * for roughly 1 % of total allocation samples — small enough
         * that the dominant contributors (JSSE crypto `byte[]` and
         * application-layer routing) swamp any improvement a pool hit
         * would provide. A dedicated size class matching this constant
         * and a reusable per-connection scratch buffer were both
         * considered and deferred: the expected gain is low single-digit
         * percent while the implementation would either redesign the
         * allocator or break the per-call buffer lifecycle contract.
         *
         * [1]: https://www.rfc-editor.org/rfc/rfc5246#section-6.2.3
         * [2]: https://www.rfc-editor.org/rfc/rfc8446#section-5.2
         * [3]: https://www.rfc-editor.org/rfc/rfc8449#section-1
         * [4]: https://www.rfc-editor.org/rfc/rfc8446#section-5.4
         */
        private const val TLS_RECORD_BUF_SIZE = 17 * 1024

        // Defense-in-depth: bounds total flushHandshakeResponse iterations.
        // A TLS 1.2 flight is typically 2-5 KB; 64 × 17 KB = 1 MB far exceeds
        // any realistic handshake. Complements the bytesProduced == 0 stall check.
        private const val MAX_FLUSH_ITERATIONS = 64
    }
}
