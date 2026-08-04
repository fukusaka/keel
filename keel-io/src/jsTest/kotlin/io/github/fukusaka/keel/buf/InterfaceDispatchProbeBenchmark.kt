package io.github.fukusaka.keel.buf

import kotlin.js.Date
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.time.TimeSource

/**
 * JS/Node.js counterpart of the Native and JVM `InterfaceDispatchProbeBenchmark`
 * (same class name, `keel-io` `nativeTest` / `jvmTest`): is a genuinely
 * polymorphic interface call more expensive than a monomorphic one under V8?
 *
 * V8 (TurboFan/Ignition) uses inline caches conceptually similar to
 * HotSpot's: monomorphic call sites devirtualize via a hidden-class guard,
 * and a 2-shape call site still gets a cheap polymorphic IC before falling
 * back to a megamorphic lookup at 3+ shapes. So — as on the JVM — a
 * 2-implementer "polymorphic" shape here is not automatically the worst
 * case the way it is for Kotlin/Native's flat itable lookup.
 *
 * Same three call shapes as the Native / JVM siblings, adapted for JS (no
 * `platform.posix.getpid` / `ProcessHandle`; [Date.getTime] is the
 * not-compile-time-foldable runtime value):
 * - **polymorphic**: an `Iface`-typed array alternating between two live
 *   implementers at the same call site.
 * - **monomorphic-interface**: one implementer at this call site, a second
 *   kept live elsewhere via a runtime-only condition.
 * - **concrete**: no interface at all — the zero-indirection floor.
 *
 * **Result (Node.js on V8, two independent runs, median of 7 samples, 1M
 * iterations/sample):** polymorphic ≈ 2.16-2.18 ns, monomorphic-interface ≈
 * 1.45-1.57 ns, concrete ≈ 0.97-1.02 ns. Unlike the JVM sibling, all three
 * shapes stay distinct and non-zero here — V8 did not collapse the
 * monomorphic-interface / concrete trials to an unmeasurable floor the way
 * HotSpot's C2 did, so this reading is more directly comparable to the
 * Native probe's three-tier gradient (polymorphic > monomorphic-interface >
 * concrete), just at different absolute magnitudes.
 *
 * **Caveat.** Like the JVM sibling, this loop has no blackhole/DCE guard
 * beyond the final `println`. V8 can in principle collapse a
 * constant-per-iteration accumulation to a closed form the same way C2
 * does; that it did not here (all three trials stayed non-zero and
 * reproducible) is itself informative, but should not be read as proof V8
 * never performs this optimization for a similar shape. See the JVM
 * sibling's KDoc for the full caveat.
 */
// Re-run: remove @Ignore, then
//   ./gradlew :keel-io:jsNodeTest --tests "*InterfaceDispatchProbeBenchmark"
@Ignore
class InterfaceDispatchProbeBenchmark {

    private interface Iface {
        fun v(): Int
    }

    private class ImplA : Iface {
        override fun v(): Int = 1
    }

    private class ImplB : Iface {
        override fun v(): Int = 2
    }

    private class Concrete {
        /**
         * Returning a constant is the measurement, not an oversight: this is the
         * zero-indirection floor the other two trials are compared against, so the
         * body has to be the cheapest thing a call can do. [ImplA.v] and [ImplB.v]
         * return constants too — they are only unflagged because they are overrides.
         */
        @Suppress("FunctionOnlyReturningConstant")
        fun v(): Int = 1
    }

    @Test
    fun compareDispatchShapes() {
        println("=== interface dispatch probe (JS/Node) ===")
        println("polymorphic (2 live impls, alternating)|${fmt(polymorphicTrial())}")
        println("monomorphic-interface (1 impl at call site, other live elsewhere)|${fmt(monomorphicInterfaceTrial())}")
        println("concrete (no interface)|${fmt(concreteTrial())}")
        println("blackhole=$blackhole")
    }

    private var blackhole = 0

    /** Genuinely polymorphic: the call site sees both implementers. */
    private fun polymorphicTrial(): Double {
        val arr = arrayOf<Iface>(ImplA(), ImplB())
        var i = 0
        return measure {
            blackhole += arr[i and 1].v()
            i++
        }
    }

    /**
     * Monomorphic at this call site, but `ImplB` is kept alive elsewhere via a
     * runtime-only (not compile-time-foldable) condition, so V8 cannot assume
     * `Iface` has a single implementer program-wide.
     */
    private fun monomorphicInterfaceTrial(): Double {
        val useA = Date().getTime() > 0 // always true at runtime, not foldable
        val x: Iface = if (useA) ImplA() else ImplB()
        if (!useA) blackhole += x.v() // keeps ImplB reachable without affecting the hot loop
        return measure { blackhole += x.v() }
    }

    private fun concreteTrial(): Double {
        val x = Concrete()
        return measure { blackhole += x.v() }
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
