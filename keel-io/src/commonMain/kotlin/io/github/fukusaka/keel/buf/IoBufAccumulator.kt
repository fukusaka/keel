package io.github.fukusaka.keel.buf

/**
 * Append-only byte accumulator backed by a chain of pooled [IoBuf] chunks.
 *
 * A streaming codec drains its output into a fixed-capacity [IoBuf]
 * repeatedly; this accumulator stitches those drains into one logical
 * payload **without copying** during accumulation and **without the
 * doubling-realloc churn** of a single growable `ByteArray`.
 *
 * ### Usage — the codec writes straight into the accumulator's chunk
 *
 * The codec writes into [writableChunk]; each time it fills the chunk
 * (`NEED_OUTPUT`) the caller calls [commit] to seal it, after which the
 * next [writableChunk] hands back a fresh pooled chunk. The codec output
 * buffers *become* the payload chunks — accumulation copies nothing.
 *
 * ```
 * val acc = IoBufAccumulator(allocator)
 * try {
 *     while (true) when (codec.update(src, acc.writableChunk())) {
 *         NEED_OUTPUT -> acc.commit()       // seal the full chunk
 *         NEED_INPUT  -> break
 *         FINISHED    -> error(...)
 *     }
 *     acc.commit()                          // seal the trailing partial
 *     val chunks = acc.toIoBufChunks()      // zero-copy hand-off …
 *     // …or: val bytes = acc.toByteArray() // …or one flatten copy
 * } catch (t: Throwable) {
 *     acc.release()                         // frees committed + in-flight chunk
 *     throw t
 * }
 * ```
 *
 * ### Finalisers (exactly one — single use)
 *
 * - [toIoBufChunks]: hand the chunks off as an owned [IoBufChunks] — no
 *   further copy; the transport gather-writes them. Ownership transfers to
 *   the returned object.
 * - [toByteArray]: flatten the chunks into one contiguous `ByteArray` (a
 *   single copy) and release every chunk. For consumers whose boundary is
 *   `ByteArray` (e.g. an application message API). Accumulating N bytes
 *   then flattening copies each byte exactly once — no amortized 2× copy.
 * - [release]: free the chunks on an abort path (covers both committed
 *   chunks and the in-flight [writableChunk]).
 *
 * Call exactly one finaliser per accumulator (single-use, like
 * [IoBufChunks]). The accumulator owns every chunk including the in-flight
 * one, so an abort needs only [release] — there is no caller-held buffer to
 * free separately.
 *
 * [trimTail] removes a fixed number of trailing bytes (e.g. a codec's
 * sync-flush marker) after the last [commit] and before finalising.
 *
 * **Not thread-safe** — built and consumed on one connection's pump.
 *
 * @param allocator source of the pooled chunks.
 * @param chunkSize per-chunk capacity. Defaults to [DEFAULT_CHUNK_SIZE]
 *   (8 KiB) which matches the buffer pool's registered class, so the
 *   chunks recycle; a non-pool-class size still works but allocates fresh.
 */
public class IoBufAccumulator(
    private val allocator: BufferAllocator,
    private val chunkSize: Int = DEFAULT_CHUNK_SIZE,
) : Releasable {

    private val chunks = ArrayList<IoBuf>()
    private var current: IoBuf? = null
    private var committed = 0

    /** Readable bytes committed so far (excludes the in-flight [writableChunk]). */
    public val size: Int get() = committed

    /**
     * The pooled chunk the codec should write into. Returns the in-flight
     * chunk until it is [commit]ed, then hands back a fresh one. Allocated
     * lazily on first call (and after each [commit]).
     */
    public fun writableChunk(): IoBuf = current ?: allocator.allocate(chunkSize).also { current = it }

    /**
     * Seals the in-flight chunk: commits it to the chunk list when it holds
     * bytes, or releases it when empty (no zero-length chunk). A no-op when
     * there is no in-flight chunk. Call on every `NEED_OUTPUT` and once after
     * the codec's final output.
     */
    public fun commit() {
        val c = current ?: return
        current = null
        if (c.readableBytes > 0) {
            chunks.add(c)
            committed += c.readableBytes
        } else {
            c.release()
        }
    }

    /**
     * Removes the last [byteCount] bytes from the committed chunks: releases
     * trailing chunks fully consumed by the trim and decrements the last
     * partial chunk's `writerIndex`. Call after the final [commit]. Used to
     * strip a codec sync-flush marker (e.g. the RFC 7692 `00 00 FF FF`
     * permessage-deflate tail) before finalising.
     *
     * @throws IllegalStateException if fewer than [byteCount] bytes are
     *   committed — a producer contract violation that would otherwise put
     *   un-trimmed bytes on the wire.
     */
    public fun trimTail(byteCount: Int) {
        if (byteCount <= 0) return
        check(committed >= byteCount) {
            "trimTail($byteCount) on $committed committed bytes — producer contract broken"
        }
        var remaining = byteCount
        var idx = chunks.size - 1
        while (remaining > 0 && idx >= 0) {
            val chunk = chunks[idx]
            val n = chunk.readableBytes
            if (n <= remaining) {
                chunk.release()
                chunks.removeAt(idx)
                remaining -= n
                idx--
            } else {
                chunk.writerIndex -= remaining
                remaining = 0
            }
        }
        committed -= byteCount
    }

    /**
     * Hands the committed chunks off as an owned [IoBufChunks] (no further
     * copy). Ownership transfers to the returned object. Single-use — do not
     * touch this accumulator afterwards. Any uncommitted in-flight chunk is
     * dropped, so call [commit] for the trailing partial first.
     */
    public fun toIoBufChunks(): IoBufChunks = IoBufChunks.takeOwnership(chunks)

    /**
     * Flattens the committed chunks into one contiguous [ByteArray] (a single
     * copy) and releases every chunk. Single-use. Commit the trailing partial
     * first.
     */
    public fun toByteArray(): ByteArray {
        val out = ByteArray(committed)
        var offset = 0
        for (chunk in chunks) {
            val n = chunk.readableBytes
            if (n > 0) {
                chunk.readByteArray(out, offset, n)
                offset += n
            }
            chunk.release()
        }
        chunks.clear()
        committed = 0
        return out
    }

    /**
     * Releases every chunk — both committed and the in-flight
     * [writableChunk]. The abort-path finaliser.
     */
    override fun release(): Boolean {
        var freed = false
        for (chunk in chunks) if (chunk.release()) freed = true
        chunks.clear()
        committed = 0
        current?.let { if (it.release()) freed = true }
        current = null
        return freed
    }

    public companion object {
        /** Default per-chunk capacity (8 KiB) — the buffer pool's registered class. */
        public const val DEFAULT_CHUNK_SIZE: Int = 8192
    }
}
