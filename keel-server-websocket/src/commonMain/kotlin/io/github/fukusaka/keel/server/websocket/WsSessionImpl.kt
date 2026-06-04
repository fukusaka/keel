package io.github.fukusaka.keel.server.websocket

import io.github.fukusaka.keel.codec.websocket.WsCloseCode
import io.github.fukusaka.keel.codec.websocket.WsFrame
import io.github.fukusaka.keel.codec.websocket.WsOpcode
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.pipeline.SuspendMessageBridge
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile

/**
 * [WsSession] implementation backed by a [SuspendMessageBridge] of
 * [WsFrame] on a [PipelinedChannel] whose codec stack has been swapped
 * to the WebSocket frame codec
 * ([addWsServerCodec][io.github.fukusaka.keel.codec.websocket.addWsServerCodec]).
 *
 * The pump that filters control frames out of [bridge] before they
 * reach the user's [incoming] channel is started by [runForward];
 * [runWebSocketUpgrade] invokes it before handing the session to user
 * code.
 *
 * When [deflate] is non-null the connection negotiated
 * `permessage-deflate` (RFC 7692): outbound data messages at or above
 * the threshold are compressed and emitted with RSV1=1, and inbound
 * compressed messages are inflated by the [aggregator]. Control frames
 * are never compressed (RFC 7692 §6.1).
 *
 * @param deflate the per-session compression engine, or null when no
 *   extension was negotiated.
 */
internal class WsSessionImpl(
    private val channel: PipelinedChannel,
    private val bridge: SuspendMessageBridge<WsFrame>,
    override val pathParameters: Map<String, String>,
    private val deflate: WsPermessageDeflate? = null,
) : WsSession {

    private val applicationFrames = Channel<WsMessage>(Channel.UNLIMITED)
    override val incoming: ReceiveChannel<WsMessage> get() = applicationFrames

    /**
     * Reassembles inbound data fragments into whole messages
     * (RFC 6455 §5.4), inflating `permessage-deflate` messages when
     * [deflate] is configured.
     */
    private val aggregator = WsFrameAggregator(
        inflater = deflate?.let { engine -> WsMessageInflater { engine.decompress(it) } },
    )

    @Volatile
    private var closed = false

    /**
     * Serialises the CLOSE-once invariant. RFC 6455 §5.5.1 mandates
     * exactly one CLOSE frame per direction. A WebSocket handler may run
     * on a multi-threaded dispatcher, in which case a naive volatile
     * check-and-set on [closed] would race two `close()` callers and put
     * two CLOSE frames on the wire. The mutex makes the close-once
     * decision atomic across dispatchers.
     */
    private val closeLock = Mutex()

    /**
     * Serialises every outbound frame so that:
     * 1. Concurrent callers of [send] cannot corrupt the shared
     *    [WsPermessageDeflate] encoder state (the engine is documented
     *    as not thread-safe — concurrent `compress()` produces a DEFLATE
     *    stream the peer rejects with `Z_DATA_ERROR`).
     * 2. A DATA frame whose `compress()` started before [close] claimed
     *    the close cannot land on the wire **after** the CLOSE frame
     *    (RFC 6455 §5.5.1 forbids any frame after CLOSE in either
     *    direction).
     *
     * Held across the whole `compress` + `requestWrite` + `requestFlush`
     * sequence so the encoder run and the frame emission are atomic.
     * The lock nests inside [closeLock] (CLOSE-once decision happens
     * first; the lock then orders the actual frame emission).
     */
    private val sendLock = Mutex()

    /** Captured peer CLOSE frame (if any) so the upgrade flow can echo it back. */
    @Volatile
    var peerCloseFrame: WsFrame? = null
        private set

    /**
     * Reads frames from [bridge], handles control frames internally,
     * reassembles data fragments into whole messages, and forwards
     * completed [WsMessage]s to [applicationFrames]. Returns when the
     * bridge closes (peer EOF / parse error), after a CLOSE frame is
     * observed, or after a fragmentation / UTF-8 protocol error fails
     * the connection.
     *
     * Control frames (`PING` / `PONG` / `CLOSE`) interleave fragments
     * freely (RFC 6455 §5.4) and bypass [aggregator] entirely, so the
     * in-progress message state is never disturbed.
     *
     * **PONG path is deliberately outside [sendLock]**: RFC 6455 §5.5.1
     * constrains DATA-frame ordering against CLOSE, not control frames,
     * so making the PING→PONG reply wait on a user `send()` that is
     * mid-compress would degrade ping latency for no protocol benefit.
     * The underlying transport's `ioDispatcher` still serialises every
     * `requestWrite` at the byte level, so the wire ordering between
     * PONG and any concurrent DATA frame is well-defined; only the
     * encoder-state sharing concern (which control frames don't touch)
     * requires the lock.
     *
     * Does **not** echo a peer CLOSE — [runWebSocketUpgrade] does that
     * after the user handler has finished draining any messages already
     * buffered, so a tail-end echo cannot race ahead of in-flight `send`
     * calls. A protocol error, however, is fatal and is failed here
     * immediately by sending a CLOSE with the RFC 6455 §7.4.1 code.
     */
    suspend fun runForward() {
        try {
            while (!closed) {
                val result = bridge.receiveCatching()
                if (result.isClosed) break
                val frame = result.getOrThrow()
                when (frame.opcode) {
                    WsOpcode.PING -> sendInternal(WsFrame.pong(frame.payload))
                    WsOpcode.PONG -> Unit // dropped
                    WsOpcode.CLOSE -> {
                        peerCloseFrame = frame
                        break
                    }
                    else -> if (!handleDataFrame(frame)) break
                }
            }
        } finally {
            applicationFrames.close()
        }
    }

    /**
     * Feeds one data frame to [aggregator], forwarding a completed
     * [WsMessage] to [applicationFrames]. On a protocol error, fails
     * the connection with the matching CLOSE code (RFC 6455 §7.4.1)
     * and returns false to stop the pump.
     *
     * @return true to continue the pump, false to stop after a fatal
     *   protocol error.
     */
    private suspend fun handleDataFrame(frame: WsFrame): Boolean =
        when (val outcome = aggregator.feed(frame)) {
            is WsAggregateResult.Incomplete -> true
            is WsAggregateResult.Completed -> {
                applicationFrames.send(outcome.message)
                true
            }
            is WsAggregateResult.ProtocolError -> {
                failConnection(outcome.closeCode, outcome.reason)
                false
            }
        }

    /**
     * Fails the connection per RFC 6455 §7.4.1 by sending a CLOSE with
     * [code]. Best-effort — a write failure is ignored since the pump
     * is tearing down regardless.
     */
    private suspend fun failConnection(code: Int, reason: String) {
        if (!claimClose()) return
        // Same CLOSE-after-DATA ordering rationale as [close].
        sendLock.withLock {
            runCatching { sendInternal(WsFrame.close(WsCloseCode(code), reason)) }
        }
    }

    override suspend fun send(frame: WsFrame) {
        // Serialised through [sendLock] for the same reason as sendData:
        // a raw-frame send racing a close() must not put a DATA frame
        // after CLOSE on the wire (RFC 6455 §5.5.1).
        sendLock.withLock {
            if (closed) return
            // RFC 6455 §5.3 forbids the server from masking outbound
            // frames. Echo handlers naturally feed received (masked)
            // client frames back into send(); strip the mask key here so
            // such code does not need to know about the rule.
            val outgoing = if (frame.maskKey != null) frame.copy(maskKey = null) else frame
            sendInternal(outgoing)
        }
    }

    override suspend fun send(message: WsMessage) {
        when (message) {
            is WsMessage.Text -> send(message.text)
            is WsMessage.Binary -> send(message.bytes)
        }
    }

    override suspend fun send(text: String) {
        sendData(WsOpcode.TEXT, text.encodeToByteArray())
    }

    override suspend fun send(bytes: ByteArray) {
        sendData(WsOpcode.BINARY, bytes)
    }

    /**
     * Sends one unfragmented data message of [opcode] carrying
     * [payload]. When `permessage-deflate` is active and [payload] is at
     * or above the threshold, the payload is compressed (RFC 7692
     * §7.2.1) and the frame's RSV1 bit set; otherwise it is sent
     * verbatim with RSV1=0.
     */
    private suspend fun sendData(opcode: WsOpcode, payload: ByteArray) {
        // The compress + emit pair runs entirely under [sendLock]: the
        // deflate engine is not thread-safe, and the `closed`-after-lock
        // check holds the "no DATA after CLOSE" invariant against a
        // concurrent close() racing in after our pre-lock check.
        sendLock.withLock {
            if (closed) return
            val engine = deflate
            val frame = if (engine != null) {
                when (val result = engine.compress(payload)) {
                    // The frame takes ownership of the compressed chunks; the
                    // encoder gather-writes them and releases each after the
                    // send. If anything between here and a successful
                    // sendInternal throws, the chunks would be orphaned with
                    // no other reference — release them on the catch path.
                    is WsPermessageDeflate.CompressResult.Compressed -> {
                        val chunks = result.chunks
                        try {
                            WsFrame(fin = true, rsv1 = true, opcode = opcode, payloadChunks = chunks)
                        } catch (t: Throwable) {
                            chunks.release()
                            throw t
                        }
                    }
                    is WsPermessageDeflate.CompressResult.Uncompressed ->
                        WsFrame(fin = true, opcode = opcode, payload = result.payload)
                }
            } else {
                WsFrame(fin = true, opcode = opcode, payload = payload)
            }
            try {
                sendInternal(frame)
            } catch (t: Throwable) {
                // sendInternal could not enqueue the frame; the chunks
                // never reached the encoder, so we still own them.
                frame.payloadChunks?.release()
                throw t
            }
        }
    }

    override suspend fun close(code: WsCloseCode, reason: String) {
        if (!claimClose()) return
        // Take [sendLock] so the CLOSE frame is sequenced after any
        // in-flight send() that won the pre-lock `if (closed) return`
        // check before we set `closed = true` in claimClose. Inside
        // those sends the post-lock re-check then sees `closed == true`
        // and the send returns without emitting — leaving CLOSE as the
        // final wire-level frame, as RFC 6455 §5.5.1 requires.
        sendLock.withLock {
            runCatching { sendInternal(WsFrame.close(code, reason)) }
        }
        applicationFrames.close()
    }

    /**
     * Internal: emit a frame as the closing handshake. Used by
     * [runWebSocketUpgrade] to echo the peer's CLOSE. No-op when the
     * session has already initiated its own close — the user-driven
     * `session.close(...)` path always sends CLOSE first, so echoing
     * again would put two CLOSE frames on the wire if the peer's ACK
     * arrives before pump teardown (RFC 6455 §5.5.1: only one CLOSE
     * each direction).
     */
    suspend fun sendRaw(frame: WsFrame) {
        if (!claimClose()) return
        // Same CLOSE-after-DATA ordering rationale as [close].
        sendLock.withLock { sendInternal(frame) }
    }

    /**
     * Atomically claims the right to send the CLOSE frame. Returns
     * true to the first caller (which becomes the close-initiator
     * and is expected to follow up with [sendInternal] of a CLOSE
     * frame plus closing [applicationFrames]); subsequent callers
     * see false and must back off.
     */
    private suspend fun claimClose(): Boolean = closeLock.withLock {
        if (closed) {
            false
        } else {
            closed = true
            true
        }
    }

    private suspend fun sendInternal(frame: WsFrame) {
        withContext(channel.ioDispatcher) {
            channel.pipeline.requestWrite(frame)
            channel.pipeline.requestFlush()
        }
    }

    /**
     * Releases the `permessage-deflate` engine's native resources.
     * Invoked by [runWebSocketUpgrade] once the session has fully ended.
     * No-op when no extension was negotiated.
     */
    fun releaseDeflate() {
        deflate?.close()
    }
}
