package io.github.fukusaka.keel.tls

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.logging.debug
import io.github.fukusaka.keel.logging.warn
import io.github.fukusaka.keel.pipeline.DuplexHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import io.github.fukusaka.keel.pipeline.TimerHandle

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
 * Takes ownership of and releases the incoming plaintext buffer once fully
 * consumed — callers (e.g. [io.github.fukusaka.keel.codec.http.HttpResponseEncoder])
 * hand it off via a fire-and-forget `propagateWrite` and do not retain or
 * release it themselves.
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
 * **One instance per connection, and not reusable.** Once this handler has
 * given its session back — the connection ending, or the handler being taken
 * out of the pipeline — it stays that way: the session is closed and cannot be
 * reopened, so a re-added instance would drop everything it is given rather
 * than fail. Build a new one, with a new codec.
 *
 * @param codec The TLS codec for this connection. Ownership transferred to handler;
 *              [TlsCodec.close] is called when this handler stops being
 *              responsible for the connection — the connection ending, or
 *              this handler being taken out of the pipeline.
 */
class TlsHandler(
    private val codec: TlsCodec,
    /**
     * Per-record plaintext buffer size for this connection (the buffer the
     * downstream codec receives as its "recv segment" on a TLS connection).
     * Defaults to [TLS_PLAINTEXT_BUF_SIZE_DEFAULT] (16 KiB, the RFC 8446
     * §5.1 maximum), which preserves the historical hardcoded value. Set
     * via [io.github.fukusaka.keel.server.TlsServerConfig.plaintextBufferSize]
     * for per-server override. Must be a power of two in
     * [[TLS_PLAINTEXT_BUF_SIZE_MIN]]..[TLS_PLAINTEXT_BUF_SIZE_MAX]; see
     * [requireValidPlaintextBufferSize].
     */
    private val plaintextBufferSize: Int = TLS_PLAINTEXT_BUF_SIZE_DEFAULT,
    /**
     * Absolute handshake time budget (ms); `0` (default) disables it. When
     * positive, an absolute deadline is armed on the first inbound TLS record
     * and disarmed when [TlsCodec.isHandshakeComplete] becomes true. If it
     * elapses, the channel is force-closed — the time-axis defence against a
     * peer that starts but never finishes the handshake. A peer that connects
     * and sends nothing is bounded by the transport idle timeout instead. Set
     * via [io.github.fukusaka.keel.tls.TlsConfig.handshakeTimeoutMillis].
     */
    private val handshakeTimeoutMillis: Long = 0,
) : DuplexHandler {

    init {
        requireValidPlaintextBufferSize(plaintextBufferSize)
        require(handshakeTimeoutMillis >= 0) {
            "handshakeTimeoutMillis ($handshakeTimeoutMillis) must be >= 0 (0 disables the deadline)"
        }
    }

    private var ctx: PipelineHandlerContext? = null
    private var accumulate: IoBuf? = null
    private var handshakeNotified = false

    // Handshake deadline: armed once on the first inbound record, cancelled on
    // completion. Touched only on the EventLoop thread (onRead /
    // checkHandshakeComplete / the timer task all run there).
    private var handshakeDeadline: TimerHandle? = null
    private var handshakeDeadlineArmed = false
    private var noTimerWarned = false

    override fun handlerAdded(ctx: PipelineHandlerContext) {
        this.ctx = ctx
        ctx.allocator.hintSizeClass(plaintextBufferSize, PLAINTEXT_HINT_COUNT)
    }

    override fun handlerRemoved(ctx: PipelineHandlerContext) {
        try {
            releaseTlsResources()
        } finally {
            this.ctx = null
        }
    }

    /**
     * Whether this handler has given back what it holds.
     *
     * Read before every use of the session, because the release and those
     * uses are not sequential. The inactivation travels inbound and this
     * handler sits near the head, so handlers *after* it are still running
     * when its own `onInactive` returns — and anything they write travels
     * back outbound through here. The server cancels its connection scope on
     * that same signal, but cancellation is cooperative: a request already
     * past its last suspension point still writes its response.
     */
    private var released = false

    /** Whether the first outbound message dropped after the release has been named. */
    private var droppedWriteReported = false

    /**
     * Gives back everything this handler holds outside the managed heap.
     *
     * Called from each of the ways this handler stops being responsible for a
     * connection — the connection ending, and the handler being taken out of
     * the pipeline for a protocol switch — because either can be the last one
     * to happen, and on a connection whose protocol was switched both do.
     * Idempotent for that reason.
     *
     * The session is the costly one. On the native backends it is an `SSL*`,
     * its `BIO`, and a manually allocated context, none of which the garbage
     * collector will ever come back for, and this handler is the only caller
     * of [TlsCodec.close] in the library's own sources (the backend tests call
     * it directly on codecs they build).
     */
    private fun releaseTlsResources() {
        if (released) return
        released = true
        // Each step guarded against the ones after it, which is what makes
        // this safe -- not the order. The latch is already spent by the time
        // the first step runs, so a step that threw would take every later
        // step with it permanently, and there is no second chance at any of
        // them: the session is GC-invisible and the buffer is pooled. Listed
        // with the session first because it is the costliest to lose, but a
        // reader should not read the order as load-bearing. The first failure
        // is what the caller gets; the rest ride on it, the way the engine's
        // own staged teardown reports its stages.
        var failure: Throwable? = null
        try {
            codec.close()
        } catch (sessionFailure: Throwable) {
            failure = sessionFailure
        }
        try {
            handshakeDeadline?.cancel()
        } catch (deadlineFailure: Throwable) {
            failure = failure?.also { it.addSuppressed(deadlineFailure) } ?: deadlineFailure
        }
        handshakeDeadline = null
        // Taken out of the field before the release, so a throw cannot leave
        // the handler holding a buffer it has already handed back.
        val held = accumulate
        accumulate = null
        try {
            held?.release()
        } catch (bufferFailure: Throwable) {
            failure = failure?.also { it.addSuppressed(bufferFailure) } ?: bufferFailure
        }
        failure?.let { throw it }
    }

    /**
     * Gives the session back when the connection ends.
     *
     * The signal that arrives on the endings that matter: a peer's FIN, a
     * failed connection, an idle reclamation. Before this, the only route to
     * [TlsCodec.close] was the handler being removed from the pipeline, which
     * only a protocol switch does — so an ordinary TLS connection ended with
     * its session still allocated.
     *
     * **A connection closed locally still reaches none of this.** `close()`
     * goes to the transport without passing the handlers at all, so that
     * ending releases nothing here; it is a separate change.
     */
    override fun onInactive(ctx: PipelineHandlerContext) = reportConnectionEnded(ctx)

    /**
     * Releases, then tells the handlers below the connection has ended.
     *
     * Both directions of that news come through here. One arrives from the
     * head as [onInactive]; the other this handler reports itself, when the
     * codec answers [TlsResult.CLOSED] because the peer sent a close_notify.
     * The second kind does **not** come back round to [onInactive] — an
     * inbound event propagated from a handler travels away from it — so a
     * release hung only on [onInactive] would miss every ending the codec is
     * the one to notice. A peer is allowed to close_notify without a TCP FIN,
     * and on that ending nothing else would have released the session.
     *
     * In a `finally` because a release that throws must not stop the news:
     * the invoker would turn the throw into an error event, and the suspend
     * bridge does not listen for those, so its queued buffers would stay held
     * and a parked reader would never be woken.
     */
    private fun reportConnectionEnded(ctx: PipelineHandlerContext) {
        try {
            releaseTlsResources()
        } finally {
            ctx.propagateInactive()
        }
    }

    // --- Inbound: ciphertext → plaintext ---

    override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
        if (msg !is IoBuf) {
            ctx.propagateRead(msg)
            return
        }
        // Arm the absolute handshake deadline on the first inbound record — the
        // point a handshake demonstrably begins. A peer that connects but never
        // sends a byte is bounded by the transport idle timeout instead; this
        // deadline bounds a handshake that has started but does not complete.
        // The deadline is absolute (armed once), so trickled records cannot
        // refresh it the way they refresh an inactivity timer.
        armHandshakeDeadlineIfNeeded(ctx)
        try {
            processInbound(ctx, msg)
        } finally {
            msg.release()
        }
    }

    /**
     * Arms the absolute handshake deadline once. Force-closes the channel on
     * elapse (the same active-reclaim policy as the idle timeout). No-op when
     * the deadline is disabled (`handshakeTimeoutMillis <= 0`), already armed,
     * or the handshake has already completed. Warns once if a positive budget
     * cannot be scheduled (the engine wires no EventLoop timer) rather than
     * silently disabling the defence.
     */
    private fun armHandshakeDeadlineIfNeeded(ctx: PipelineHandlerContext) {
        // A connection that has ended gets no new deadline: the timer would
        // outlive it and close a channel that is already gone.
        if (released) return
        if (handshakeDeadlineArmed || handshakeTimeoutMillis <= 0 || handshakeNotified) return
        handshakeDeadlineArmed = true
        val handle = ctx.channel.scheduleDeadline(handshakeTimeoutMillis) {
            handshakeDeadline = null
            ctx.channel.close()
        }
        handshakeDeadline = handle
        if (handle == null && !noTimerWarned) {
            noTimerWarned = true
            ctx.channel.logger.warn {
                "TLS handshake deadline (${handshakeTimeoutMillis}ms) is configured but not enforced: " +
                    "this channel's engine provides no EventLoop timer"
            }
        }
    }

    private fun processInbound(ctx: PipelineHandlerContext, cipherBuf: IoBuf) {
        // Fast path: no accumulated partial record — use cipherBuf directly.
        // Slow path: append to accumulate buffer, then unprotect from accumulate.
        val input = mergeWithAccumulate(ctx, cipherBuf)

        // Checked each turn, and before the length: this loop propagates a
        // decrypted record to the handlers below and they may end the
        // connection while it does, and `input` can *be* the accumulate
        // buffer, which the release hands back to the pool.
        //
        // Measured: without the flag the suite does not finish. The run that
        // hangs is the handshake case, and it hangs for the plainer reason of
        // the two — the codec keeps asking for a wrap while the flush below is
        // a no-op, so no turn consumes anything. Reading a pooled buffer's
        // length is the hazard the ordering above answers; it is not what that
        // measurement exercised.
        while (!released && input.readableBytes > 0) {
            val plainBuf = ctx.allocator.allocate(plaintextBufferSize)
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
                    reportConnectionEnded(ctx)
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
    private fun mergeWithAccumulate(ctx: PipelineHandlerContext, cipherBuf: IoBuf): IoBuf {
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
     * If [input] is already the accumulate buffer (a prior call right-sized
     * it, or [mergeWithAccumulate] just appended into its reserved headroom),
     * this is a no-op — the buffer is already exactly what the next
     * [mergeWithAccumulate] call should see, so re-allocating and re-copying
     * it here would defeat the whole point of right-sizing: for a record
     * spanning N reads, every one of those N calls would otherwise allocate
     * and copy again, undoing the "allocated once" guarantee.
     *
     * Otherwise (first short read for this record, `accumulate` was `null`),
     * relocates the remaining bytes into a fresh accumulate buffer, right-
     * sized to the full TLS record length when the 5-byte record header is
     * already present (see [recordSizeIfKnown]). Without this, a codec that
     * reports zero bytes consumed on a short record (e.g. JSSE's
     * `SSLEngine.unwrap()` on `BUFFER_UNDERFLOW`, unlike the native
     * BIO-callback codecs which always drain what they are given) causes a
     * record spanning N reads to be grown and fully re-copied by
     * [mergeWithAccumulate] on every one of those reads — right-sizing here
     * means the buffer is allocated once and each subsequent read appends
     * into already-reserved headroom instead.
     */
    private fun saveAccumulate(ctx: PipelineHandlerContext, input: IoBuf) {
        val remaining = input.readableBytes
        if (remaining == 0) {
            releaseAccumulate()
            return
        }
        if (input === accumulate) return
        // Relocate the unconsumed bytes into a fresh accumulate buffer.
        // copyTo advances both input.readerIndex and acc.writerIndex.
        val allocSize = recordSizeIfKnown(input, remaining) ?: remaining
        val acc = ctx.allocator.allocate(allocSize)
        input.copyTo(acc, remaining)
        accumulate = acc
    }

    /**
     * Parses the 5-byte TLS record header (RFC 8446 §5.1: `type`[1] +
     * `legacy_record_version`[2] + `length`[2], big-endian) at [input]'s
     * current reader position to compute the full on-wire record size
     * (header + ciphertext payload).
     *
     * Returns `null` when the header itself is not fully available yet
     * ([remaining] < 5), when the declared payload length exceeds the
     * maximum realistic ciphertext record ([TLS_CIPHERTEXT_BUF_SIZE], which
     * already covers TLS 1.3 AEAD and TLS 1.2 CBC+HMAC overhead) — treated
     * as implausible/malformed input and left for [TlsCodec.unprotect] to
     * reject during actual parsing rather than driving an allocation size
     * here, or when the computed total does not exceed what is already
     * available (right-sizing has nothing to add in that case).
     */
    private fun recordSizeIfKnown(input: IoBuf, remaining: Int): Int? {
        if (remaining < TLS_RECORD_HEADER_SIZE) return null
        val base = input.readerIndex
        val payloadLength =
            ((input.getByte(base + 3).toInt() and 0xFF) shl 8) or
                (input.getByte(base + 4).toInt() and 0xFF)
        if (payloadLength > TLS_CIPHERTEXT_BUF_SIZE - TLS_RECORD_HEADER_SIZE) return null
        val total = TLS_RECORD_HEADER_SIZE + payloadLength
        return total.takeIf { it > remaining }
    }

    private fun releaseAccumulate() {
        accumulate?.release()
        accumulate = null
    }

    // --- Outbound: plaintext → ciphertext ---

    override fun onWrite(ctx: PipelineHandlerContext, msg: Any) {
        if (msg !is IoBuf) {
            ctx.propagateWrite(msg)
            return
        }
        // msg is fully consumed by processOutbound (read into fresh ciphertext
        // buffers, never itself propagated downstream) and this handler is its
        // last consumer, so it must be released here regardless of which
        // internal exit path processOutbound took (full drain, BUFFER_OVERFLOW,
        // CLOSED, or an unexpected-status error). No current caller (e.g.
        // HttpResponseEncoder's `ctx.propagateWrite(content.content)`) retains
        // or releases msg after handing it off — assuming otherwise silently
        // leaked the plaintext buffer on every TLS write (confirmed via
        // ResourceLeakDetector on the Netty backend, ~100 KB/response with
        // pooled ByteBuf allocators).
        try {
            processOutbound(ctx, msg)
        } finally {
            msg.release()
        }
    }

    /**
     * Names an outbound message this handler could not send.
     *
     * Once at warn and the rest at debug: a connection that ends with work in
     * flight drops a burst, and a line per message would bury the first one.
     */
    private fun reportDroppedWrite(ctx: PipelineHandlerContext) {
        if (droppedWriteReported) {
            ctx.channel.logger.debug { "another outbound message dropped: the TLS session is gone" }
            return
        }
        droppedWriteReported = true
        ctx.channel.logger.warn {
            "outbound message dropped: the TLS session was released when the connection ended, " +
                "so there is nothing to encrypt it with"
        }
    }

    private fun processOutbound(ctx: PipelineHandlerContext, plainBuf: IoBuf) {
        // Said rather than swallowed. There is nothing to encrypt with, and
        // sending the plaintext on would be worse than dropping it, so the
        // message ends here -- but a caller that has just been told its write
        // succeeded is owed the reason it did not. This is reachable exactly
        // where the flag's own KDoc says: the server's cancellation is
        // cooperative, so a request past its last suspension point still
        // writes its response.
        //
        // Once, at warn, with the rest at debug: a connection that ends with
        // work in flight drops a burst, and a line per message would bury the
        // first one.
        if (released) {
            reportDroppedWrite(ctx)
            return
        }
        // Each record goes to the transport, and a transport that fails ends
        // the connection from inside that call — so the flag is read on every
        // turn here too. `onWrite`'s `finally` gives the plaintext back either
        // way.
        while (!released && plainBuf.readableBytes > 0) {
            val cipherBuf = ctx.allocator.allocate(TLS_CIPHERTEXT_BUF_SIZE)
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
                    // Stall guard: a well-behaved codec that returns OK while
                    // the plaintext still has readable bytes must have made
                    // forward progress on this call, either by consuming
                    // plaintext or by producing ciphertext. If both counters
                    // are zero the next iteration would re-enter this block
                    // with identical state and spin forever. flushHandshakeResponse
                    // already has an equivalent stall check for its NEED_WRAP
                    // branch; processOutbound was previously missing it.
                    if (result.bytesConsumed == 0 && result.bytesProduced == 0) {
                        val remaining = plainBuf.readableBytes
                        ctx.propagateError(
                            TlsException(
                                "processOutbound stalled: codec returned OK with 0 bytes consumed and 0 bytes produced, " +
                                    "$remaining plaintext bytes remaining",
                                TlsErrorCategory.PROTOCOL_ERROR,
                            ),
                        )
                        return
                    }
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
                    reportConnectionEnded(ctx)
                    return
                }
                TlsResult.NEED_WRAP -> {
                    // NEED_WRAP from protect during application-data encoding
                    // is unexpected. The TLS codec state machine should not
                    // ask the caller to wrap another record mid-plaintext: if
                    // a post-handshake message (e.g. TLS 1.3 KeyUpdate or a
                    // new session ticket) needs to go out, the codec should
                    // either interleave it transparently or surface the need
                    // before plaintext is accepted. Silently breaking the
                    // loop (the previous behaviour) left the caller believing
                    // the write had fully succeeded while only a prefix of
                    // plainBuf had actually been encoded, resulting in wire
                    // truncation with no error signal. Propagate a structured
                    // error so the downstream pipeline can close.
                    val remaining = plainBuf.readableBytes
                    ctx.propagateError(
                        TlsException(
                            "Unexpected NEED_WRAP from protect during application-data encoding, " +
                                "$remaining plaintext bytes remaining",
                            TlsErrorCategory.PROTOCOL_ERROR,
                        ),
                    )
                    return
                }
                TlsResult.NEED_MORE_INPUT -> {
                    // NEED_MORE_INPUT is a signal for unprotect (the codec
                    // needs more ciphertext to decode a record) and is
                    // meaningless on the protect path — protect consumes
                    // plaintext the caller has already handed over, so there
                    // is no additional input the caller can provide. If the
                    // codec still returns this status on an outbound call,
                    // its state machine is in a state the handler cannot
                    // make progress from; fail loudly rather than silently
                    // truncating the write.
                    val remaining = plainBuf.readableBytes
                    ctx.propagateError(
                        TlsException(
                            "Unexpected NEED_MORE_INPUT from protect during application-data encoding, " +
                                "$remaining plaintext bytes remaining",
                            TlsErrorCategory.PROTOCOL_ERROR,
                        ),
                    )
                    return
                }
            }
        }
        checkHandshakeComplete(ctx)
    }

    override fun onFlush(ctx: PipelineHandlerContext) {
        ctx.propagateFlush()
    }

    override fun onClose(ctx: PipelineHandlerContext) {
        // The same set as the other two routes, in a `finally` so a release
        // that throws does not take the rest of the release and the
        // propagation with it.
        //
        // No close reaches this today — nothing in the library walks the
        // pipeline to close — so this is consistency with the routes that do
        // run, not a path under test here. What it is not is a close_notify:
        // the session's own close writes one into its BIO and nothing drains
        // it, which needs a write before the walk continues and is filed.
        try {
            releaseTlsResources()
        } finally {
            ctx.propagateClose()
        }
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
    private fun flushHandshakeResponse(ctx: PipelineHandlerContext): Boolean {
        val emptyBuf = ctx.allocator.allocate(0)
        try {
            var iterations = 0
            // Same reason as the two loops above: this one propagates writes
            // and flushes, either of which can end the connection. Falling
            // out reports success, and the caller's own loop is guarded, so
            // it stops on the next turn rather than reading a freed session.
            while (!released) {
                val cipherBuf = ctx.allocator.allocate(TLS_CIPHERTEXT_BUF_SIZE)
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
                        reportConnectionEnded(ctx)
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

    private fun checkHandshakeComplete(ctx: PipelineHandlerContext) {
        // Guarding the loops was not enough: all three of them are followed by
        // this, and the release lands inside them. Reading
        // `codec.isHandshakeComplete` here would be `SSL_is_init_finished` on a
        // freed session — and this is the window where it bites, since a
        // connection that ended before its handshake finished leaves
        // `handshakeNotified` false, so the condition below does not
        // short-circuit before the read.
        if (released) return
        if (!handshakeNotified && codec.isHandshakeComplete) {
            handshakeNotified = true
            // Handshake completed in time — disarm the deadline.
            handshakeDeadline?.cancel()
            handshakeDeadline = null
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
         * ### Buffer pooling
         *
         * Plaintext buffers (`plaintextBufferSize`, default 16 KiB) are
         * hinted as a hot size class via
         * [io.github.fukusaka.keel.buf.BufferAllocator.hintSizeClass]
         * in [handlerAdded], so inbound plaintext allocations hit the pool
         * on steady-state connections (when the allocator honours the hint;
         * the call is a best-effort no-op for pool-less allocators). Ciphertext buffers (17 KiB) are not
         * pooled: JFR profiling showed TLS buffer allocation accounts for
         * ~1% of total allocation samples, with JSSE crypto byte[] and
         * kernel TLS processing as the dominant costs. Pooling ciphertext
         * produced no measurable throughput improvement (+/-1% noise).
         *
         * [1]: https://www.rfc-editor.org/rfc/rfc5246#section-6.2.3
         * [2]: https://www.rfc-editor.org/rfc/rfc8446#section-5.2
         * [3]: https://www.rfc-editor.org/rfc/rfc8449#section-1
         * [4]: https://www.rfc-editor.org/rfc/rfc8446#section-5.4
         */
        /**
         * Default plaintext buffer size: 2^14 = 16384 (RFC 8446 §5.1 max
         * plaintext record payload). Honours any compliant peer's record
         * size without a `record_overflow` alert. Preserved as the default
         * for [TlsHandler.plaintextBufferSize], so installers that do not
         * pass a per-connection override see no behaviour change.
         */
        public const val TLS_PLAINTEXT_BUF_SIZE_DEFAULT: Int = 16 * 1024

        /**
         * Minimum permitted plaintext buffer size for [TlsHandler]. Set to
         * the RFC 8446 §5.1 ceiling so the receiver can accept any
         * compliant peer record without `record_overflow`; shrinking below
         * this requires advertising a smaller `record_size_limit`
         * (RFC 8449), a follow-up not implemented in any keel TLS backend
         * yet.
         */
        public const val TLS_PLAINTEXT_BUF_SIZE_MIN: Int = 16 * 1024

        /**
         * Maximum permitted plaintext buffer size for [TlsHandler]. 1 MiB
         * matches the upper bound of [io.github.fukusaka.keel.core.IoEngineConfig.readBufferSize]
         * so callers face a uniform envelope across the transport-side
         * read buffer and the TLS-side codec segment.
         */
        public const val TLS_PLAINTEXT_BUF_SIZE_MAX: Int = 1 shl 20

        /**
         * Validates a [TlsHandler.plaintextBufferSize] candidate: a power
         * of two within [TLS_PLAINTEXT_BUF_SIZE_MIN]..[TLS_PLAINTEXT_BUF_SIZE_MAX].
         * Separate from
         * `IoEngineConfig.requireValidReadBufferSize` because the TLS
         * plaintext buffer has a stricter lower bound (the RFC 8446 §5.1
         * ceiling); applying the readBufferSize validator here would not
         * catch the unsafe sub-16 KiB range, and applying this validator
         * to transport read buffers would be a regression.
         */
        public fun requireValidPlaintextBufferSize(size: Int) {
            require(size in TLS_PLAINTEXT_BUF_SIZE_MIN..TLS_PLAINTEXT_BUF_SIZE_MAX) {
                "plaintextBufferSize must be in " +
                    "$TLS_PLAINTEXT_BUF_SIZE_MIN..$TLS_PLAINTEXT_BUF_SIZE_MAX, was $size"
            }
            require(size and (size - 1) == 0) {
                "plaintextBufferSize must be a power of two, was $size"
            }
        }

        /**
         * Maximum ciphertext record on the wire: plaintext (16 KiB) + AEAD
         * overhead (up to ~1 KiB for TLS 1.2 CBC + HMAC-SHA384 with random
         * padding; 256 bytes for TLS 1.3 AEAD). See the original
         * `TLS_RECORD_BUF_SIZE` KDoc above for the full derivation.
         */
        private const val TLS_CIPHERTEXT_BUF_SIZE = 17 * 1024

        /**
         * TLS record header size (RFC 8446 §5.1): `type`[1] +
         * `legacy_record_version`[2] + `length`[2]. Used by
         * [recordSizeIfKnown] to right-size the accumulate buffer once the
         * header (and thus the full record length) is known.
         */
        private const val TLS_RECORD_HEADER_SIZE = 5

        // `maxCount` hint for the plaintext size class — passed to
        // [io.github.fukusaka.keel.buf.BufferAllocator.hintSizeClass] at
        // pipeline setup (size is the per-connection `plaintextBufferSize`,
        // default 16 KiB). Typical HTTPS connection uses 1-2 concurrent
        // inbound buffers; the hint of 4 accommodates a small burst
        // without over-committing memory on allocators that honour it.
        // Callers configuring a larger `plaintextBufferSize` trade pool
        // capacity for per-buffer memory accordingly; the hint count
        // itself is not configurable here (follow-up if a per-bind knob
        // turns out to matter empirically).
        private const val PLAINTEXT_HINT_COUNT = 4

        // Defense-in-depth: bounds total flushHandshakeResponse iterations.
        // A TLS 1.2 flight is typically 2-5 KB; 64 × 17 KB = 1 MB far exceeds
        // any realistic handshake. Complements the bytesProduced == 0 stall check.
        private const val MAX_FLUSH_ITERATIONS = 64
    }
}
