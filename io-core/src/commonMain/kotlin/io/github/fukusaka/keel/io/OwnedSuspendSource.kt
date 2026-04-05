package io.github.fukusaka.keel.io

import io.github.fukusaka.keel.buf.IoBuf

/**
 * Push-model byte source that delivers data in engine-owned [IoBuf]s.
 *
 * Unlike [SuspendSource] where the caller provides the buffer, a
 * [OwnedSuspendSource] allocates (or selects from a pool) the buffer
 * and fills it with data before returning. This enables zero-copy I/O
 * for push-model engines (io_uring multishot recv, Netty, NWConnection)
 * where the kernel or runtime delivers data in its own buffer.
 *
 * **Ownership contract**: The caller receives ownership of the returned
 * [IoBuf] and MUST call [IoBuf.release] when done reading.
 * The [IoBuf]'s deallocator returns the buffer to the engine's pool
 * (e.g., a provided buffer ring in the io_uring engine).
 *
 * **Integration with codec layer**: Use the push-mode
 * [BufferedSuspendSource] constructor for zero-copy codec integration.
 * Alternatively, [PushToSuspendSourceAdapter] provides pull-model
 * compatibility with one [IoBuf.copyTo] per read.
 *
 * **MemoryOwner not used**: A `MemoryOwner<IoBuf>` wrapper was considered
 * but [IoBuf] already provides [IoBuf.retain]/[IoBuf.release] with
 * deallocator callback, making the wrapper redundant. See design.md §4.7.
 *
 * **Thread safety**: implementations are typically single-threaded
 * (EventLoop model). [readOwned] dispatches to the EventLoop when
 * called from an external thread.
 *
 * @see SuspendSource for the pull-model counterpart
 * @see PushToSuspendSourceAdapter for pull-model compatibility
 * @see BufferedSuspendSource for zero-copy push-mode reading
 */
interface OwnedSuspendSource : AutoCloseable {
    /**
     * Suspends until the engine delivers data in an engine-owned buffer.
     *
     * The returned [IoBuf] contains data between [IoBuf.readerIndex]
     * and [IoBuf.writerIndex]. The caller MUST call [IoBuf.release]
     * after reading, which triggers the deallocator to return
     * the buffer to the engine's pool.
     *
     * @return An [IoBuf] with readable data, or `null` on EOF.
     */
    suspend fun readOwned(): IoBuf?

    /**
     * Releases any resources held by this source (e.g., cancels
     * in-flight multishot recv operations).
     *
     * Does NOT release [IoBuf]s already returned by [readOwned] —
     * the caller is responsible for releasing those.
     *
     * Safe to call multiple times (idempotent).
     */
    override fun close()
}
