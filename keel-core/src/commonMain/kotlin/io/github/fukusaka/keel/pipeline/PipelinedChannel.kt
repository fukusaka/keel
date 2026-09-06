package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.core.Channel
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.io.BufferedSuspendSource
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import kotlinx.coroutines.withContext

/**
 * A channel with an associated [Pipeline] for protocol processing.
 *
 * Extends [Channel] to support both push-based Pipeline I/O (via handler
 * callbacks) and pull-based suspend I/O (via [read]/[write]/[flush]).
 *
 * **Pipeline mode** (push, zero-suspend): engine feeds data into the pipeline
 * via [Pipeline.notifyRead]. Handlers process data synchronously.
 * No [SuspendBridgeHandler] needed.
 *
 * **Coroutine mode** (pull, suspend): a [SuspendBridgeHandler] is installed
 * before TAIL to bridge pipeline callbacks to suspend [read]/[write]/[flush].
 * Used for interactive protocols (SMTP, Redis), proxies, and Ktor integration.
 *
 * Engine implementations create a PipelinedChannel per accepted connection,
 * wiring the pipeline to the underlying transport via [IoTransport].
 */
interface PipelinedChannel : Channel {

    /** The pipeline processing I/O events for this channel. */
    val pipeline: Pipeline

    /**
     * The per-connection [Logger] (typically tagged with the channel /
     * engine name). Components that operate on this channel but are not
     * pipeline handlers — e.g. the WebSocket session core — use it to log
     * connection-scoped diagnostics. Defaults to a no-op so external
     * implementations and lightweight test doubles need not provide one;
     * [AbstractPipelinedChannel] supplies the engine's real logger.
     */
    val logger: Logger get() = NoopLoggerFactory.logger("")

    /** True if the outbound buffer has capacity for more writes. */
    val isWritable: Boolean

    /**
     * Schedules an absolute completion deadline on this channel's EventLoop, for
     * codec/server-level time bounds (e.g. the header-complete timeout that defends
     * against slow-header trickle attacks). Returns a [TimerHandle] to cancel when
     * the phase completes in time, or `null` if the engine has no timer wired.
     *
     * Unlike the transport idle timeout, the deadline is **absolute** — it is not
     * refreshed by I/O progress, so a trickle of bytes cannot defeat it. Backed by
     * the same per-EventLoop scheduler as the idle timeout. **Call on the pipeline
     * (EventLoop) thread.** Default returns `null` so test doubles need not implement
     * it; [AbstractPipelinedChannel] delegates to the transport.
     */
    fun scheduleDeadline(delayMillis: Long, task: () -> Unit): TimerHandle? = null

    /**
     * Enables or disables the read loop on the underlying transport.
     *
     * When `true`, the transport registers for read events and delivers
     * data via the pipeline. When `false`, read events are deregistered.
     *
     * Delegates to [IoTransport.readEnabled].
     */
    var readEnabled: Boolean

    /**
     * Pauses inbound consumption for flow control; see
     * [IoTransport.pauseReads] for the engine contract (stop consuming
     * within a bounded overshoot, no data loss, FIN detection may be
     * delayed). The default delegates to [readEnabled]; the abstract
     * implementation routes to the transport's real pause.
     */
    fun pauseReads() {
        readEnabled = false
    }

    /** Resumes inbound consumption after [pauseReads]. */
    fun resumeReads() {
        readEnabled = true
    }

    override val remoteAddress: SocketAddress? get() = null
    override val localAddress: SocketAddress? get() = null

    /**
     * Default: delegates to [isActive]. Engines should override if they
     * distinguish "transport open" from "ready for I/O" (e.g. half-close:
     * isOpen=true but isActive=false after shutdownOutput).
     */
    override val isOpen: Boolean get() = isActive

    /**
     * Whether the connection ended under the caller — the transport reported
     * its end and the channel closed itself — as opposed to a close this
     * side performed. A [read] after the former is the end of file; after
     * the latter it is a misuse. The default cannot tell and refuses every
     * read after a close; a channel that can tell says so.
     *
     * [read] reads this on the caller's thread, next to [isOpen] and outside
     * the owning context, so an implementation keeps two things: the value
     * is published like [isOpen] is (volatile, or under the same
     * publication), and it turns `true` before [isOpen] turns `false` for
     * an end the transport reported — a reading that sees the close then
     * sees the mark. Otherwise the reading falls into the second case.
     */
    val endedByTransport: Boolean get() = false

    // --- Coroutine mode: suspend I/O with EventLoop thread guarantee ---
    //
    // SuspendBridgeHandler requires all methods (read, onRead, onInactive,
    // write, flush) to execute on the same EventLoop thread.
    // withContext(ioDispatcher) guarantees this for Coroutine mode
    // operations called from any thread (runBlocking, Dispatchers.Default, etc.).
    // When already on the EventLoop, withContext is a no-op.

    /**
     * Lazily installs [SuspendBridgeHandler] in the pipeline.
     *
     * Does NOT start the read loop — call [readEnabled] = true separately
     * to begin receiving data. This separation allows callers that already
     * have their own pipeline-level bridge (e.g. [SuspendMessageBridge])
     * to arm reads without installing an unnecessary [SuspendBridgeHandler].
     *
     * Always called on the I/O thread (via [withContext]).
     */
    fun ensureBridge(): SuspendBridgeHandler

    /**
     * Reads decoded data via [SuspendBridgeHandler] on the EventLoop thread.
     *
     * Installs the bridge (if not yet present) and enables reading on the
     * first call. [withContext] dispatches to [ioDispatcher] (EventLoop) to
     * guarantee single-threaded access to [SuspendBridgeHandler] state.
     *
     * Where the transport reports the peer's end of file apart from the
     * connection's end, that end of file is `-1` once the bridge's queue is
     * drained, the channel stays open and writable, and [close] is the
     * caller's; a transport still making one report for every end ends the
     * read side with it, releasing what was queued. After an
     * end the transport reported — see [endedByTransport] — the channel is
     * closed and a read is `-1` too; after a close this side performed it
     * is a misuse and throws [IllegalStateException].
     *
     * @return number of bytes read, or -1 on EOF.
     */
    override suspend fun read(buf: IoBuf): Int {
        // One reading decides. A second one on this thread could see an end
        // that landed between the two and refuse as a misuse what is the
        // end of file; the channel marks the transport's end before it
        // closes, so a reading that sees the close sees the mark.
        if (!isOpen) {
            if (endedByTransport) return -1
            error("Channel is closed")
        }
        return withContext(ioDispatcher) {
            val bridge = ensureBridge()
            // First-read arming only: while the bridge has suspended reads
            // at its high watermark, re-arming is the bridge's dequeue
            // path's job (at the low watermark) — arming here would defeat
            // the hysteresis and flap the engine's read registration on
            // every call.
            // Nor after the peer's end of file: nothing more will arrive, and
            // arming would only have the transport read the same end again.
            if (!readEnabled && !bridge.readSuspendedByWatermark && !bridge.isEof) readEnabled = true
            bridge.read(buf)
        }
    }

    /**
     * Writes data through the pipeline on the EventLoop thread.
     *
     * Takes [buf] in every outcome. The pipeline has it once the hop to the
     * loop ran; when this call throws before that — the channel is closed,
     * the loop refused the hop, the caller was cancelled while the hop was
     * still queued — the buffer is released here. A cancellation that lands
     * on the way back, after the hop ran, leaves the buffer with the pipeline
     * and is rethrown as it is: a second release here would return to the
     * pool a buffer still queued for the send.
     *
     * A write with nothing to write is the same transfer: the buffer is
     * released and `0` returned. The caller was already told not to touch it
     * after this call, so keeping it here would leave it to nobody.
     *
     * A write after [shutdownOutput] reaches the transport, which releases
     * it unsent; an engine that refuses it earlier must release the buffer
     * before it throws, or the transfer above does not hold there.
     *
     * @return number of bytes buffered (actual send happens on [flush]).
     */
    override suspend fun write(buf: IoBuf): Int {
        if (!isOpen) {
            buf.release()
            error("Channel is closed")
        }
        val n = buf.readableBytes
        if (n == 0) {
            buf.release()
            return 0
        }
        var taken = false
        try {
            withContext(ioDispatcher) {
                taken = true
                pipeline.requestWrite(buf)
            }
        } catch (t: Throwable) {
            if (!taken) buf.release()
            throw t
        }
        return n
    }

    /**
     * Flushes all buffered data on the EventLoop thread and suspends
     * until the flush completes.
     *
     * Dispatches both [requestFlush] and [awaitFlushComplete] to the
     * [ioDispatcher] to guarantee thread safety for engines that
     * require EventLoop-thread-only access (io_uring SQE submission,
     * kqueue/epoll POSIX write).
     */
    override suspend fun flush() {
        check(isOpen) { "Channel is closed" }
        withContext(ioDispatcher) {
            pipeline.requestFlush()
            awaitFlushComplete()
        }
    }

    /**
     * Initiates a flush through the pipeline (fire-and-forget).
     *
     * No-op if the channel is already closed. Non-suspend: callers must
     * ensure this is called from the EventLoop thread or an appropriate
     * context. Use [flush] (suspend) for safe cross-thread flushing.
     */
    override fun requestFlush() {
        if (!isOpen) return
        pipeline.requestFlush()
    }

    // awaitFlushComplete, awaitClosed, shutdownOutput, close: no defaults.
    // AbstractPipelinedChannel provides implementations by delegating to IoTransport.

    /**
     * Returns a [BufferedSuspendSource] for codec-layer reading.
     *
     * If a [SuspendBridgeHandler] is already installed in the pipeline
     * (by a prior [read] call or explicit `ensureBridge()`), returns a
     * push-mode source backed by [SuspendBridgeHandler]'s [OwnedSuspendSource]
     * — handler-processed [IoBuf]s are delivered without copying.
     *
     * Otherwise, falls back to pull-mode via [asSuspendSource] (1 copy per read).
     * The pull-mode path triggers [SuspendBridgeHandler] installation on the
     * first actual read, so it is functionally correct.
     */
    override fun asBufferedSuspendSource(): BufferedSuspendSource {
        val bridge = pipeline.get(SUSPEND_BRIDGE_NAME) as? SuspendBridgeHandler
        return if (bridge != null) {
            BufferedSuspendSource(bridge)
        } else {
            BufferedSuspendSource(asSuspendSource(), allocator)
        }
    }

    companion object {
        /** Handler name used by engine implementations for [SuspendBridgeHandler]. */
        const val SUSPEND_BRIDGE_NAME = "__suspend_bridge__"
    }
}
