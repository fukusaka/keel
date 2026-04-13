package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.core.Channel
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.io.BufferedSuspendSource
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

    /** True if the outbound buffer has capacity for more writes. */
    val isWritable: Boolean

    /**
     * Enables or disables the read loop on the underlying transport.
     *
     * When `true`, the transport registers for read events and delivers
     * data via the pipeline. When `false`, read events are deregistered.
     *
     * Delegates to [IoTransport.readEnabled].
     */
    var readEnabled: Boolean

    override val remoteAddress: SocketAddress? get() = null
    override val localAddress: SocketAddress? get() = null

    /**
     * Default: delegates to [isActive]. Engines should override if they
     * distinguish "transport open" from "ready for I/O" (e.g. half-close:
     * isOpen=true but isActive=false after shutdownOutput).
     */
    override val isOpen: Boolean get() = isActive

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
     * @return number of bytes read, or -1 on EOF.
     */
    override suspend fun read(buf: IoBuf): Int {
        check(isOpen) { "Channel is closed" }
        return withContext(ioDispatcher) {
            val bridge = ensureBridge()
            if (!readEnabled) readEnabled = true
            bridge.read(buf)
        }
    }

    /**
     * Writes data through the pipeline on the EventLoop thread.
     *
     * Engines that support half-close should override to check
     * `outputShutdown` before delegating to `super.write(buf)`.
     *
     * @return number of bytes buffered (actual send happens on [flush]).
     */
    override suspend fun write(buf: IoBuf): Int {
        check(isOpen) { "Channel is closed" }
        val n = buf.readableBytes
        if (n == 0) return 0
        withContext(ioDispatcher) {
            pipeline.requestWrite(buf)
        }
        return n
    }

    /**
     * Initiates a flush through the pipeline (fire-and-forget).
     *
     * Non-suspend: callers must ensure this is called from the EventLoop
     * thread or an appropriate context. Use [flush] (suspend) for safe
     * cross-thread flushing.
     */
    override fun requestFlush() {
        check(isOpen) { "Channel is closed" }
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
