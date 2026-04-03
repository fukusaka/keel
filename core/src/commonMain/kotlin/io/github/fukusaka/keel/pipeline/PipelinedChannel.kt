package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.core.Channel
import io.github.fukusaka.keel.core.SocketAddress

/**
 * A channel with an associated [ChannelPipeline] for protocol processing.
 *
 * Extends [Channel] to support both push-based Pipeline I/O (via handler
 * callbacks) and pull-based suspend I/O (via [read]/[write]/[flush]).
 *
 * **Pipeline mode** (push, zero-suspend): engine feeds data into the pipeline
 * via [ChannelPipeline.notifyRead]. Handlers process data synchronously.
 * No [SuspendBridgeHandler] needed.
 *
 * **Channel mode** (pull, suspend): a [SuspendBridgeHandler] is installed
 * before TAIL to bridge pipeline callbacks to suspend [read]/[write]/[flush].
 * Used for interactive protocols (SMTP, Redis), proxies, and Ktor integration.
 *
 * Engine implementations create a PipelinedChannel per accepted connection,
 * wiring the pipeline to the underlying transport via [IoTransport].
 */
interface PipelinedChannel : Channel {

    /** The pipeline processing I/O events for this channel. */
    val pipeline: ChannelPipeline

    /** True if the outbound buffer has capacity for more writes. */
    val isWritable: Boolean

    // --- Channel defaults for gradual migration ---
    // Engines must override lifecycle methods (close, shutdownOutput) and
    // may override suspend I/O methods if Channel mode is supported.
    // Default suspend I/O throws UnsupportedOperationException — this is
    // a transitional measure (LSP violation) that will be resolved when
    // all engines are migrated to PipelinedChannel.

    override val remoteAddress: SocketAddress? get() = null
    override val localAddress: SocketAddress? get() = null

    /**
     * Default: delegates to [isActive]. Engines should override if they
     * distinguish "transport open" from "ready for I/O" (e.g. half-close:
     * isOpen=true but isActive=false after shutdownOutput).
     */
    override val isOpen: Boolean get() = isActive

    override suspend fun read(buf: IoBuf): Int {
        throw UnsupportedOperationException(
            "suspend read() not available. Install SuspendBridgeHandler or use Pipeline mode."
        )
    }

    override suspend fun write(buf: IoBuf): Int {
        throw UnsupportedOperationException(
            "suspend write() not available. Install SuspendBridgeHandler or use Pipeline mode."
        )
    }

    override suspend fun flush() {
        throw UnsupportedOperationException(
            "suspend flush() not available. Install SuspendBridgeHandler or use Pipeline mode."
        )
    }

    override suspend fun awaitClosed() {}

    /**
     * Default no-op. Engine implementations MUST override to send TCP FIN.
     * Empty default is a transitional measure during engine migration.
     */
    override fun shutdownOutput() {}

    /**
     * Default no-op. Engine implementations MUST override to release
     * transport resources (fd, buffers). Empty default is a transitional
     * measure during engine migration — failure to override will cause
     * resource leaks.
     */
    override fun close() {}
}
