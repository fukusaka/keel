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
 * Native micro-benchmark for the sequential-scan cost of a *composite* buffer
 * in the upcoming `IoBuf` redesign. Invoked via `--bench=chain-scan`.
 *
 * The `IoBuf` redesign introduces a composite buffer: a logical
 * buffer made of a **chain of fixed-size segments**. The codec scans bytes
 * sequentially (HTTP header parsing scans forward for `\n`). A flat
 * (single contiguous) buffer scans with a plain pointer increment; a chained
 * buffer needs, per byte, a "am I at the current segment's end? if so advance
 * to the next segment" check. This bench MEASURES that chain-walk tax so the
 * composite-buffer design can be decided: keep flat as the common case and use
 * composite only for accumulation, or go composite-everywhere if it is cheap.
 *
 * Six variants, each scanning the same total of 65536 bytes sequentially
 * and summing every byte:
 * - **flat-direct**: 65536 bytes in ONE contiguous region scanned with a
 *   bare indexed loop (no cursor object). The isolation baseline.
 * - **flat**: 65536 bytes in ONE contiguous native region; a [FlatCursor]
 *   does `base[off++]` with no boundary check.
 * - **chain-8x8k**: 8 separate 8192-byte regions, chained; a [ChainCursor]
 *   does a per-byte boundary check before `base[off++]`. The realistic
 *   composite (8 KiB segments).
 * - **chain-64x1k**: 64 separate 1024-byte regions, chained; same
 *   [ChainCursor]. A stress variant with 8x more boundary crossings.
 * - **chain-1seg**: the [ChainCursor] over ONE 65536-byte segment — the
 *   per-byte boundary check still runs but is always false and `base` is
 *   never reassigned. Isolates the per-byte branch + cursor field traffic
 *   from the boundary base reload.
 * - **seggran-8x8k**: the same 8x8192B chain as chain-8x8k scanned at
 *   SEGMENT GRANULARITY (outer loop over segments, tight flat inner loop
 *   per segment). The scan strategy the composite buffer must expose.
 *
 * Methodology:
 * - One op = one full 65536-byte sequential scan (65536 `nextByte` calls
 *   summed).
 * - 2 s warmup, 5 s trial x 3 runs per variant, median reported.
 * - A `blackhole` accumulator defeats dead-code elimination.
 *
 * Printed format (tsv-ish, one line per variant):
 *   `variant|ns/op|ops/sec|notes`
 */
@OptIn(ExperimentalForeignApi::class)
fun runChainScanBench() {
    println("Chain scan micro-bench (Kotlin/Native)")
    println("======================================")
    println("variant|ns/op|ops/sec|notes")

    // flat: one contiguous 65536-byte region.
    val flatRegion = nativeHeap.allocArray<ByteVar>(TOTAL_BYTES)
    fillPattern(flatRegion, TOTAL_BYTES, 0)

    // chain-8x8k: 8 regions of 8192 bytes.
    val regions8 = allocChain(SEGMENTS_8, SEGMENT_LEN_8K)
    // chain-64x1k: 64 regions of 1024 bytes.
    val regions64 = allocChain(SEGMENTS_64, SEGMENT_LEN_1K)

    try {
        // flat-direct: one contiguous region scanned with a direct indexed
        // loop — no cursor object, no method call. The baseline that
        // isolates the cursor-abstraction cost (vs `flat`) and the
        // segment-granularity cost (vs `seggran-8x8k`).
        benchVariant("flat-direct (1 region, direct loop)", "no cursor, bare indexed loop") {
            scanFlatDirect(flatRegion)
        }
        benchVariant("flat (1 contiguous region)", "flat cursor, no boundary check") {
            scanFlat(FlatCursor(flatRegion))
        }
        benchVariant("chain-8x8k (8 x 8192B segments)", "chain cursor, 7 crossings/scan") {
            scanChain(ChainCursor(regions8, SEGMENT_LEN_8K))
        }
        benchVariant("chain-64x1k (64 x 1024B segments)", "chain cursor, 63 crossings/scan") {
            scanChain(ChainCursor(regions64, SEGMENT_LEN_1K))
        }
        // chain-1seg: the chain cursor over ONE 65536-byte segment — the
        // per-byte boundary check runs every byte but is always false and
        // `base` is never reassigned. Isolates the per-byte branch + cursor
        // field traffic from the segment-boundary base reload.
        benchVariant("chain-1seg (chain cursor, 1 segment)", "per-byte branch, base never reassigned") {
            scanChain(ChainCursor(arrayOf(flatRegion), TOTAL_BYTES))
        }
        // seggran-8x8k: the same 8 x 8192B chain as chain-8x8k, scanned at
        // SEGMENT GRANULARITY — an outer loop over segments, each scanned
        // with a tight flat inner loop over a hoistable local base. No
        // per-byte boundary check; boundary work is O(segment count).
        benchVariant("seggran-8x8k (8 x 8192B, segment-granularity scan)", "flat inner loop per segment") {
            scanSegmentGranular(regions8, SEGMENT_LEN_8K)
        }
    } finally {
        nativeHeap.free(flatRegion.rawValue)
        for (r in regions8) nativeHeap.free(r.rawValue)
        for (r in regions64) nativeHeap.free(r.rawValue)
    }

    println()
    println("blackhole=$blackhole") // prevent dead-code elimination
}

// -------------------------------------------------------------------------
// Setup helpers
// -------------------------------------------------------------------------

/** Fills [len] bytes of [region] with a non-trivial, scan-position-dependent pattern. */
@OptIn(ExperimentalForeignApi::class)
private fun fillPattern(region: CPointer<ByteVar>, len: Int, base: Int) {
    for (i in 0 until len) region[i] = ((base + i) and 0x7F).toByte()
}

/** Allocates [count] native regions of [segmentLen] bytes each, filled so the global scan sum is data-dependent. */
@OptIn(ExperimentalForeignApi::class)
private fun allocChain(count: Int, segmentLen: Int): Array<CPointer<ByteVar>> =
    Array(count) { idx ->
        val region = nativeHeap.allocArray<ByteVar>(segmentLen)
        fillPattern(region, segmentLen, idx * segmentLen)
        region
    }

// -------------------------------------------------------------------------
// Cursors — flat vs chain. Only the cursor logic differs between variants.
// -------------------------------------------------------------------------

/**
 * Models a flat single-segment buffer: a plain cursor over one contiguous
 * region. [nextByte] is a bare `base[off++]` with no boundary check.
 */
@OptIn(ExperimentalForeignApi::class)
private class FlatCursor(private val base: CPointer<ByteVar>) {
    private var off: Int = 0

    fun nextByte(): Byte = base[off++]
}

/**
 * Models a composite buffer cursor: a chain of fixed-size segments. [nextByte]
 * does a per-byte boundary check ("at the current segment's end? advance to
 * the next segment") before the `base[off++]` index. This is the chain-walk
 * tax the bench measures.
 */
@OptIn(ExperimentalForeignApi::class)
private class ChainCursor(
    private val segments: Array<CPointer<ByteVar>>,
    private val segmentLen: Int,
) {
    private var regionIndex: Int = 0
    private var base: CPointer<ByteVar> = segments[0]
    private var off: Int = 0

    fun nextByte(): Byte {
        if (off == segmentLen) {
            regionIndex++
            base = segments[regionIndex]
            off = 0
        }
        return base[off++]
    }
}

// -------------------------------------------------------------------------
// Scan loops — one call = one full TOTAL_BYTES-byte scan (one op).
// -------------------------------------------------------------------------

private fun scanFlat(cursor: FlatCursor) {
    var sum = 0L
    for (i in 0 until TOTAL_BYTES) sum += cursor.nextByte().toLong()
    blackhole += sum
}

/**
 * Direct flat scan of one contiguous region — a bare indexed loop with no
 * cursor object and no method call. Isolates the cost of the [FlatCursor]
 * abstraction (vs [scanFlat]) and of segment granularity (vs
 * [scanSegmentGranular], both being cursor-free direct loops).
 */
@OptIn(ExperimentalForeignApi::class)
private fun scanFlatDirect(region: CPointer<ByteVar>) {
    var sum = 0L
    for (i in 0 until TOTAL_BYTES) sum += region[i].toLong()
    blackhole += sum
}

private fun scanChain(cursor: ChainCursor) {
    var sum = 0L
    for (i in 0 until TOTAL_BYTES) sum += cursor.nextByte().toLong()
    blackhole += sum
}

/**
 * Segment-granularity scan: an outer loop over the segments, each scanned
 * with a tight flat inner loop over a hoistable local `base`. No per-byte
 * boundary check — the boundary work is O([segments].size). This is the
 * scan strategy the composite buffer must expose for the codec.
 */
@OptIn(ExperimentalForeignApi::class)
private fun scanSegmentGranular(segments: Array<CPointer<ByteVar>>, segmentLen: Int) {
    var sum = 0L
    for (seg in segments) {
        for (i in 0 until segmentLen) sum += seg[i].toLong()
    }
    blackhole += sum
}

// -------------------------------------------------------------------------
// Harness
// -------------------------------------------------------------------------

private const val TOTAL_BYTES = 65536
private const val SEGMENT_LEN_8K = 8192
private const val SEGMENTS_8 = TOTAL_BYTES / SEGMENT_LEN_8K
private const val SEGMENT_LEN_1K = 1024
private const val SEGMENTS_64 = TOTAL_BYTES / SEGMENT_LEN_1K

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
    report(variant, nsPerOp, "$notes (1 op = ${TOTAL_BYTES}B scan)")
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
