package io.github.fukusaka.keel.io

import io.github.fukusaka.keel.buf.IoBuf

/**
 * Adapts a [OwnedSuspendSource] to the pull-model [SuspendSource] interface.
 *
 * Each [read] call delegates to [OwnedSuspendSource.readOwned], copies the
 * delivered data into the caller's [IoBuf], and releases the engine-owned
 * buffer. This enables push-model engines to work with the existing
 * [BufferedSuspendSource] codec layer without modification.
 *
 * **Copy overhead**: One [IoBuf.copyTo] per read (platform-optimized bulk copy:
 * memcpy on Native, ByteBuffer.put on JVM, Int8Array.set on JS). For true
 * zero-copy, use [OwnedSuspendSource.readOwned] directly (future
 * BufferedSuspendSource push mode).
 *
 * **Leftover handling**: When the engine-owned buffer contains more data than
 * the caller's buffer can accept, the owned buffer is retained and drained
 * on subsequent [read] calls before requesting the next buffer from the source.
 *
 * @param pushSource The push-model source to adapt.
 */
class OwnedToSuspendSourceAdapter(
    private val pushSource: OwnedSuspendSource,
) : SuspendSource {

    // Retained engine-owned buffer with unconsumed bytes from a previous read().
    // Released when fully drained or on close().
    private var leftover: IoBuf? = null

    /**
     * Reads bytes into [buf] by delegating to [OwnedSuspendSource.readOwned].
     *
     * If a previous read left unconsumed bytes in the engine-owned buffer,
     * those are drained first. Otherwise, a new buffer is requested from the
     * push source.
     *
     * @return number of bytes read, or -1 on EOF.
     */
    override suspend fun read(buf: IoBuf): Int {
        val owned = leftover ?: pushSource.readOwned() ?: return -1
        leftover = null

        val length = minOf(owned.readableBytes, buf.writableBytes)
        owned.copyTo(buf, length)

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
