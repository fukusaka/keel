package io.github.fukusaka.keel.benchmark

/**
 * `--bench=nw-recv-cost`: in-process micro-bench comparing the
 * production NW receive path (dual-path zero-copy single-region +
 * memcpy fallback) against an "always memcpy" baseline.
 *
 * Runs only on macOS (the NW engine is macOS-only). On Linux the bench
 * returns false to signal "not available on this target" and the CLI
 * dispatch falls through to the unknown-bench error.
 *
 * **What it measures**: full per-receive cost including the Kotlin-side
 * IoBuf wrap that production runs each callback. The original PoC
 * micro-bench (deleted with the investigation branch) measured only
 * the C-side `dispatch_data_apply` cost and missed the Kotlin
 * allocation cost that turned the initial production rollout into a
 * 16% regression — see
 * `benchmark/results-summary/2026-05-20-nw-zerocopy-recv-ab.md` for the
 * full investigation log. This bench is the honest, end-to-end
 * version: future re-validations can run it and get numbers that
 * track production A/B trends.
 *
 * **Three scenarios**:
 *
 * - **segwrap-path** (`wrapExternalNativePtr`, generic wrap): callback
 *   wraps via `wrapExternalNativePtr` (`NativeIoBuf` +
 *   `ExternalWrapOwner` closure). Kept as a comparator — production
 *   switched to the engine-direct path in PR #583.
 *
 * - **engdir-path** (`DispatchDataIoBuf`, engine-direct as of PR
 *   #583, current production wrap): callback allocates a small
 *   holder of identical field shape to `DispatchDataIoBuf` (used as
 *   a proxy because `DispatchDataIoBuf` itself is `internal` to
 *   keel-engine-nwconnection) and releases the handle. 1 allocation
 *   per receive.
 *
 * - **copy-path**: drives `keel_nw_test_dispatch_handle_copyonly`
 *   (bench-only helper that forces the multi-region memcpy branch on
 *   any handle, bypassing the zero-copy detection). Identical to the
 *   pre-zero-copy production path; represents the multi-region
 *   fallback cost in current production.
 *
 * Both paths share the same outer dispatch_data_t (created once,
 * retained externally, released after the bench), so per-iter cost
 * isolates the receive-callback work.
 *
 * Run `./gradlew -Pbenchmark :benchmark:linkReleaseExecutableMacosArm64`
 * then `benchmark/build/bin/macosArm64/releaseExecutable/benchmark.kexe --bench=nw-recv-cost`.
 *
 * @return true if the bench ran (macOS), false otherwise (Linux).
 */
expect fun runNwRecvCostBench(): Boolean
