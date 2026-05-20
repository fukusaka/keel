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
 * **Two scenarios**:
 *
 * - **wrap-path**: drives `keel_nw_test_dispatch_handle` (production
 *   `keel_nw_dispatch_received` with a pre-made single-region handle),
 *   the callback wraps the region as an IoBuf via
 *   `wrapExternalNativePtr` (the Segment-backed wrap as of PR #581),
 *   releases the IoBuf (which fires the handle release through
 *   `keel_nw_dispatch_data_release`), and loops. After PR (α)
 *   replaced the production wrap with the engine-direct
 *   `DispatchDataIoBuf`, this measurement still represents the
 *   Kotlin-side allocation + release cost class — both implementations
 *   pay `dispatch_data_release` at refcount-zero, both allocate per
 *   receive (Segment-backed = 4 allocations / engine-direct = 1),
 *   and the wrap-vs-copy comparison answers the larger design
 *   question (is zero-copy faster than memcpy at this byte size).
 *
 * - **copy-path**: drives `keel_nw_test_dispatch_handle_copyonly`
 *   (bench-only helper that forces the multi-region memcpy branch on
 *   any handle, bypassing the zero-copy detection). The callback
 *   receives bytes already memcpy'd into a pre-allocated
 *   pool-backed IoBuf — identical to the pre-zero-copy production
 *   path. Loops.
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
