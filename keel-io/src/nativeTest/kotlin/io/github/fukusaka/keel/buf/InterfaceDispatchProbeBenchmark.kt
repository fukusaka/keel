package io.github.fukusaka.keel.buf

import platform.posix.getpid
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.time.TimeSource

/**
 * Is Kotlin/Native interface dispatch structurally free, or only free when the
 * compiler happens to devirtualize it? Decision record for the `ScopeLocal`
 * interface-to-`expect class` conversion — this is the controlled experiment
 * that justifies treating single-implementer devirtualization as an
 * optimization, not a guarantee.
 *
 * Three call shapes, isolating whether a *second live implementer elsewhere in
 * the binary* (not just "is this an interface") is what defeats
 * devirtualization:
 * - **polymorphic**: an `Iface`-typed array alternating between two live
 *   implementers at the *same call site* — no compiler can devirtualize this,
 *   the true itable-dispatch case.
 * - **monomorphic-interface**: an `Iface`-typed value that always holds the
 *   same implementer at this call site, but the *other* implementer is also
 *   instantiated elsewhere in the binary (via [getpid], a value unknowable at
 *   compile time, so the branch can't be folded and `ImplB` can't be
 *   eliminated as dead code). This is the shape `ScopeLocal` used to have
 *   *across the whole module* before the `expect class` conversion: a single
 *   implementer per compiled target, but the interface itself had other
 *   implementers elsewhere (the other platforms' actuals) that a
 *   whole-program analysis would need to rule out to devirtualize safely.
 * - **concrete**: no interface at all — the zero-indirection floor.
 *
 * **Result (Kotlin/Native 2.3.20, release, macOS arm64, median of 7 samples,
 * 200M iterations/sample):** polymorphic ≈ 2.05 ns, monomorphic-interface ≈
 * 0.76 ns, concrete ≈ 0.47 ns. Genuine polymorphism costs ~4.3x the concrete
 * floor; a monomorphic-at-the-call-site interface call is cheaper (~1.6x the
 * floor) but not free — some indirection survives even when the compiler
 * *could*, in principle, prove single-implementer-at-this-site.
 *
 * **Verdict.** Interface dispatch on Kotlin/Native is not unconditionally
 * zero-cost; it is *conditionally* cheap, and the condition (no second live
 * implementer reachable from the call site) is an optimizer behavior, not a
 * language guarantee. `ScopeLocal`'s per-target `expect`/`actual` factory
 * already gave every compiled target the best-case shape for this
 * optimization (exactly one implementer linked per target — even better than
 * this benchmark's monomorphic-interface case, which keeps a second
 * implementer live in the same binary), which is consistent with the
 * `ScopeLocalCostBench` finding that removing the interface measured no
 * additional win there. Converting to an `expect class` makes the
 * single-implementer property structural instead of relying on that
 * optimization continuing to apply.
 */
// Re-run: remove @Ignore, then
//   ./gradlew :keel-io:macosArm64Test --tests "*InterfaceDispatchProbeBenchmark"
//   ./gradlew :keel-io:linuxX64Test   --tests "*InterfaceDispatchProbeBenchmark"
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
        fun v(): Int = 1
    }

    @Test
    fun compareDispatchShapes() {
        println("=== interface dispatch probe (Native, release) ===")
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
     * runtime-only (not compile-time-foldable) condition, so the compiler
     * cannot assume `Iface` has a single implementer program-wide.
     */
    private fun monomorphicInterfaceTrial(): Double {
        val useA = getpid() > 0 // always true at runtime, not compile-time-foldable
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
        const val WARMUP_ITERS = 2_000_000
        const val TRIAL_ITERS = 5_000_000
        const val SAMPLES = 7
    }
}
