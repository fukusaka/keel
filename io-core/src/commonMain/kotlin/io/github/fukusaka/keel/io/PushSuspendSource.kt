package io.github.fukusaka.keel.io

import io.github.fukusaka.keel.buf.IoBuf

/**
 * Push-model byte source that delivers data in engine-owned [IoBuf]s.
 *
 * Unlike [SuspendSource] where the caller provides the buffer, a
 * [PushSuspendSource] allocates (or selects from a pool) the buffer
 * and fills it with data before returning. This enables zero-copy I/O
 * for push-model engines (io_uring multishot recv, Netty, NWConnection)
 * where the kernel or runtime delivers data in its own buffer.
 *
 * **Ownership contract**: The caller receives ownership of the returned
 * [IoBuf] and MUST call [IoBuf.release] when done reading.
 * The [IoBuf.deallocator] returns the buffer to the engine's pool
 * (e.g., a provided buffer ring in the io_uring engine).
 *
 * **Integration with codec layer**: Use [PushToSuspendSourceAdapter] to
 * adapt a [PushSuspendSource] to [SuspendSource] for use with
 * [BufferedSuspendSource]. A future push-mode [BufferedSuspendSource]
 * constructor will accept [PushSuspendSource] directly for zero-copy.
 *
 * **MemoryOwner not used**: A `MemoryOwner<IoBuf>` wrapper was considered
 * but [IoBuf] already provides [IoBuf.retain]/[IoBuf.release] with
 * deallocator callback, making the wrapper redundant. See design.md §4.7.
 *
 * @see SuspendSource for the pull-model counterpart
 * @see PushToSuspendSourceAdapter for pull-model compatibility
 */
interface PushSuspendSource : AutoCloseable {
    /**
     * Suspends until the engine delivers data in an engine-owned buffer.
     *
     * The returned [IoBuf] contains data between [IoBuf.readerIndex]
     * and [IoBuf.writerIndex]. The caller MUST call [IoBuf.release]
     * after reading, which triggers the [IoBuf.deallocator] to return
     * the buffer to the engine's pool.
     *
     * @return A [IoBuf] with readable data, or `null` on EOF.
     */
    suspend fun readOwned(): IoBuf?

    override fun close()
}
