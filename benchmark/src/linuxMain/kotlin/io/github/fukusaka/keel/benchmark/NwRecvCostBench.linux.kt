package io.github.fukusaka.keel.benchmark

/**
 * Linux actual for [runNwRecvCostBench]: returns false because the NW
 * engine is macOS-only. The NativeMain dispatch falls through to the
 * unknown-bench error path.
 */
actual fun runNwRecvCostBench(): Boolean = false
