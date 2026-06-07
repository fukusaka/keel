package io.github.fukusaka.keel.benchmark

import kotlin.concurrent.AtomicReference
import kotlin.random.Random
import kotlin.time.DurationUnit
import kotlin.time.TimeSource
import kotlin.time.measureTime

/**
 * Variant bench for the `pools` size-class lookup used by `SlabAllocator`
 * (Native) / `PooledDirectAllocator` (JVM) on the allocation hot path.
 *
 * Motivation: Phase 1 of the chunk-based allocator roadmap unifies the two
 * allocators into commonMain. The `pools` map (`Int size -> Pool`) is the only
 * concurrency-bearing structure that must be re-homed. Its access pattern is
 * **read-mostly** (lookups on every `allocate` / `returnToPool`; writes only at
 * `registerPoolSize`, i.e. startup / TLS setup) with **tiny cardinality** (1
 * default 8 KiB class, +1-2 for TLS). On the hot path the lookup is always a
 * **single-threaded read** (the owning EventLoop thread). This bench quantifies
 * the per-lookup overhead each strategy imposes on that read path.
 *
 * Strategies:
 * - **PlainHashMap**: `HashMap<Int, V>`, no lock. Lower bound for a hashed
 *   lookup including `Int` autoboxing of the key.
 * - **SpinLockHashMap**: `HashMap<Int, V>` guarded by an `AtomicReference<Boolean>`
 *   spin lock — the current `SlabAllocator` shape (thread-safe for the
 *   NWConnection cross-thread release).
 * - **CowAtomicRef**: `AtomicReference<Map<Int, V>>` — copy-on-write. Read is a
 *   single volatile load + hashed lookup, no lock; writes (rare) swap a fresh
 *   immutable map. Lock-free reads like a concurrent map but trivially common.
 * - **IntArrayScan**: parallel `IntArray` (sizes) + `Array<V?>` (pools) behind a
 *   volatile ref, linear scan. No boxing, no hashing; at cardinality <= 4 a
 *   branchy scan may beat any hashed map.
 *
 * Cardinality {1, 2, 4} brackets the realistic class count.
 *
 * Invocation: `benchmark.kexe --bench=poolmap-variants`
 */
fun runPoolMapVariantBench() {
    println("Pool size-class lookup variant bench (Kotlin/Native)")
    println("====================================================")
    println("Read-only single-thread lookup (hot-path shape); ns/op = ns per single lookup")
    println("variant|cardinality|ns/op|ops/sec")

    val cardinalities = intArrayOf(1, 2, 4)
    val labels = listOf("PlainHashMap", "SpinLockHashMap", "CowAtomicRef", "IntArrayScan")

    for (card in cardinalities) {
        val keys = sizeClasses(card)
        for (label in labels) {
            val ns = execTrial(poolMapFactory(label), keys) / OPS_PER_CYCLE
            val ops = 1_000_000_000.0 / ns
            println("$label|$card|${format1(ns)}|${formatE(ops)}")
        }
    }
    println()
    println("blackhole=$poolMapBlackhole")
}

/**
 * Realistic size-class keys. Card 1 = 8 KiB only. Card 2 = +16 KiB (TLS record).
 * Card 4 = +1 KiB / 4 KiB (SSE / chunked small frames). All are the exact
 * `Int` capacities the allocator registers, not synthetic spreads.
 */
private fun sizeClasses(card: Int): IntArray = when (card) {
    1 -> intArrayOf(8192)
    2 -> intArrayOf(8192, 16384)
    4 -> intArrayOf(1024, 4096, 8192, 16384)
    else -> error("unsupported cardinality $card")
}

@kotlin.concurrent.Volatile
private var poolMapBlackhole: Long = 0

private const val WARMUP_MS = 1_500L
private const val TRIAL_MS = 3_000L
private const val OPS_PER_CYCLE = 10

/** Warmup + 3-trial median. Returns ns per cycle ([OPS_PER_CYCLE] lookups). */
private fun execTrial(factory: () -> PoolMapAdapter, keys: IntArray): Double {
    val map = factory()
    for (k in keys) map.put(k, "v")

    val warmupMark = TimeSource.Monotonic.markNow()
    var iters = 0L
    while (warmupMark.elapsedNow().toDouble(DurationUnit.MILLISECONDS) < WARMUP_MS) {
        repeat(1_000) {
            runLookups(map, keys)
            iters++
        }
    }
    poolMapBlackhole += iters

    val nsPerCycle = DoubleArray(3)
    for (t in 0 until 3) {
        var trialIters = 0L
        val elapsed = measureTime {
            val deadline = TimeSource.Monotonic.markNow()
            while (deadline.elapsedNow().toDouble(DurationUnit.MILLISECONDS) < TRIAL_MS) {
                repeat(10_000) {
                    runLookups(map, keys)
                    trialIters++
                }
            }
        }
        nsPerCycle[t] = elapsed.toDouble(DurationUnit.NANOSECONDS) / trialIters.toDouble()
    }
    nsPerCycle.sort()
    return nsPerCycle[1]
}

/** 1 cycle = [OPS_PER_CYCLE] lookups against the registered key set (always hits). */
private fun runLookups(map: PoolMapAdapter, keys: IntArray) {
    val size = keys.size
    var sum = 0L
    for (i in 0 until OPS_PER_CYCLE) {
        val k = keys[Random.nextInt(size)]
        val v = map.get(k)
        if (v != null) sum += k.toLong()
    }
    poolMapBlackhole += sum
}

private fun poolMapFactory(label: String): () -> PoolMapAdapter = when (label) {
    "PlainHashMap" -> { -> PlainHashMapAdapter() }
    "SpinLockHashMap" -> { -> SpinLockHashMapAdapter() }
    "CowAtomicRef" -> { -> CowAtomicRefAdapter() }
    "IntArrayScan" -> { -> IntArrayScanAdapter() }
    else -> error("unknown $label")
}

private fun format1(v: Double): String {
    val rounded = kotlin.math.round(v * 100.0) / 100.0
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

// -------------------------------------------------------------------------
// Adapters (one virtual call per op — identical overhead across variants)
// -------------------------------------------------------------------------

private interface PoolMapAdapter {
    fun put(key: Int, value: String)
    fun get(key: Int): String?
}

/** Plain HashMap, no lock. Includes Int autoboxing on lookup. */
private class PlainHashMapAdapter : PoolMapAdapter {
    private val map = HashMap<Int, String>()
    override fun put(key: Int, value: String) { map[key] = value }
    override fun get(key: Int): String? = map[key]
}

/** HashMap + spin lock — current SlabAllocator shape. */
private class SpinLockHashMapAdapter : PoolMapAdapter {
    private val map = HashMap<Int, String>()
    private val lock = AtomicReference(false)

    private inline fun <T> withSpinLock(block: () -> T): T {
        while (!lock.compareAndSet(false, true)) { /* spin */ }
        try {
            return block()
        } finally {
            lock.value = false
        }
    }

    override fun put(key: Int, value: String) = withSpinLock { map[key] = value; Unit }
    override fun get(key: Int): String? = withSpinLock { map[key] }
}

/** Copy-on-write: volatile read of an immutable map, lock-free lookup. */
private class CowAtomicRefAdapter : PoolMapAdapter {
    private val ref = AtomicReference<Map<Int, String>>(emptyMap())

    override fun put(key: Int, value: String) {
        while (true) {
            val cur = ref.value
            val next = HashMap(cur).apply { this[key] = value }
            if (ref.compareAndSet(cur, next)) return
        }
    }

    override fun get(key: Int): String? = ref.value[key]
}

/** Parallel int/value arrays behind a volatile ref, linear scan. No boxing. */
private class IntArrayScanAdapter : PoolMapAdapter {
    @kotlin.concurrent.Volatile
    private var table: Table = Table(IntArray(0), arrayOfNulls(0))

    private class Table(val sizes: IntArray, val pools: Array<String?>)

    override fun put(key: Int, value: String) {
        val cur = table
        val idx = cur.sizes.indexOf(key)
        if (idx >= 0) {
            cur.pools[idx] = value
            return
        }
        val n = cur.sizes.size
        val sizes = cur.sizes.copyOf(n + 1)
        val pools = cur.pools.copyOf(n + 1)
        sizes[n] = key
        pools[n] = value
        table = Table(sizes, pools)
    }

    override fun get(key: Int): String? {
        val cur = table
        val sizes = cur.sizes
        for (i in sizes.indices) {
            if (sizes[i] == key) return cur.pools[i]
        }
        return null
    }
}
