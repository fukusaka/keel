package io.github.keel.engine.nodejs

import kotlinx.io.Buffer
import kotlinx.io.RawSource

/**
 * Placeholder [RawSource] for Node.js engine.
 *
 * JS is single-threaded and cannot block on async I/O. The kotlinx-io
 * [RawSource] interface requires synchronous [readAtMostTo], which is
 * incompatible with Node.js's event-driven model.
 *
 * Phase (b) will introduce a coroutine-aware bridge. For now, use
 * [NodeChannel.read] directly for async I/O.
 */
internal class ChannelSource(
    private val channel: NodeChannel,
) : RawSource {

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        throw UnsupportedOperationException(
            "asSource() is not supported on Node.js in Phase (a). " +
                "Use Channel.read(NativeBuf) directly."
        )
    }

    override fun close() {}
}
