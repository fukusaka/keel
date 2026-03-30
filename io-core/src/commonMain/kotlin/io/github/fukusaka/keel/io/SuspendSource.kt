package io.github.fukusaka.keel.io

import io.github.fukusaka.keel.buf.IoBuf

/**
 * Suspend-capable byte source backed by [IoBuf].
 *
 * Unlike kotlinx-io's `RawSource` which is non-suspend, this interface
 * supports coroutine suspension for non-blocking I/O. Each [read] call
 * suspends until data is available, matching the pull-model
 * `Channel.read` semantics from the `core` module.
 *
 * kotlinx-io independent — uses keel's own [IoBuf] type, enabling
 * zero-copy I/O from kernel to codec layer when combined with
 * [BufferedSuspendSource].
 *
 * **Thread safety**: implementations are typically single-threaded
 * (EventLoop model). See the specific implementation's KDoc for details.
 *
 * @see BufferedSuspendSource for readLine/readByte utilities
 * @see PushSuspendSource for the push-model counterpart
 */
interface SuspendSource : AutoCloseable {
    /**
     * Reads bytes into [buf], advancing [IoBuf.writerIndex].
     *
     * Suspends until at least one byte is available or EOF is reached.
     * Transport-level errors (e.g., connection reset) propagate as
     * platform-specific exceptions.
     *
     * @return number of bytes read, or -1 on EOF.
     */
    suspend fun read(buf: IoBuf): Int

    /**
     * Releases any resources held by this source.
     *
     * Does NOT close the underlying transport (Channel). The caller
     * manages the transport lifecycle independently.
     *
     * Safe to call multiple times (idempotent).
     */
    override fun close()
}
