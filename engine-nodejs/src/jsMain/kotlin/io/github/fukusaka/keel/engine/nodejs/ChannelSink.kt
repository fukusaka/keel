package io.github.fukusaka.keel.engine.nodejs

import kotlinx.io.Buffer
import kotlinx.io.RawSink

/**
 * Placeholder [RawSink] for Node.js engine.
 *
 * JS is single-threaded and cannot block on async I/O. The kotlinx-io
 * [RawSink] interface requires synchronous [write]/[flush], which is
 * incompatible with Node.js's event-driven model.
 *
 * Phase (b) will introduce a coroutine-aware bridge. For now, use
 * [NodeChannel.write]/[NodeChannel.flush] directly for async I/O.
 */
internal class ChannelSink(
    private val channel: NodeChannel,
) : RawSink {

    override fun write(source: Buffer, byteCount: Long) {
        throw UnsupportedOperationException(
            "asSink() is not supported on Node.js in Phase (a). " +
                "Use Channel.write(NativeBuf)/flush() directly."
        )
    }

    override fun flush() {
        throw UnsupportedOperationException(
            "asSink() is not supported on Node.js in Phase (a)."
        )
    }

    override fun close() {}
}
