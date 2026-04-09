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
 *
 * ## Write Backpressure
 *
 * [isWritable] tracks whether the transport can accept more writes without
 * excessive buffering. Implementations maintain a `pendingBytes` counter
 * incremented on [write] and decremented on flush completion. When
 * `pendingBytes >= highWaterMark`, [isWritable] becomes false; when
 * `pendingBytes < lowWaterMark`, it becomes true again.
 *
 * Applications and pipeline handlers should check [isWritable] before
 * writing large amounts of data, and listen for [onWritabilityChanged]
 * to resume writing when the transport drains.
 */
interface IoTransport {

    /**
     * Buffers [buf] for a subsequent [flush].
     *
     * The transport retains the buffer (calls [IoBuf.retain]).
     * The buffer is released after the flush completes.
     *
     * Implementations must update [pendingBytes] and check [highWaterMark]
     * to maintain [isWritable] state.
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

    /**
     * Whether the transport can accept more writes without excessive buffering.
     *
     * Becomes false when pending bytes exceed [highWaterMark], and true
     * again when pending bytes drop below [lowWaterMark]. Applications
     * should check this before writing large responses.
     *
     * Default: always true (for simple transports without backpressure).
     */
    val isWritable: Boolean get() = true

    /**
     * Callback invoked when [isWritable] changes state.
     *
     * Called with `false` when pending bytes cross [highWaterMark] (stop writing),
     * and `true` when they drop below [lowWaterMark] (resume writing).
     */
    var onWritabilityChanged: ((Boolean) -> Unit)? get() = null; set(_) {}

    /** Closes the transport and releases resources. */
    fun close()

    companion object {
        /** Default high water mark: 64 KB. Stop writing when this much data is buffered. */
        const val DEFAULT_HIGH_WATER_MARK = 65536

        /** Default low water mark: 32 KB. Resume writing when buffered data drops below this. */
        const val DEFAULT_LOW_WATER_MARK = 32768
    }
}
