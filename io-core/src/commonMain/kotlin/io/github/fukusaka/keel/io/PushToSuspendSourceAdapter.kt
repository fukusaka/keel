package io.github.fukusaka.keel.io

/**
 * Adapts a [PushSuspendSource] to the pull-model [SuspendSource] interface.
 *
 * Each [read] call delegates to [PushSuspendSource.readOwned], copies the
 * delivered data into the caller's [NativeBuf], and releases the engine-owned
 * buffer. This enables push-model engines to work with the existing
 * [BufferedSuspendSource] codec layer without modification.
 *
 * **Copy overhead**: One memcpy-equivalent per read (engine buffer -> caller buffer).
 * This is the same overhead as the current pull-model path for push engines
 * (e.g., Netty's ByteBuf -> NativeBuf copy). For true zero-copy, use
 * [PushSuspendSource.readOwned] directly (future BufferedSuspendSource push mode).
 *
 * **Leftover handling**: When the engine-owned buffer contains more data than
 * the caller's buffer can accept, the owned buffer is retained and drained
 * on subsequent [read] calls before requesting the next buffer from the source.
 *
 * @param pushSource The push-model source to adapt.
 */
internal class PushToSuspendSourceAdapter(
    private val pushSource: PushSuspendSource,
) : SuspendSource {

    // Retained engine-owned buffer with unconsumed bytes from a previous read().
    // Released when fully drained or on close().
    private var leftover: NativeBuf? = null

    /**
     * Reads bytes into [buf] by delegating to [PushSuspendSource.readOwned].
     *
     * If a previous read left unconsumed bytes in the engine-owned buffer,
     * those are drained first. Otherwise, a new buffer is requested from the
     * push source.
     *
     * @return number of bytes read, or -1 on EOF.
     */
    override suspend fun read(buf: NativeBuf): Int {
        val owned = leftover ?: pushSource.readOwned() ?: return -1
        leftover = null

        val readable = owned.readableBytes
        val writable = buf.writableBytes
        val length = minOf(readable, writable)
        // Byte-by-byte copy via common NativeBuf API.
        // Platform-optimized bulk copy (memcpy / ByteBuffer.put) deferred
        // to a future NativeBuf.copyTo() method or BufferedSuspendSource push mode.
        for (i in 0 until length) {
            buf.writeByte(owned.readByte())
        }

        if (owned.readableBytes > 0) {
            // Retain for the next read() call.
            leftover = owned
        } else {
            owned.release()
        }
        return length
    }

    override fun close() {
        leftover?.release()
        leftover = null
        pushSource.close()
    }
}
