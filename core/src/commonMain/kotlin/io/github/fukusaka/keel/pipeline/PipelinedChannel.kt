package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.buf.BufferAllocator

/**
 * A channel with an associated [ChannelPipeline] for protocol processing.
 *
 * Sits above the transport-layer [io.github.fukusaka.keel.core.Channel] and
 * provides callback-based, zero-suspend I/O through the pipeline. Engine
 * implementations create a PipelinedChannel per accepted connection, wiring
 * the pipeline to the underlying transport via [IoTransport].
 */
interface PipelinedChannel {

    /** The pipeline processing I/O events for this channel. */
    val pipeline: ChannelPipeline

    /** True if the channel is connected and ready for I/O. */
    val isActive: Boolean

    /** True if the outbound buffer has capacity for more writes. */
    val isWritable: Boolean

    /** The buffer allocator for this channel. */
    val allocator: BufferAllocator
}
