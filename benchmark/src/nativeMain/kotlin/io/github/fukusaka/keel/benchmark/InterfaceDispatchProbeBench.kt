package io.github.fukusaka.keel.benchmark

import kotlin.time.TimeSource
import platform.posix.getpid

private interface ProbeIface {
    fun v(): Int
}

private class ProbeImplA : ProbeIface {
    override fun v(): Int = 1
}

private class ProbeImplB : ProbeIface {
    override fun v(): Int = 2
}

private class ProbeConcrete {
    fun v(): Int = 1
}

private var probeBlackhole = 0

private const val WARMUP_ITERS = 20_000_000
private const val TRIAL_ITERS = 200_000_000
private const val SAMPLES = 7

private inline fun probeMeasure(op: () -> Unit): Double {
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

private fun probeFmt(v: Double): String {
    val scaled = (v * 100).toLong()
    return "${scaled / 100}.${(scaled % 100).toString().padStart(2, '0')}"
}

private fun polymorphicTrial(): Double {
    val arr = Array<ProbeIface>(2) { if (it % 2 == 0) ProbeImplA() else ProbeImplB() }
    var i = 0
    return probeMeasure {
        probeBlackhole += arr[i and 1].v()
        i++
    }
}

private fun monomorphicInterfaceTrial(): Double {
    val useA = getpid() > 0
    val x: ProbeIface = if (useA) ProbeImplA() else ProbeImplB()
    if (!useA) probeBlackhole += x.v()
    return probeMeasure { probeBlackhole += x.v() }
}

private fun concreteTrial(): Double {
    val x = ProbeConcrete()
    return probeMeasure { probeBlackhole += x.v() }
}

/**
 * `--bench=iface-probe`: release-mode A/B/C of Kotlin/Native interface dispatch
 * cost, isolating whether a *second live implementer elsewhere in the binary*
 * (not just "is this an interface") defeats devirtualization. The
 * `keel-io` test decision record (`InterfaceDispatchProbeBenchmark`) documents
 * this same experiment's methodology and its role in the `ScopeLocal`
 * interface-to-`expect class` conversion; this file is its release-build
 * confirmation harness (the `nativeTest` debug build already agreed closely).
 *
 * - **polymorphic**: an interface-typed array alternating between two live
 *   implementers at the same call site — true itable dispatch, no
 *   devirtualization possible.
 * - **monomorphic-interface**: a value that always holds the same implementer
 *   at this call site, but the other implementer is kept live elsewhere via
 *   [getpid] (unknowable at compile time, so it can't be folded away).
 * - **concrete**: no interface at all — the zero-indirection floor.
 *
 * Run `./gradlew -Pbenchmark :benchmark:linkReleaseExecutableMacosArm64` then
 * `benchmark/build/bin/macosArm64/releaseExecutable/benchmark.kexe --bench=iface-probe`.
 */
fun runInterfaceDispatchProbeBench() {
    println("=== interface dispatch probe (Native, release) ===")
    println("polymorphic (2 live impls, alternating)|${probeFmt(polymorphicTrial())}")
    println("monomorphic-interface (1 impl at call site, other live elsewhere)|${probeFmt(monomorphicInterfaceTrial())}")
    println("concrete (no interface)|${probeFmt(concreteTrial())}")
    println("blackhole=$probeBlackhole")
}
