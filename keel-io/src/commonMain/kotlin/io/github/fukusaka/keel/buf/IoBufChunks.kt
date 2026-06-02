package io.github.fukusaka.keel.buf

/**
 * An owned, ordered list of pooled [IoBuf] chunks that together form one
 * logical outbound payload.
 *
 * Produced by a streaming codec that drains its output into a fresh pooled
 * buffer per step (rather than copying into one growing `ByteArray`), so the
 * codec output buffers *are* the chunks — no contiguous heap copy, no boxing.
 * An encoder then writes a length prefix from [totalSize] and gather-writes
 * the chunks with one `propagateWrite` per chunk; the transport coalesces
 * them into a single `writev` and releases each chunk after the send.
 *
 * **Ownership**: this object owns the chunks. Handing it to the transport
 * (via the frame / message it rides on) transfers ownership — after that the
 * holder must not touch the chunks. On an error path *before* hand-off, the
 * holder must call [release] to free every chunk (see [release]).
 *
 * A dedicated type (rather than a raw `List<IoBuf>`) keeps the reference-
 * counted ownership explicit and leak-checkable: [release] frees the whole
 * list in one call, so a caller cannot forget a chunk on the abort path.
 *
 * **Not thread-safe.** Constructed and consumed on one connection's pump.
 *
 * @property totalSize the sum of every chunk's `readableBytes`, cached at
 *   construction for the length prefix (chunks are produced in full before
 *   the prefix is written).
 */
class IoBufChunks(private val chunks: List<IoBuf>) : Releasable {

    val totalSize: Int = chunks.sumOf { it.readableBytes }

    /** Number of chunks. */
    val chunkCount: Int get() = chunks.size

    /** The chunk at [index] (0-based). */
    fun chunkAt(index: Int): IoBuf = chunks[index]

    /** Invokes [action] for each chunk in order (e.g. one `propagateWrite` per chunk). */
    inline fun forEach(action: (IoBuf) -> Unit) {
        for (i in 0 until chunkCount) action(chunkAt(i))
    }

    /**
     * Releases every chunk. Returns `true` if at least one chunk's reference
     * count reached zero. Use on the abort path before ownership is
     * transferred to the transport.
     */
    override fun release(): Boolean {
        var freedAny = false
        for (chunk in chunks) {
            if (chunk.release()) freedAny = true
        }
        return freedAny
    }
}
