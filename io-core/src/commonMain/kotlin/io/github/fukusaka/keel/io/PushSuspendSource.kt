package io.github.fukusaka.keel.io

/**
 * Push-model byte source that delivers data in engine-owned [NativeBuf]s.
 *
 * Unlike [SuspendSource] where the caller provides the buffer, a
 * [PushSuspendSource] allocates (or selects from a pool) the buffer
 * and fills it with data before returning. This enables zero-copy I/O
 * for push-model engines (io_uring multishot recv, Netty, NWConnection)
 * where the kernel or runtime delivers data in its own buffer.
 *
 * **Ownership contract**: The caller receives ownership of the returned
 * [NativeBuf] and MUST call [NativeBuf.release] when done reading.
 * The [NativeBuf.deallocator] returns the buffer to the engine's pool
 * (e.g., a provided buffer ring in the io_uring engine).
 *
 * **Integration with codec layer**: Use [PushToSuspendSourceAdapter] to
 * adapt a [PushSuspendSource] to [SuspendSource] for use with
 * [BufferedSuspendSource].
 *
 * @see SuspendSource for the pull-model counterpart
 * @see PushToSuspendSourceAdapter for pull-model compatibility
 */
interface PushSuspendSource : AutoCloseable {
    /**
     * Suspends until the engine delivers data in an engine-owned buffer.
     *
     * The returned [NativeBuf] contains data between [NativeBuf.readerIndex]
     * and [NativeBuf.writerIndex]. The caller MUST call [NativeBuf.release]
     * after reading, which triggers the [NativeBuf.deallocator] to return
     * the buffer to the engine's pool.
     *
     * @return A [NativeBuf] with readable data, or `null` on EOF.
     */
    suspend fun readOwned(): NativeBuf?

    override fun close()
}
