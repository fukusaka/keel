package io.github.fukusaka.keel.scope

import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.time.TimeSource

/**
 * JVM counterpart of the Native `ScopeLocalCostBench` (`benchmark` module,
 * `--bench=scopelocal-cost`): before/after A/B for the `ScopeLocal`
 * interface-to-`expect class` conversion, on the JVM actual specifically
 * (a plain [java.lang.ThreadLocal] wrapper — no GCD composite, no generic
 * keyed-HashMap fallback the way the Apple / Linux actuals have).
 *
 * Compares `scopeLocal { Box(0) }.current()` against a caller-cached
 * reference (the floor `HttpRequestDecoder`-style per-connection caching
 * achieves).
 *
 * **Result (Temurin 21, checked out at both `main` (interface) and this PR's
 * head (expect class)):** `scopeLocal{}.current()` measured 2.04 ns (before) vs.
 * 2.05 ns (after) — unchanged. The caller-cached floor measured 0.02 ns on
 * both. The ~2 ns is [java.lang.ThreadLocal.get]'s own cost, not
 * interface-vs-class dispatch — consistent with the sibling
 * `InterfaceDispatchProbeBenchmark` finding that a monomorphic JVM interface
 * call devirtualizes below this harness's resolution.
 */
// Re-run: remove @Ignore, then
//   ./gradlew :keel-io:jvmTest --tests "*ScopeLocalCostBenchmark"
@Ignore
class ScopeLocalCostBenchmark {

    private class Box(var n: Int)

    @Test
    fun compareScopeLocalCost() {
        val slot: ScopeLocal<Box> = scopeLocal { Box(0) }
        val cached = slot.current()
        println("=== ScopeLocal.current() cost (JVM) ===")
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
        const val WARMUP_ITERS = 5_000_000
        const val TRIAL_ITERS = 5_000_000
        const val SAMPLES = 7
    }
}
