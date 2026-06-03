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
 * ### Ownership semantics
 *
 * This object owns the chunks. Handing it to the transport (via the frame
 * or message it rides on) transfers ownership of *every* chunk — after that
 * the holder must not touch the chunks or this object. On an error path
 * *before* hand-off, the holder must call [release] to free every chunk.
 *
 * **Single-use.** This object is meant to be consumed exactly once:
 *   - one full hand-off (the transport takes every chunk), or
 *   - one full [release] (the holder reclaims them).
 *
 * After either, every accessor on this object ([totalSize], [chunkCount],
 * [chunkAt], [forEach]) is undefined — the underlying chunks may have been
 * recycled into the pool and could be holding bytes for a completely
 * different message. Calling [release] twice is harmless on the [IoBufChunks]
 * wrapper but each underlying `IoBuf` may detect the double-release as an
 * invariant violation, so don't.
 *
 * **No fan-out.** There is intentionally no `retain()` — broadcasting the
 * same payload to N peers requires materialising the bytes first (e.g. via
 * an explicit `copyTo(ByteArray)`). The chunks are pooled buffers whose
 * lifetime is "one logical send"; reference-bumping them across sends would
 * blur that boundary and defeat leak detection.
 *
 * **Partial hand-off.** If the encoder hands off the first N chunks then
 * a downstream `propagateWrite` throws on chunk N+1, the encoder owns the
 * remaining `chunkCount - N` chunks and must release them individually —
 * NOT by calling [release] on this object, which would attempt to release
 * the first N (already owned by the transport) too.
 *
 * A dedicated type (rather than a raw `List<IoBuf>`) keeps the reference-
 * counted ownership explicit and leak-checkable: [release] frees the whole
 * list in one call, so a caller cannot forget a chunk on the abort path.
 * The internal list reference is kept private so callers cannot mutate it.
 *
 * **Not thread-safe.** Constructed and consumed on one connection's pump.
 *
 * @property totalSize the sum of every chunk's `readableBytes`, cached at
 *   construction for the length prefix (chunks are produced in full before
 *   the prefix is written).
 */
public class IoBufChunks(chunks: List<IoBuf>) : Releasable {

    // Defensive copy so the caller can't mutate the backing list after
    // construction; iteration cost is one walk per chunk anyway.
    private val chunks: List<IoBuf> = chunks.toList()

    public val totalSize: Int = this.chunks.sumOf { it.readableBytes }

    /** Number of chunks. */
    public val chunkCount: Int get() = chunks.size

    /**
     * The chunk at [index] (0-based).
     *
     * @throws IndexOutOfBoundsException if [index] is outside `[0, chunkCount)`.
     */
    public fun chunkAt(index: Int): IoBuf {
        if (index < 0 || index >= chunks.size) {
            throw IndexOutOfBoundsException(
                "IoBufChunks chunk index $index out of bounds [0..${chunks.size})",
            )
        }
        return chunks[index]
    }

    /** Invokes [action] for each chunk in order (e.g. one `propagateWrite` per chunk). */
    public inline fun forEach(action: (IoBuf) -> Unit) {
        for (i in 0 until chunkCount) action(chunkAt(i))
    }

    /**
     * Releases every chunk. Returns `true` if at least one chunk's reference
     * count reached zero. Use on the abort path before ownership is
     * transferred to the transport. Calling this after a successful hand-off
     * (or twice) attempts to release already-recycled buffers and may trip
     * the underlying `IoBuf`'s double-release invariant.
     */
    override fun release(): Boolean {
        var freedAny = false
        for (chunk in chunks) {
            if (chunk.release()) freedAny = true
        }
        return freedAny
    }
}
