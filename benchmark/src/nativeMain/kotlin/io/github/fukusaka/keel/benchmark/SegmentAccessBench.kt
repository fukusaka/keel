package io.github.fukusaka.keel.benchmark

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.set
import kotlin.time.DurationUnit
import kotlin.time.TimeSource
import kotlin.time.measureTime

/**
 * Native micro-benchmark for per-byte buffer access paths in the upcoming
 * `IoBuf` redesign. Invoked via `--bench=segment-access`.
 *
 * In the redesign an `IoBuf` becomes a *view* over a `Segment` (a fixed-size
 * raw-memory holder). The open design question is whether the view should
 * **cache the segment's raw base pointer in its own field** so that per-byte
 * access is `cachedBase[index]` — avoiding the `view.segment.ptr[index]`
 * double indirection. The Kotlin/Native optimiser *might* already hoist the
 * loop-invariant `segment.ptr` load, making an explicit cache redundant; or it
 * *might* not, making the cache essential. This bench measures it.
 *
 * Three structurally identical classes, differing only in the access path:
 * - **direct**: holds the `CPointer` directly (models today's `NativeIoBuf`).
 * - **cached-base**: a view that copies `segment.ptr` into its own field in
 *   the constructor (models the Phase 1 design).
 * - **indirect**: a view that dereferences `segment.ptr` on every access
 *   (models a naive view with no caching).
 *
 * Expected reading: `direct` ≈ `cached-base`. `indirect` is the question —
 * equal (K/N hoisted the invariant `segment.ptr`) or slower (caching needed).
 *
 * Methodology:
 * - All variants read from one 8192-byte `nativeHeap` region.
 * - One op = one full 8192-byte scan (8192 `getByte` calls summed).
 * - 2 s warmup, 5 s trial × 3 runs per variant, median reported.
 * - A `blackhole` accumulator defeats dead-code elimination.
 *
 * Printed format (tsv-ish, one line per variant):
 *   `variant|ns/op|ops/sec|notes`
 */
@OptIn(ExperimentalForeignApi::class)
fun runSegmentAccessBench() {
    println("Segment access micro-bench (Kotlin/Native)")
    println("===========================================")
    println("variant|ns/op|ops/sec|notes")

    val region = nativeHeap.allocArray<ByteVar>(REGION_SIZE)
    // Fill with a non-trivial pattern so the sum is data-dependent.
    for (i in 0 until REGION_SIZE) region[i] = (i and 0x7F).toByte()

    try {
        val direct = DirectBuf(region)
        benchVariant("direct (ptr held in buffer)", "today NativeIoBuf, no view") {
            scanDirect(direct)
        }

        val segment = FakeSegment(region)
        val cached = CachedView(segment)
        benchVariant("cached-base (view caches segment.ptr)", "Phase 1 design") {
            scanCached(cached)
        }

        val indirect = IndirectView(segment)
        benchVariant("indirect (view derefs segment.ptr)", "naive view, double indirection") {
            scanIndirect(indirect)
        }
    } finally {
        nativeHeap.free(region.rawValue)
    }

    println()
    println("blackhole=$blackhole") // prevent dead-code elimination
}

// -------------------------------------------------------------------------
// Variant classes — structurally identical, only the access path differs.
// -------------------------------------------------------------------------

/**
 * Models today's `NativeIoBuf`: the buffer object holds the raw pointer
 * directly. Per-byte access is a single array index off [ptr].
 */
@OptIn(ExperimentalForeignApi::class)
private class DirectBuf(private val ptr: CPointer<ByteVar>) {
    fun getByte(index: Int): Byte = ptr[index]
}

/** Stand-in for the redesign `Segment`: a fixed-size raw-memory holder. */
@OptIn(ExperimentalForeignApi::class)
private class FakeSegment(val ptr: CPointer<ByteVar>)

/**
 * Models the Phase 1 design: a view over a [FakeSegment] that copies the
 * segment base pointer into its own [cachedBase] field in the constructor,
 * so per-byte access skips the `view.segment.ptr` field load.
 */
@OptIn(ExperimentalForeignApi::class)
private class CachedView(segment: FakeSegment) {
    private val cachedBase: CPointer<ByteVar> = segment.ptr

    fun getByte(index: Int): Byte = cachedBase[index]
}

/**
 * Models a naive view with no caching: every access dereferences
 * `segment.ptr` first (a double indirection — view field load, then the
 * pointer index).
 */
@OptIn(ExperimentalForeignApi::class)
private class IndirectView(private val segment: FakeSegment) {
    fun getByte(index: Int): Byte = segment.ptr[index]
}

// -------------------------------------------------------------------------
// Scan loops — one call = one full REGION_SIZE-byte scan (one op).
// -------------------------------------------------------------------------

private fun scanDirect(buf: DirectBuf) {
    var sum = 0L
    for (i in 0 until REGION_SIZE) sum += buf.getByte(i).toLong()
    blackhole += sum
}

private fun scanCached(view: CachedView) {
    var sum = 0L
    for (i in 0 until REGION_SIZE) sum += view.getByte(i).toLong()
    blackhole += sum
}

private fun scanIndirect(view: IndirectView) {
    var sum = 0L
    for (i in 0 until REGION_SIZE) sum += view.getByte(i).toLong()
    blackhole += sum
}

// -------------------------------------------------------------------------
// Harness
// -------------------------------------------------------------------------

private const val REGION_SIZE = 8192

/**
 * Sink accumulator — exported at end of run to defeat dead-code elimination
 * that would otherwise let the optimiser erase the scan loops.
 */
@kotlin.concurrent.Volatile
private var blackhole: Long = 0

private const val WARMUP_MS = 2_000L
private const val TRIAL_MS = 5_000L

/** Runs warmup + 3-trial median for a single variant and reports it. */
private inline fun benchVariant(variant: String, notes: String, body: () -> Unit) {
    warmup(body)
    val nsPerOp = trialMedian(body)
    report(variant, nsPerOp, "$notes (1 op = ${REGION_SIZE}B scan)")
}

private inline fun warmup(body: () -> Unit) {
    val deadline = TimeSource.Monotonic.markNow()
    var iters = 0L
    while (deadline.elapsedNow().toDouble(DurationUnit.MILLISECONDS) < WARMUP_MS) {
        repeat(1_000) {
            body()
            iters++
        }
    }
    blackhole += iters
}

/**
 * Runs [body] for 5 s three times and returns the median ns/op.
 *
 * Counts iterations achieved in the trial window and derives ns/op, avoiding
 * per-op timing syscall overhead.
 */
private inline fun trialMedian(body: () -> Unit): Double {
    val nsPerRun = DoubleArray(3)
    for (i in 0 until 3) {
        var iters = 0L
        val elapsed = measureTime {
            val deadline = TimeSource.Monotonic.markNow()
            while (deadline.elapsedNow().toDouble(DurationUnit.MILLISECONDS) < TRIAL_MS) {
                repeat(1_000) {
                    body()
                    iters++
                }
            }
        }
        val ns = elapsed.toDouble(DurationUnit.NANOSECONDS)
        nsPerRun[i] = ns / iters.toDouble()
    }
    nsPerRun.sort()
    return nsPerRun[1] // median of 3
}

private fun report(variant: String, nsPerOp: Double, notes: String) {
    val opsPerSec = 1_000_000_000.0 / nsPerOp
    println("$variant|${format1(nsPerOp)}|${formatE(opsPerSec)}|$notes")
}

// K/N has limited String.format; format manually.
private fun format1(v: Double): String {
    val rounded = kotlin.math.round(v * 10.0) / 10.0
    return rounded.toString()
}

private fun formatE(v: Double): String {
    if (v <= 0.0) return "0"
    val exp = kotlin.math.floor(kotlin.math.log10(v)).toInt()
    var p = 1.0
    val absExp = if (exp < 0) -exp else exp
    repeat(absExp) { p *= 10.0 }
    val mantissa = if (exp < 0) v * p else v / p
    val mRound = kotlin.math.round(mantissa * 100.0) / 100.0
    return "${mRound}e${exp}"
}
