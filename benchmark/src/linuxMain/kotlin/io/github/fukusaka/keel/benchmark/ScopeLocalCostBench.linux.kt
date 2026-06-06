package io.github.fukusaka.keel.benchmark

/** No-op on Linux: the composite + GCD path measured here is Apple-only. */
actual fun runScopeLocalCostBench(): Boolean = false
