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
 */
internal class WsSessionImpl(
    private val channel: PipelinedChannel,
    private val bridge: SuspendMessageBridge<WsFrame>,
) : WsSession {

    private val applicationFrames = Channel<WsFrame>(Channel.UNLIMITED)
    override val incoming: ReceiveChannel<WsFrame> get() = applicationFrames

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

    /** Captured peer CLOSE frame (if any) so the upgrade flow can echo it back. */
    @Volatile
    var peerCloseFrame: WsFrame? = null
        private set

    /**
     * Reads frames from [bridge], handles control frames internally,
     * and forwards application data to [applicationFrames]. Returns
     * when the bridge closes (peer EOF / parse error) or after a CLOSE
     * frame is observed. Does **not** echo the CLOSE — [runWebSocketUpgrade]
     * does that after the user handler has finished draining any
     * application frames already in the channel buffer, so a tail-end
     * echo cannot race ahead of in-flight `send` calls.
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
                    else -> applicationFrames.send(frame)
                }
            }
        } finally {
            applicationFrames.close()
        }
    }

    override suspend fun send(frame: WsFrame) {
        if (closed) return
        // RFC 6455 §5.3 forbids the server from masking outbound
        // frames. Echo handlers naturally feed received (masked)
        // client frames back into send(); strip the mask key here so
        // such code does not need to know about the rule.
        val outgoing = if (frame.maskKey != null) frame.copy(maskKey = null) else frame
        sendInternal(outgoing)
    }

    override suspend fun close(code: WsCloseCode, reason: String) {
        if (!claimClose()) return
        runCatching { sendInternal(WsFrame.close(code, reason)) }
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
        sendInternal(frame)
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
}
