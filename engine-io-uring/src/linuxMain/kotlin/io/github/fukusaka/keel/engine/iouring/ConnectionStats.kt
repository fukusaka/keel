package io.github.fukusaka.keel.engine.iouring

/**
 * Per-connection I/O statistics for [IoModeSelector] decisions.
 *
 * Updated by [IoUringChannel] after each flush operation. All fields
 * are accessed from the EventLoop thread only (no synchronisation needed).
 *
 * @see IoModeSelector for using these stats to select [IoMode]
 */
class ConnectionStats {
    /** Total number of flush operations completed. */
    var totalFlushes: Long = 0

    /** Number of flush operations where direct syscall returned EAGAIN. */
    var writeEagainCount: Long = 0

    /** Total bytes successfully written across all flushes. */
    var totalBytesWritten: Long = 0

    /** EAGAIN ratio: fraction of flushes that encountered EAGAIN. */
    val eagainRatio: Double
        get() = if (totalFlushes > 0) writeEagainCount.toDouble() / totalFlushes else 0.0
}
