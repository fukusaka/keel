package io.github.fukusaka.keel.buf

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Ignore
import kotlin.test.Test

/**
 * JVM counterpart of the Native `--bench=poolmap-variants` micro-bench.
 *
 * Measures per-lookup ns/op for the `pools` size-class map used by
 * [PooledDirectAllocator] on the allocation hot path. The access pattern is
 * read-mostly (lookups on every allocate/return; writes only at
 * `hintSizeClass`) with tiny cardinality. On the hot path the lookup is a
 * single-threaded read by the owning EventLoop thread.
 *
 * Adds the JVM-only [ConcurrentHashMap] candidate (the current
 * [PooledDirectAllocator] choice) which has no Native equivalent.
 *
 * Strategies: PlainHashMap / SpinLockHashMap / ConcurrentHashMap /
 * CowAtomicRef / IntArrayScan. Cardinality {1, 2, 4}.
 *
 * Not a unit test — runs as `@Test` for the normal `jvmTest` task; inspect
 * stdout. Does not assert.
 */
// @Ignore: one-time measurement (no functional assertion) — a decision
// aid that caught no regression, so it is not run in the gate / CI; kept
// for re-verification. The verified content + conclusion is the class
// KDoc above.
// Re-run: remove @Ignore, then
//   ./gradlew :keel-io:jvmTest --tests "*PoolMapVariantBenchmark"
@Ignore
class PoolMapVariantBenchmark {

    @Test
    fun poolMapVariants() {
        println("Pool size-class lookup variant bench (JVM)")
        println("==========================================")
        println("Read-only single-thread lookup (hot-path shape); ns/op = ns per single lookup")
        println("variant|cardinality|ns/op")

        val cardinalities = intArrayOf(1, 2, 4)
        val labels = listOf("PlainHashMap", "SpinLockHashMap", "ConcurrentHashMap", "CowAtomicRef", "IntArrayScan")

        for (card in cardinalities) {
            val keys = sizeClasses(card)
            for (label in labels) {
                val ns = execTrial(factory(label), keys)
                println("$label|$card|${"%.2f".format(ns)}")
            }
        }
        println("blackhole=$blackhole")
    }

    private fun sizeClasses(card: Int): IntArray = when (card) {
        1 -> intArrayOf(8192)
        2 -> intArrayOf(8192, 16384)
        4 -> intArrayOf(1024, 4096, 8192, 16384)
        else -> error("unsupported cardinality $card")
    }

    /** Warmup + 3-trial median. Returns ns per single lookup. */
    private fun execTrial(map: PoolMapAdapter, keys: IntArray): Double {
        for (k in keys) map.put(k, "v")

        // Warmup to trigger C2.
        repeat(WARMUP_CYCLES) { runLookups(map, keys) }

        val nsPerOp = DoubleArray(3)
        for (t in 0 until 3) {
            val start = System.nanoTime()
            repeat(TRIAL_CYCLES) { runLookups(map, keys) }
            val elapsed = System.nanoTime() - start
            nsPerOp[t] = elapsed.toDouble() / (TRIAL_CYCLES.toDouble() * OPS_PER_CYCLE)
        }
        nsPerOp.sort()
        return nsPerOp[1]
    }

    private fun runLookups(map: PoolMapAdapter, keys: IntArray) {
        val size = keys.size
        var sum = 0L
        for (i in 0 until OPS_PER_CYCLE) {
            val k = keys[i % size]
            val v = map.get(k)
            if (v != null) sum += k.toLong()
        }
        blackhole += sum
    }

    private fun factory(label: String): PoolMapAdapter = when (label) {
        "PlainHashMap" -> PlainHashMapAdapter()
        "SpinLockHashMap" -> SpinLockHashMapAdapter()
        "ConcurrentHashMap" -> ConcurrentHashMapAdapter()
        "CowAtomicRef" -> CowAtomicRefAdapter()
        "IntArrayScan" -> IntArrayScanAdapter()
        else -> error("unknown $label")
    }

    private interface PoolMapAdapter {
        fun put(key: Int, value: String)
        fun get(key: Int): String?
    }

    private class PlainHashMapAdapter : PoolMapAdapter {
        private val map = HashMap<Int, String>()
        override fun put(key: Int, value: String) { map[key] = value }
        override fun get(key: Int): String? = map[key]
    }

    private class SpinLockHashMapAdapter : PoolMapAdapter {
        private val map = HashMap<Int, String>()
        private val lock = AtomicBoolean(false)
        private inline fun <T> withSpinLock(block: () -> T): T {
            while (!lock.compareAndSet(false, true)) { /* spin */ }
            try {
                return block()
            } finally {
                lock.set(false)
            }
        }
        override fun put(key: Int, value: String) = withSpinLock {
            map[key] = value
            Unit
        }
        override fun get(key: Int): String? = withSpinLock { map[key] }
    }

    private class ConcurrentHashMapAdapter : PoolMapAdapter {
        private val map = ConcurrentHashMap<Int, String>()
        override fun put(key: Int, value: String) { map[key] = value }
        override fun get(key: Int): String? = map[key]
    }

    private class CowAtomicRefAdapter : PoolMapAdapter {
        private val ref = AtomicReference<Map<Int, String>>(emptyMap())
        override fun put(key: Int, value: String) {
            while (true) {
                val cur = ref.get()
                val next = HashMap(cur).apply { this[key] = value }
                if (ref.compareAndSet(cur, next)) return
            }
        }
        override fun get(key: Int): String? = ref.get()[key]
    }

    private class IntArrayScanAdapter : PoolMapAdapter {
        @Volatile
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

    private companion object {
        @Volatile
        @JvmStatic
        var blackhole: Long = 0
        const val WARMUP_CYCLES = 2_000_000
        const val TRIAL_CYCLES = 5_000_000
        const val OPS_PER_CYCLE = 10
    }
}
