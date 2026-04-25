package io.github.fukusaka.keel.benchmark

import kotlin.random.Random
import kotlin.time.DurationUnit
import kotlin.time.TimeSource
import kotlin.time.measureTime

/**
 * Native micro-benchmarks for Kotlin/Native collection allocation and
 * primitive-boxing costs. Invoked via `--bench=collection-alloc`.
 *
 * Measures representative patterns used by `EpollEventLoop` / `KqueueEventLoop` /
 * `AbstractIoTransport` hot paths, so the numbers translate directly to design
 * decisions (ArrayDeque vs ArrayList for `pendingWrites`, primitive-array vs
 * `list.map { }` for flushGather regions, custom `LongObjectMap` vs `HashMap<Long, V>`
 * for fd registrations).
 *
 * Methodology:
 * - 2 s warmup, 5 s trial × 3 runs per variant, median reported.
 * - A `blackhole` accumulator defeats dead-code elimination.
 * - Times reported per operation (ns/op). The shape of allocations
 *   (whether a given variant allocates on the hot path) follows from
 *   reading the Kotlin stdlib sources rather than a profiler — there
 *   is no heap profiler on Kotlin/Native.
 *
 * Printed format (tsv-ish, one line per variant):
 *   `variant|ns/op|ops/sec|notes`
 */
fun runCollectionAllocBench() {
    println("Collection alloc micro-bench (Kotlin/Native)")
    println("=============================================")
    println("variant|ns/op|ops/sec|notes")

    // pendingWrites pattern: add / removeFirst at small sizes.
    benchArrayListAddFirst(size = 1)
    benchArrayListAddFirst(size = 4)
    benchArrayListAddFirst(size = 16)
    benchArrayDequeAddFirst(size = 1)
    benchArrayDequeAddFirst(size = 4)
    benchArrayDequeAddFirst(size = 16)

    // drainBatch pattern: clear + add × N + iterate.
    benchDrainBatchArrayList(size = 8)
    benchDrainBatchArrayDeque(size = 8)

    // flushGather pattern: map { NativeRegion } vs parallel primitive arrays.
    benchFlushRegionsMap(size = 4)
    benchFlushRegionsMap(size = 16)
    benchFlushRegionsPrimitive(size = 4)
    benchFlushRegionsPrimitive(size = 16)

    // Registration map pattern: put/remove with Long keys.
    benchHashMapLongKey(size = 64)
    benchHashMapLongKey(size = 1024)
    benchLongObjectMap(size = 64)
    benchLongObjectMap(size = 1024)
    benchKeelLongObjectMap(size = 64)
    benchKeelLongObjectMap(size = 1024)

    // Iterator vs indexed access on ArrayList.
    benchArrayListForEachIterator(size = 64)
    benchArrayListIndexedFor(size = 64)

    println()
    println("blackhole=$blackhole") // prevent dead-code elimination
}

/**
 * Simulates `pendingWrites.add(0, remainder)` → `pendingWrites.removeFirst()`
 * cycle (EAGAIN partial write retry path in `AbstractIoTransport`). ArrayList
 * `add(0, ...)` performs an O(n) `copyInto` shift; measure for size=1, 4, 16.
 */
private fun benchArrayListAddFirst(size: Int) {
    val scratch = ArrayList<Int>(size + 4)
    // pre-fill
    for (i in 0 until size) scratch.add(i)

    // Warmup.
    val mark = TimeSource.Monotonic.markNow()
    warmup {
        scratch.add(0, blackhole.toInt())
        blackhole = (blackhole + scratch.removeAt(0)).toLong()
    }

    // Trial: median of 3 runs.
    val nsPerOp = trialMedian(warmupElapsed = mark.elapsedNow().toDouble(DurationUnit.SECONDS)) {
        scratch.add(0, blackhole.toInt())
        blackhole = (blackhole + scratch.removeAt(0)).toLong()
    }
    report("ArrayList.add(0,e)+removeAt(0) size=$size", nsPerOp, "O(n) shift via copyInto")
}

/**
 * Simulates the same cycle but using `ArrayDeque.addFirst(e)` /
 * `ArrayDeque.removeFirst()` — both O(1) via head pointer on a circular buffer.
 */
private fun benchArrayDequeAddFirst(size: Int) {
    val scratch = ArrayDeque<Int>(size + 4)
    for (i in 0 until size) scratch.addLast(i)

    val mark = TimeSource.Monotonic.markNow()
    warmup {
        scratch.addFirst(blackhole.toInt())
        blackhole = (blackhole + scratch.removeFirst()).toLong()
    }
    val nsPerOp = trialMedian(warmupElapsed = mark.elapsedNow().toDouble(DurationUnit.SECONDS)) {
        scratch.addFirst(blackhole.toInt())
        blackhole = (blackhole + scratch.removeFirst()).toLong()
    }
    report("ArrayDeque.addFirst(e)+removeFirst() size=$size", nsPerOp, "O(1) head pointer")
}

/**
 * drainBatch pattern: reset, add × N, iterate. Currently implemented via
 * `mutableListOf<Runnable>()` + reuse; ArrayDeque is equivalent capacity-wise.
 */
private fun benchDrainBatchArrayList(size: Int) {
    val scratch = ArrayList<Int>(size + 4)
    val source = IntArray(size) { it }

    val mark = TimeSource.Monotonic.markNow()
    warmup { drainBatchCycleList(scratch, source) }
    val nsPerOp = trialMedian(warmupElapsed = mark.elapsedNow().toDouble(DurationUnit.SECONDS)) {
        drainBatchCycleList(scratch, source)
    }
    report("drainBatch via ArrayList size=$size", nsPerOp, "clear+addAll+iterate (field reuse)")
}

private fun benchDrainBatchArrayDeque(size: Int) {
    val scratch = ArrayDeque<Int>(size + 4)
    val source = IntArray(size) { it }

    val mark = TimeSource.Monotonic.markNow()
    warmup { drainBatchCycleDeque(scratch, source) }
    val nsPerOp = trialMedian(warmupElapsed = mark.elapsedNow().toDouble(DurationUnit.SECONDS)) {
        drainBatchCycleDeque(scratch, source)
    }
    report("drainBatch via ArrayDeque size=$size", nsPerOp, "clear+addAll+iterate")
}

private fun drainBatchCycleList(scratch: ArrayList<Int>, source: IntArray) {
    scratch.clear()
    for (x in source) scratch.add(x)
    var s = 0L
    for (x in scratch) s += x.toLong()
    blackhole += s
}

private fun drainBatchCycleDeque(scratch: ArrayDeque<Int>, source: IntArray) {
    scratch.clear()
    for (x in source) scratch.addLast(x)
    var s = 0L
    for (x in scratch) s += x.toLong()
    blackhole += s
}

/** Simulates `flushGather` via `pendingWrites.map { NativeRegion(...) }`. */
private data class BenchRegion(val ptr: Long, val len: Int)

private fun benchFlushRegionsMap(size: Int) {
    val pending = List(size) { BenchRegion(ptr = it.toLong() * 16, len = 64) }

    val mark = TimeSource.Monotonic.markNow()
    warmup {
        val regions = pending.map { BenchRegion(it.ptr + 1, it.len) }
        var s = 0L
        for (r in regions) s += r.ptr + r.len
        blackhole += s
    }
    val nsPerOp = trialMedian(warmupElapsed = mark.elapsedNow().toDouble(DurationUnit.SECONDS)) {
        val regions = pending.map { BenchRegion(it.ptr + 1, it.len) }
        var s = 0L
        for (r in regions) s += r.ptr + r.len
        blackhole += s
    }
    report("flushGather via .map{Region} size=$size", nsPerOp, "ArrayList + Iterator + N Region allocs")
}

/** Simulates field-cached LongArray(ptr) + IntArray(len) parallel pattern. */
private fun benchFlushRegionsPrimitive(size: Int) {
    val ptrs = LongArray(size) { it.toLong() * 16 }
    val lens = IntArray(size) { 64 }
    val ptrsOut = LongArray(size) // field-cached scratch
    val lensOut = IntArray(size)

    val mark = TimeSource.Monotonic.markNow()
    warmup {
        for (i in 0 until size) {
            ptrsOut[i] = ptrs[i] + 1
            lensOut[i] = lens[i]
        }
        var s = 0L
        for (i in 0 until size) s += ptrsOut[i] + lensOut[i]
        blackhole += s
    }
    val nsPerOp = trialMedian(warmupElapsed = mark.elapsedNow().toDouble(DurationUnit.SECONDS)) {
        for (i in 0 until size) {
            ptrsOut[i] = ptrs[i] + 1
            lensOut[i] = lens[i]
        }
        var s = 0L
        for (i in 0 until size) s += ptrsOut[i] + lensOut[i]
        blackhole += s
    }
    report("flushGather via LongArray+IntArray size=$size", nsPerOp, "primitive array, 0 alloc")
}

/** `HashMap<Long, V>.put` / `remove` path used by `registrations` / `fdEvents`. */
private fun benchHashMapLongKey(size: Int) {
    val map = HashMap<Long, String>(size * 2)
    val keys = LongArray(size) { (it.toLong() * 1_000_003) }
    // pre-populate
    for (k in keys) map[k] = "v"

    val mark = TimeSource.Monotonic.markNow()
    warmup {
        val k = keys[Random.nextInt(size)]
        map.remove(k)
        map[k] = "v"
        blackhole += k
    }
    val nsPerOp = trialMedian(warmupElapsed = mark.elapsedNow().toDouble(DurationUnit.SECONDS)) {
        val k = keys[Random.nextInt(size)]
        map.remove(k)
        map[k] = "v"
        blackhole += k
    }
    report("HashMap<Long,V> put/remove size=$size", nsPerOp, "Long boxing per call")
}

/**
 * Minimal open-addressing primitive-keyed map (Long → V). No boxing, no
 * node allocation. Mirrors what a `LongObjectMap<V>` replacement would
 * look like in keel for `registrations` / `fdEvents`.
 */
private class SimpleLongObjectMap<V>(capacityHint: Int) {
    private val cap: Int = nextPowerOfTwo(capacityHint.coerceAtLeast(8) * 2)
    private val keys: LongArray = LongArray(cap)
    private val used: BooleanArray = BooleanArray(cap)
    private val values: Array<Any?> = arrayOfNulls(cap)

    fun put(key: Long, value: V) {
        var i = (key.hashCode() and (cap - 1))
        while (used[i] && keys[i] != key) i = (i + 1) and (cap - 1)
        keys[i] = key
        used[i] = true
        values[i] = value
    }

    fun remove(key: Long) {
        var i = (key.hashCode() and (cap - 1))
        while (used[i] && keys[i] != key) i = (i + 1) and (cap - 1)
        if (used[i]) {
            used[i] = false
            values[i] = null
        }
    }

    private fun nextPowerOfTwo(v: Int): Int {
        var x = 1
        while (x < v) x = x shl 1
        return x
    }
}

private fun benchLongObjectMap(size: Int) {
    val map = SimpleLongObjectMap<String>(size * 2)
    val keys = LongArray(size) { (it.toLong() * 1_000_003) }
    for (k in keys) map.put(k, "v")

    val mark = TimeSource.Monotonic.markNow()
    warmup {
        val k = keys[Random.nextInt(size)]
        map.remove(k)
        map.put(k, "v")
        blackhole += k
    }
    val nsPerOp = trialMedian(warmupElapsed = mark.elapsedNow().toDouble(DurationUnit.SECONDS)) {
        val k = keys[Random.nextInt(size)]
        map.remove(k)
        map.put(k, "v")
        blackhole += k
    }
    report("SimpleLongObjectMap put/remove size=$size", nsPerOp, "open-addressing, 0 Long box")
}

/**
 * The actual production-grade `LongObjectMap` from `keel-io`. Adds tombstone
 * tracking, Fibonacci hashing and resize-on-load over [SimpleLongObjectMap].
 * Confirms the `LongObjectMap` adoption in `KqueueEventLoop` / `EpollEventLoop`
 * keeps the speedup that the simpler bench impl reported.
 */
private fun benchKeelLongObjectMap(size: Int) {
    val map = io.github.fukusaka.keel.collections.LongObjectMap<String>(initialCapacity = size * 2)
    val keys = LongArray(size) { (it.toLong() * 1_000_003) }
    for (k in keys) map.put(k, "v")

    val mark = TimeSource.Monotonic.markNow()
    warmup {
        val k = keys[Random.nextInt(size)]
        map.remove(k)
        map.put(k, "v")
        blackhole += k
    }
    val nsPerOp = trialMedian(warmupElapsed = mark.elapsedNow().toDouble(DurationUnit.SECONDS)) {
        val k = keys[Random.nextInt(size)]
        map.remove(k)
        map.put(k, "v")
        blackhole += k
    }
    report("keel LongObjectMap put/remove size=$size", nsPerOp, "tombstone + Fibonacci hash + resize-ready")
}

/** `for (x in list)` — allocates iterator per call on a generic List. */
private fun benchArrayListForEachIterator(size: Int) {
    val list = ArrayList<Int>(size).apply { repeat(size) { add(it) } }
    val mark = TimeSource.Monotonic.markNow()
    warmup {
        var s = 0L
        for (x in list) s += x.toLong()
        blackhole += s
    }
    val nsPerOp = trialMedian(warmupElapsed = mark.elapsedNow().toDouble(DurationUnit.SECONDS)) {
        var s = 0L
        for (x in list) s += x.toLong()
        blackhole += s
    }
    report("ArrayList for-in (Iterator) size=$size", nsPerOp, "iterator alloc per call")
}

private fun benchArrayListIndexedFor(size: Int) {
    val list = ArrayList<Int>(size).apply { repeat(size) { add(it) } }
    val mark = TimeSource.Monotonic.markNow()
    warmup {
        var s = 0L
        for (i in 0 until list.size) s += list[i].toLong()
        blackhole += s
    }
    val nsPerOp = trialMedian(warmupElapsed = mark.elapsedNow().toDouble(DurationUnit.SECONDS)) {
        var s = 0L
        for (i in 0 until list.size) s += list[i].toLong()
        blackhole += s
    }
    report("ArrayList indexed for-loop size=$size", nsPerOp, "no iterator")
}

// -------------------------------------------------------------------------
// Harness
// -------------------------------------------------------------------------

/**
 * Sink accumulator — exported at end of run to defeat dead-code elimination
 * that would otherwise let the optimiser erase most of the benchmark bodies.
 */
@kotlin.concurrent.Volatile
private var blackhole: Long = 0

private const val WARMUP_MS = 2_000L
private const val TRIAL_MS = 5_000L

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
 * Instead of counting time per op (expensive syscall overhead), we count
 * iterations achieved in the trial window and derive ns/op.
 */
private inline fun trialMedian(warmupElapsed: Double, body: () -> Unit): Double {
    val nsPerRun = DoubleArray(3)
    for (i in 0 until 3) {
        var iters = 0L
        val elapsed = measureTime {
            val deadline = TimeSource.Monotonic.markNow()
            while (deadline.elapsedNow().toDouble(DurationUnit.MILLISECONDS) < TRIAL_MS) {
                repeat(10_000) {
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
