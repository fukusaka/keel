package io.github.fukusaka.keel.io

import io.github.fukusaka.keel.buf.IoBuf

/**
 * Suspend-capable byte sink backed by [IoBuf].
 *
 * Unlike kotlinx-io's `RawSink` which is non-suspend, this interface
 * supports coroutine suspension for non-blocking I/O. Each [write] call
 * suspends until the data is accepted, matching [Channel.write]'s semantics.
 *
 * kotlinx-io independent — uses keel's own [IoBuf] type.
 *
 * @see BufferedSuspendSink for writeString/writeByte utilities
 * @see Channel.asSuspendSink for obtaining an instance
 */
interface SuspendSink : AutoCloseable {
    /**
     * Writes bytes from [buf], advancing [IoBuf.readerIndex].
     *
     * @return number of bytes written.
     */
    suspend fun write(buf: IoBuf): Int

    /** Flushes any buffered data to the underlying transport. */
    suspend fun flush()

    override fun close()
}
