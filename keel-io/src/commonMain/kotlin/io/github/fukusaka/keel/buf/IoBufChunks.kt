package io.github.fukusaka.keel.buf

/**
 * An owned, ordered list of pooled [IoBuf] chunks that together form one
 * logical payload — an outbound codec output gather-written to the
 * transport, or an inbound request body collected via
 * `HttpCall.receiveChunks`.
 *
 * Produced by a streaming codec that drains its output into a fresh pooled
 * buffer per step (rather than copying into one growing `ByteArray`), so the
 * codec output buffers *are* the chunks — no contiguous heap copy, no boxing.
 * An encoder then writes a length prefix from [totalSize] and gather-writes
 * the chunks with one `propagateWrite` per chunk; the transport coalesces
 * them into a single `writev` and releases each chunk after the send.
 * Inbound, `HttpCall.receiveChunks` collects the received body chunks into
 * the same structure; the ownership contract below applies identically,
 * while the length-prefix / gather-write / partial-hand-off notes are
 * outbound-encoder specifics.
 *
 * ### Ownership semantics
 *
 * This object owns the chunks. The owner transfers ownership exactly once —
 * either by handing it off to a consumer (outbound: the transport, via the
 * frame or message it rides on, after which the holder must not touch the
 * chunks or this object) or by calling [release] (the abort path before any
 * hand-off, and the normal path once an inbound body has been consumed).
 * [release] frees every chunk.
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
 * **Partial hand-off (outbound encoders).** If an encoder hands off the
 * first N chunks then a downstream `propagateWrite` throws on chunk N+1, the
 * encoder owns the remaining `chunkCount - N` chunks and must release them
 * individually — NOT by calling [release] on this object, which would attempt
 * to release the first N (already owned by the transport) too.
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
public class IoBufChunks private constructor(
    chunks: List<IoBuf>,
    defensiveCopy: Boolean,
) : Releasable {

    /**
     * Constructs an [IoBufChunks] holding a defensive copy of [chunks]. Safe
     * to call from anywhere — the returned object cannot be affected by
     * later mutation of [chunks]. The defensive copy costs one [ArrayList]
     * allocation per construction.
     *
     * Hot paths that have just constructed a fresh `ArrayList<IoBuf>` and
     * have no use for it afterwards should call [takeOwnership] instead.
     */
    public constructor(chunks: List<IoBuf>) : this(chunks, defensiveCopy = true)

    // The defensive copy guards against external mutation; hot paths that
    // can prove the source list is not touched afterwards use
    // `takeOwnership` to skip the copy.
    private val chunks: List<IoBuf> = if (defensiveCopy) chunks.toList() else chunks

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

    public companion object {
        /**
         * Constructs an [IoBufChunks] that takes ownership of [chunks]
         * **without** the defensive copy the standard [IoBufChunks]
         * constructor makes.
         *
         * **Caller contract.** After this call, [chunks] is owned by the
         * returned [IoBufChunks]:
         *   - the caller MUST NOT mutate it (add / remove / clear);
         *   - the caller MUST NOT release individual entries (the
         *     returned [IoBufChunks] will);
         *   - the caller MUST NOT keep a reference to entries past the
         *     [IoBufChunks]'s single-use lifetime (transport hand-off or
         *     [release]).
         *
         * Use on a hot path that has just constructed a fresh mutable
         * list (typically `ArrayList<IoBuf>`) the caller no longer needs
         * — eliminates the per-message defensive-copy allocation. Misuse
         * leads to either double-release or use-after-free of the
         * underlying pooled buffers, so prefer the safe constructor
         * unless the allocation savings are measured.
         */
        public fun takeOwnership(chunks: List<IoBuf>): IoBufChunks =
            IoBufChunks(chunks, defensiveCopy = false)
    }
}
