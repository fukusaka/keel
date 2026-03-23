package io.github.fukusaka.keel.io

/**
 * Suspend-capable byte source backed by [NativeBuf].
 *
 * Unlike kotlinx-io's `RawSource` which is non-suspend, this interface
 * supports coroutine suspension for non-blocking I/O. Each [read] call
 * suspends until data is available, matching [Channel.read]'s semantics.
 *
 * kotlinx-io independent — uses keel's own [NativeBuf] type, enabling
 * zero-copy I/O from kernel to codec layer when combined with
 * [BufferedSuspendSource].
 *
 * @see BufferedSuspendSource for readLine/readByte utilities
 * @see Channel.asSuspendSource for obtaining an instance
 */
interface SuspendSource : AutoCloseable {
    /**
     * Reads bytes into [buf], advancing [NativeBuf.writerIndex].
     *
     * @return number of bytes read, or -1 on EOF.
     */
    suspend fun read(buf: NativeBuf): Int

    override fun close()
}
