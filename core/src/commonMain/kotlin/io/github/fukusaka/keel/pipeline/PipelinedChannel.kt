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
    // Engines override these. Default implementations throw to catch
    // unimplemented methods during migration.

    override val remoteAddress: SocketAddress? get() = null
    override val localAddress: SocketAddress? get() = null
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
    override fun shutdownOutput() {}
    override fun close() {}
}
