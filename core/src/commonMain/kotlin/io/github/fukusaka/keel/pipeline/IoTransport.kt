package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.buf.IoBuf

/**
 * Transport-layer I/O operations, shared between [io.github.fukusaka.keel.core.Channel]
 * and the pipeline's [HeadHandler].
 *
 * Engine implementations extract their write/flush logic into an IoTransport
 * so that both the suspend-based Channel API and the zero-suspend pipeline
 * API use the same I/O code path.
 *
 * - **Channel** calls [flush] and suspends on [onFlushComplete] until all bytes are sent.
 * - **HeadHandler** calls [flush] as fire-and-forget (does not set [onFlushComplete]).
 *
 * Engine-specific optimizations (IoMode selection, writev, SEND_ZC) are
 * encapsulated within each IoTransport implementation.
 *
 * All methods must be called on the EventLoop thread.
 */
interface IoTransport {

    /**
     * Buffers [buf] for a subsequent [flush].
     *
     * The transport retains the buffer (calls [IoBuf.retain]).
     * The buffer is released after the flush completes.
     */
    fun write(buf: IoBuf)

    /**
     * Sends all buffered writes to the network.
     *
     * @return true if the flush completed synchronously (all bytes sent),
     *         false if an async send is pending (e.g., io_uring SEND SQE).
     */
    fun flush(): Boolean

    /**
     * Callback invoked when an async flush completes.
     *
     * Set by the transport's write-readiness callback to signal
     * completion. Used internally by [awaitPendingFlush].
     * Pipeline HeadHandler does not set this (fire-and-forget).
     */
    var onFlushComplete: (() -> Unit)?

    /**
     * Suspends until all pending async flush operations complete.
     *
     * Returns immediately if the last [flush] completed synchronously
     * (returned true). Called by Channel mode's [io.github.fukusaka.keel.core.Channel.awaitFlushComplete].
     *
     * Default implementation is no-op (for transports that always flush synchronously).
     */
    suspend fun awaitPendingFlush() {}

    /** Closes the transport and releases resources. */
    fun close()
}
