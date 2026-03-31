package io.github.fukusaka.keel.engine.iouring

/**
 * Selects the [IoMode] for an I/O operation based on [ConnectionStats].
 *
 * Called by [IoUringChannel] before each flush to determine whether to
 * use direct POSIX syscall or io_uring SQE/CQE.
 *
 * Implementations must be lightweight — called on the hot path per flush.
 *
 * @see IoModeSelectors for built-in strategies
 */
fun interface IoModeSelector {
    fun select(stats: ConnectionStats): IoMode
}

/**
 * Built-in [IoModeSelector] strategies.
 *
 * ```
 * // Always use io_uring CQE (traditional path)
 * IoModeSelectors.CQE
 *
 * // Always try direct syscall first
 * IoModeSelectors.FALLBACK_CQE
 *
 * // Adaptive: start with direct, switch to CQE if EAGAIN rate exceeds 10%
 * IoModeSelectors.eagainThreshold(0.1)
 * ```
 */
object IoModeSelectors {

    /** Always use io_uring SQE/CQE. No direct syscall attempt. */
    val CQE = IoModeSelector { IoMode.CQE }

    /** Always try direct syscall first. CQE fallback on EAGAIN. */
    val FALLBACK_CQE = IoModeSelector { IoMode.FALLBACK_CQE }

    /**
     * Adaptive strategy: starts with [IoMode.FALLBACK_CQE] (direct syscall).
     * Switches to [IoMode.CQE] when the recent EAGAIN rate (EMA) exceeds
     * [ratio] after [minSamples] flush operations.
     *
     * Reverts to [IoMode.FALLBACK_CQE] automatically as the EMA decays
     * when load decreases (exponential decay, half-life ~14 flushes).
     *
     * @param ratio EAGAIN rate threshold (0.0–1.0). Default 0.1 (10%).
     * @param minSamples Minimum flush count before switching. Default 100.
     */
    fun eagainThreshold(
        ratio: Double = 0.1,
        minSamples: Long = 100,
    ) = IoModeSelector { stats ->
        if (stats.totalFlushes >= minSamples && stats.recentEagainRate > ratio)
            IoMode.CQE
        else
            IoMode.FALLBACK_CQE
    }
}
