package io.github.fukusaka.keel.buf

/**
 * A growable, owned list of pooled [IoBuf] chunks built by **adding existing
 * chunks** — the mutable counterpart of [IoBufChunks].
 *
 * Where [IoBufAccumulator] *writes bytes* into freshly allocated chunks (the
 * tool for a codec draining its own output), this *adds chunks that already
 * exist* as pooled buffers. Use it for the "collect a payload from its arriving
 * chunks" pattern — HTTP request-body aggregation, WS message reassembly, the
 * server's body-read helpers — where copying each inbound buffer into a fresh
 * chunk would be wasted work.
 *
 * ### Usage
 *
 * ```
 * val acc = IoBufMutableChunks()
 * try {
 *     while (true) acc.add(source.next() ?: break)   // ownership transfers in
 * } catch (t: Throwable) {
 *     acc.release()                                   // abort: free what we hold
 *     throw t
 * }
 * val chunks = acc.toIoBufChunks()      // zero-copy hand-off …
 * // …or: val bytes = acc.toByteArray() // …or one flatten copy
 * ```
 *
 * ### Ownership & finalisers (exactly one — single use)
 *
 * [add] transfers ownership of the chunk to this object. Call exactly one
 * finaliser per instance:
 *
 * - [toIoBufChunks]: hand the chunks off as an owned [IoBufChunks] — no copy.
 * - [toByteArray]: flatten the chunks into one contiguous `ByteArray` (a single
 *   copy) and release every chunk.
 * - [release]: free every added chunk on an abort path.
 *
 * After any finaliser this object is spent; do not touch it again.
 *
 * **Not thread-safe** — built and consumed on one connection's pump.
 */
public class IoBufMutableChunks : Releasable {

    private val chunks = ArrayList<IoBuf>()
    private var totalBytes = 0

    /** Total readable bytes across every added chunk. */
    public val size: Int get() = totalBytes

    /** Number of chunks held (excludes dropped empties). */
    public val chunkCount: Int get() = chunks.size

    /**
     * Adds [chunk], transferring ownership to this object. A chunk with no
     * readable bytes is released immediately rather than held, so it never
     * occupies a pool slot or appears in [toIoBufChunks].
     */
    public fun add(chunk: IoBuf) {
        if (chunk.readableBytes > 0) {
            chunks.add(chunk)
            totalBytes += chunk.readableBytes
        } else {
            chunk.release()
        }
    }

    /**
     * Hands the chunks off as an owned [IoBufChunks] (no copy). Ownership
     * transfers to the returned object. Single-use — do not touch this instance
     * afterwards.
     */
    public fun toIoBufChunks(): IoBufChunks = IoBufChunks.takeOwnership(chunks)

    /**
     * Flattens the chunks into one contiguous [ByteArray] (a single copy) and
     * releases every chunk. Single-use. Returns an empty array when nothing was
     * added.
     */
    public fun toByteArray(): ByteArray {
        val out = ByteArray(totalBytes)
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
        totalBytes = 0
        return out
    }

    /**
     * Releases every added chunk — the abort-path finaliser. Returns `true` if
     * at least one chunk's reference count reached zero.
     */
    override fun release(): Boolean {
        var freed = false
        for (chunk in chunks) if (chunk.release()) freed = true
        chunks.clear()
        totalBytes = 0
        return freed
    }
}
