package io.github.fukusaka.keel.scope

import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.time.TimeSource

/**
 * JS/Node.js counterpart of the Native `ScopeLocalCostBench` (`benchmark`
 * module, `--bench=scopelocal-cost`) and the JVM `ScopeLocalCostBenchmark`:
 * before/after A/B for the `ScopeLocal` interface-to-`expect class`
 * conversion, on the JS actual specifically (a lazily-initialized singleton —
 * no GCD composite, no per-thread HashMap, since Node.js is single-threaded).
 *
 * Compares `scopeLocal { Box(0) }.current()` against a caller-cached
 * reference (the floor per-connection caching achieves).
 *
 * **Result (Node.js on V8, checked out at both `main` (interface) and this
 * PR's head (expect class)):** `scopeLocal{}.current()` measured 1.36 ns
 * (before) vs. 1.41 ns (after); the caller-cached floor measured 1.54 ns
 * (before) vs. 1.44 ns (after) — both pairs overlap within run-to-run noise,
 * and `scopeLocal{}.current()` is already indistinguishable from the cached
 * floor on *either* revision. The `SingletonScopeLocal` actual's own body (a
 * nullable-field check) is cheap enough that interface-vs-class dispatch
 * does not surface here, unlike the sibling `InterfaceDispatchProbeBenchmark`
 * which isolates dispatch cost directly and does show a real gap.
 */
// Re-run: remove @Ignore, then
//   ./gradlew :keel-io:jsNodeTest --tests "*ScopeLocalCostBenchmark"
@Ignore
class ScopeLocalCostBenchmark {

    private class Box(var n: Int)

    @Test
    fun compareScopeLocalCost() {
        val slot: ScopeLocal<Box> = scopeLocal { Box(0) }
        val cached = slot.current()
        println("=== ScopeLocal.current() cost (JS/Node) ===")
        println("scopeLocal{}.current()|${fmt(scopeLocalTrial(slot))}")
        println("caller-cached (resolved once, held)|${fmt(cachedTrial(cached))}")
        println("blackhole=$blackhole")
    }

    private var blackhole = 0

    private fun scopeLocalTrial(slot: ScopeLocal<Box>): Double = measure {
        blackhole += slot.current().n
    }

    private fun cachedTrial(cached: Box): Double = measure {
        blackhole += cached.n
    }

    private inline fun measure(op: () -> Unit): Double {
        var w = 0
        while (w < WARMUP_ITERS) {
            op()
            w++
        }
        val samples = DoubleArray(SAMPLES)
        for (t in 0 until SAMPLES) {
            val mark = TimeSource.Monotonic.markNow()
            var i = 0
            while (i < TRIAL_ITERS) {
                op()
                i++
            }
            samples[t] = mark.elapsedNow().inWholeNanoseconds.toDouble() / TRIAL_ITERS
        }
        samples.sort()
        return samples[SAMPLES / 2]
    }

    private fun fmt(v: Double): String {
        val scaled = (v * 100).toLong()
        return "${scaled / 100}.${(scaled % 100).toString().padStart(2, '0')}"
    }

    private companion object {
        const val WARMUP_ITERS = 1_000_000
        const val TRIAL_ITERS = 1_000_000
        const val SAMPLES = 7
    }
}
