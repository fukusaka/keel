package io.github.fukusaka.keel.engine.iouring

/**
 * Per-connection I/O statistics for [IoModeSelector] decisions.
 *
 * Uses exponential moving average (EMA) for EAGAIN rate to focus on
 * recent behaviour rather than cumulative history. With α = 0.05,
 * the half-life is ~14 flushes — old data decays exponentially and
 * the selector responds to load changes within ~20 flushes.
 *
 * Updated by [IoUringIoTransport] after each flush operation. All fields
 * are accessed from the EventLoop thread only (no synchronisation needed).
 *
 * Properties are read-only from external code (`internal set`).
 * [IoModeSelector] implementations should treat this as immutable.
 *
 * @see IoModeSelector for using these stats to select [IoMode]
 */
class ConnectionStats internal constructor() {

    /** Total number of flush operations completed. */
    var totalFlushes: Long = 0
        internal set

    /** Total bytes successfully written across all flushes. */
    var totalBytesWritten: Long = 0
        internal set

    /**
     * Exponentially weighted moving average of EAGAIN occurrence.
     *
     * Range: 0.0 (never EAGAIN) to 1.0 (always EAGAIN).
     * Recent flushes are weighted more heavily than older ones.
     * With α = [EMA_ALPHA] (0.05), approximately the last 20 flushes
     * dominate the value.
     */
    var recentEagainRate: Double = 0.0
        internal set

    /**
     * Records the outcome of a flush operation and updates statistics.
     *
     * @param eagain true if direct send() returned EAGAIN during this flush.
     * @param bytesWritten total bytes written in this flush (including CQE fallback).
     */
    internal fun recordFlush(eagain: Boolean, bytesWritten: Long) {
        recentEagainRate = EMA_ALPHA * (if (eagain) 1.0 else 0.0) +
            (1.0 - EMA_ALPHA) * recentEagainRate
        totalFlushes++
        totalBytesWritten += bytesWritten
    }

    companion object {
        /**
         * EMA smoothing factor. 0.05 gives a half-life of ~14 samples
         * (ln(0.5) / ln(1 - 0.05) ≈ 13.5). The selector responds to
         * load changes within ~20 flushes.
         */
        private const val EMA_ALPHA = 0.05
    }
}
