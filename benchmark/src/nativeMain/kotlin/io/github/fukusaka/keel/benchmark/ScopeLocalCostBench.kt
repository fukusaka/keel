package io.github.fukusaka.keel.benchmark

/**
 * `--bench=scopelocal-cost`: in-process micro-bench (release) quantifying the
 * per-`ScopeLocal.current()` cost on a raw pthread — the kqueue execution
 * context, where there is no GCD install so `current()` takes the
 * `dispatch_get_specific`-miss + fallback path.
 *
 * Compares three resolutions of the same logical slot:
 * - **composite**: `scopeLocal { ... }` — the Apple actual (`DispatchQueueLocal`
 *   over a per-pthread `@ThreadLocal`).
 * - **dql+@ThreadLocal**: a hand-rolled `DispatchQueueLocal(fallback = { @ThreadLocal val })`,
 *   the shape `HttpHeadersPool.apple` uses today — isolates the
 *   `dispatch_get_specific`-miss cost from the generic `@ThreadLocal` HashMap.
 * - **caller-cached**: a value resolved once and held in a local — the floor a
 *   per-connection caller-side cache achieves (no per-read lookup).
 *
 * macOS-only (the composite + GCD path is Apple). Returns false on Linux.
 *
 * Run `./gradlew -Pbenchmark :benchmark:linkReleaseExecutableMacosArm64` then
 * `benchmark/build/bin/macosArm64/releaseExecutable/benchmark.kexe --bench=scopelocal-cost`.
 */
expect fun runScopeLocalCostBench(): Boolean
