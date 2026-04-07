package io.github.fukusaka.keel.io

import io.github.fukusaka.keel.buf.IoBuf

/**
 * Suspend-capable byte sink backed by [IoBuf].
 *
 * Unlike kotlinx-io's `RawSink` which is non-suspend, this interface
 * supports coroutine suspension for non-blocking I/O. Each [write] call
 * suspends until the data is accepted, matching the pull-model
 * `Channel.write` semantics from the `core` module.
 *
 * kotlinx-io independent — uses keel's own [IoBuf] type.
 *
 * **Thread safety**: implementations are typically single-threaded
 * (EventLoop model). See the specific implementation's KDoc for details.
 *
 * @see BufferedSuspendSink for writeString/writeByte utilities
 */
interface SuspendSink : AutoCloseable {
    /**
     * Writes bytes from [buf] between [IoBuf.readerIndex] and [IoBuf.writerIndex].
     *
     * Suspends until the data is accepted by the underlying transport.
     * Transport-level errors (e.g., connection reset, broken pipe) propagate
     * as platform-specific exceptions.
     *
     * @return number of bytes written.
     */
    suspend fun write(buf: IoBuf): Int

    /**
     * Flushes any buffered data to the underlying transport.
     *
     * Transport-level errors propagate as platform-specific exceptions.
     */
    suspend fun flush()

    /**
     * Releases any resources held by this sink.
     *
     * Does NOT flush buffered data or close the underlying transport.
     * Call [flush] before [close] to ensure all data is sent.
     *
     * Safe to call multiple times (idempotent).
     */
    override fun close()
}
