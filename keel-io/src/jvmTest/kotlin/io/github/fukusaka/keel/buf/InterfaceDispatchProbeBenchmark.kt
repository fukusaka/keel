package io.github.fukusaka.keel.buf

import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.time.TimeSource

/**
 * JVM counterpart of the Native `InterfaceDispatchProbeBenchmark` (same class
 * name, `keel-io` `nativeTest`): is a genuinely polymorphic interface call
 * more expensive than a monomorphic one on the JVM, the way it measurably is
 * on Kotlin/Native?
 *
 * Expectation going in: HotSpot's inline caches make this a *different*
 * question than on Native. A monomorphic call site devirtualizes via an
 * inline guard; a call site with exactly two receiver types still gets a
 * cheap **bimorphic** inline cache (a type check plus one of two direct
 * calls) after warmup — only a *megamorphic* call site (3+ receiver types)
 * falls back to a true vtable dispatch. So a 2-implementer "polymorphic"
 * shape here is not automatically the worst case the way it is for
 * Kotlin/Native's flat itable lookup.
 *
 * Same three call shapes as the Native sibling, adapted for the JVM (no
 * `platform.posix.getpid`; [ProcessHandle.current] is the JVM's
 * not-compile-time-foldable runtime value):
 * - **polymorphic**: an `Iface`-typed array alternating between two live
 *   implementers at the same call site (bimorphic inline cache territory).
 * - **monomorphic-interface**: one implementer at this call site, a second
 *   kept live elsewhere via a runtime-only condition.
 * - **concrete**: no interface at all — the zero-indirection floor.
 *
 * **Result (Temurin 21, two independent JVM runs, median of 7 samples, 5M
 * iterations/sample):** polymorphic ≈ 1.05-1.06 ns; monomorphic-interface and
 * concrete both round to ≈ 0.00 ns. This confirms the expectation above — a
 * two-receiver call site pays a small, real, reproducible cost (the
 * bimorphic guard), while a call site C2 can prove effectively monomorphic
 * is indistinguishable from no dispatch at all.
 *
 * **Caveat on the 0.00 ns readings.** Unlike a JMH harness, this loop has no
 * blackhole/consumeCPU guard against C2's loop-invariant / strength-reduction
 * optimizations. The monomorphic-interface and concrete trials both
 * accumulate a compile-time-constant per-iteration value with no other
 * observable side effect, which C2 can in principle collapse to a
 * closed-form `blackhole += constant * iterations` without executing the
 * loop body at all — so "0.00 ns" should be read as *too fast to
 * distinguish from full elimination with this harness*, not as a rigorously
 * isolated per-call cost. The polymorphic result is more trustworthy: the
 * `i and 1` alternation makes the accumulated value data-dependent per
 * iteration, which is harder to reduce to a closed form, and the measured
 * cost is real and reproducible across independent runs.
 */
// Re-run: remove @Ignore, then
//   ./gradlew :keel-io:jvmTest --tests "*InterfaceDispatchProbeBenchmark"
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
        println("=== interface dispatch probe (JVM) ===")
        println("polymorphic (2 live impls, alternating)|${fmt(polymorphicTrial())}")
        println("monomorphic-interface (1 impl at call site, other live elsewhere)|${fmt(monomorphicInterfaceTrial())}")
        println("concrete (no interface)|${fmt(concreteTrial())}")
        println("blackhole=$blackhole")
    }

    private var blackhole = 0

    /** Genuinely polymorphic: the call site sees both implementers. */
    private fun polymorphicTrial(): Double {
        val arr = Array<Iface>(2) { if (it % 2 == 0) ImplA() else ImplB() }
        var i = 0
        return measure {
            blackhole += arr[i and 1].v()
            i++
        }
    }

    /**
     * Monomorphic at this call site, but `ImplB` is kept alive elsewhere via a
     * runtime-only (not compile-time-foldable) condition, so the JIT cannot
     * assume `Iface` has a single implementer program-wide.
     */
    private fun monomorphicInterfaceTrial(): Double {
        val useA = ProcessHandle.current().pid() > 0 // always true at runtime, not foldable
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
        const val WARMUP_ITERS = 5_000_000
        const val TRIAL_ITERS = 5_000_000
        const val SAMPLES = 7
    }
}
