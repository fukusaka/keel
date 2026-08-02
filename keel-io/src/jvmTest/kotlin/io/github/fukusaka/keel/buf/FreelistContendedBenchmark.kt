package io.github.fukusaka.keel.buf

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.test.Ignore
import kotlin.test.Test

/**
 * JVM counterpart of the native `--bench=freelist-contended`. Drives N java
 * threads doing pop+push roundtrips on a shared freelist, then drains and
 * verifies node-set integrity (ABA detection).
 *
 * Purpose: confirm the freelist ABA result is algorithm-fundamental, not a
 * Native artifact — the plain intrusive Treiber stack (formerly the
 * `PooledDirectAllocator` shape) corrupts under genuine contention on the JVM too,
 * while the spin lock and the versioned-index variant stay correct. This is why
 * concurrent allocate via `asSource` (the engine push read path racing a caller's
 * pull refill on one allocator) forced `PooledDirectAllocator` to switch to a
 * lock-guarded `MutexFreelist`: the Treiber was safe only while every allocator was
 * strictly EL-pinned, which that scenario violates.
 *
 * Not a unit test — runs as `@Test` for `jvmTest`; inspect stdout. Asserts only
 * that the harness ran (no functional assertion on timing).
 */
// @Ignore: one-time measurement (no functional assertion) — a decision
// aid that caught no regression, so it is not run in the gate / CI; kept
// for re-verification. The verified content + conclusion is the class
// KDoc above.
// Re-run: remove @Ignore, then
//   ./gradlew :keel-io:jvmTest --tests "*FreelistContendedBenchmark"
@Ignore
class FreelistContendedBenchmark {

    @Test
    fun freelistUncontended() {
        println("Uncontended single-thread freelist (JVM); ns per pop+push roundtrip")
        println("variant|depth|ns/op")
        val depths = intArrayOf(1, 8)
        val labels = listOf("SpinLock", "ReentrantLock", "PlainTreiber", "VersionedIndexTreiber")
        for (depth in depths) {
            for (label in labels) {
                val ns = uncontendedTrial(label, depth)
                println("$label|$depth|${"%.2f".format(ns)}")
            }
        }
        println("blackhole=$blackhole")
    }

    private fun uncontendedTrial(label: String, depth: Int): Double {
        val freelist = factory(label, POOL_NODES)
        val nodes = Array(depth) { Node(it) }
        for (n in nodes) freelist.push(n)
        repeat(WARMUP_CYCLES) {
            val n = freelist.pop()
            if (n != null) freelist.push(n)
        }
        val ns = DoubleArray(3)
        for (t in 0 until 3) {
            val start = System.nanoTime()
            repeat(TRIAL_CYCLES) {
                val n = freelist.pop()
                if (n != null) {
                    freelist.push(n)
                    blackhole += 1
                }
            }
            ns[t] = (System.nanoTime() - start).toDouble() / TRIAL_CYCLES
        }
        ns.sort()
        return ns[1]
    }

    @Test
    fun freelistContended() {
        println("Contended MPMC freelist bench (JVM, java threads)")
        println("=================================================")
        println("variant|threads|ns/op|Mops/sec|correctness")

        val threadCounts = intArrayOf(2, 4, 8)
        val labels = listOf("SpinLock", "ReentrantLock", "PlainTreiber", "VersionedIndexTreiber")

        for (label in labels) {
            for (n in threadCounts) {
                val r = runTrial(label, POOL_NODES, n, ITERS_PER_THREAD)
                val secs = r.wallNs / 1e9
                val mops = if (secs > 0) r.totalOps / secs / 1e6 else 0.0
                val nsPerOp = if (r.totalOps > 0) r.wallNs / r.totalOps else 0.0
                val verdict = if (r.correct) "OK (${r.drained}/$POOL_NODES)" else "CORRUPT (${r.drained}/$POOL_NODES)"
                println("$label|$n|${"%.2f".format(nsPerOp)}|${"%.2f".format(mops)}|$verdict")
            }
        }
        println("blackhole=$blackhole")
    }

    private class TrialResult(val wallNs: Double, val totalOps: Long, val correct: Boolean, val drained: Int)

    private fun runTrial(label: String, poolNodes: Int, nThreads: Int, iters: Int): TrialResult {
        val freelist = factory(label, poolNodes)
        val nodes = Array(poolNodes) { Node(it) }
        for (node in nodes) freelist.push(node)

        val perThreadOps = LongArray(nThreads)
        val threads = ArrayList<Thread>(nThreads)
        val start = System.nanoTime()
        for (t in 0 until nThreads) {
            val idx = t
            threads.add(
                Thread {
                    var ops = 0L
                    repeat(iters) {
                        val node = freelist.pop()
                        if (node != null) {
                            freelist.push(node)
                            ops++
                        }
                    }
                    perThreadOps[idx] = ops
                },
            )
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        val wallNs = (System.nanoTime() - start).toDouble()

        var totalOps = 0L
        for (o in perThreadOps) totalOps += o
        blackhole += totalOps

        val seen = BooleanArray(poolNodes)
        var drained = 0
        var corrupt = false
        var guard = 0
        while (true) {
            val node = freelist.pop() ?: break
            if (guard++ > poolNodes * 4) {
                corrupt = true
                break
            }
            val id = node.id
            if (id < 0 || id >= poolNodes || seen[id]) {
                corrupt = true
                break
            }
            seen[id] = true
            drained++
        }
        if (drained != poolNodes) corrupt = true

        return TrialResult(wallNs, totalOps, correct = !corrupt, drained = drained)
    }

    private fun factory(label: String, capacity: Int): Freelist = when (label) {
        "SpinLock" -> SpinLockFreelist()
        "ReentrantLock" -> ReentrantLockFreelist()
        "PlainTreiber" -> PlainTreiberFreelist()
        "VersionedIndexTreiber" -> VersionedIndexTreiberFreelist(capacity)
        else -> error("unknown $label")
    }

    /** ArrayDeque + blocking ReentrantLock — parks the waiter on contention. */
    private class ReentrantLockFreelist : Freelist {
        private val list = ArrayDeque<Node>()
        private val lock = ReentrantLock()
        override fun push(node: Node) = lock.withLock { list.addLast(node) }
        override fun pop(): Node? = lock.withLock { if (list.isEmpty()) null else list.removeLast() }
    }

    private class Node(val id: Int) {
        @Volatile
        var nextLink: Node? = null
    }

    private interface Freelist {
        fun push(node: Node)
        fun pop(): Node?
    }

    /** ArrayDeque + spin lock (the native SlabAllocator shape). */
    private class SpinLockFreelist : Freelist {
        private val list = ArrayDeque<Node>()
        private val lock = AtomicBoolean(false)
        private inline fun <T> withSpinLock(block: () -> T): T {
            while (!lock.compareAndSet(false, true)) { /* spin */ }
            try {
                return block()
            } finally {
                lock.set(false)
            }
        }
        override fun push(node: Node) = withSpinLock { list.addLast(node) }
        override fun pop(): Node? = withSpinLock { if (list.isEmpty()) null else list.removeLast() }
    }

    /** Plain intrusive Treiber (the current PooledDirectAllocator shape). */
    private class PlainTreiberFreelist : Freelist {
        private val head = AtomicReference<Node?>(null)
        override fun push(node: Node) {
            while (true) {
                val cur = head.get()
                node.nextLink = cur
                if (head.compareAndSet(cur, node)) return
            }
        }
        override fun pop(): Node? {
            while (true) {
                val cur = head.get() ?: return null
                if (head.compareAndSet(cur, cur.nextLink)) {
                    cur.nextLink = null
                    return cur
                }
            }
        }
    }

    /** ABA-safe versioned-index Treiber: packed (index:32 | version:32) AtomicLong. */
    private class VersionedIndexTreiberFreelist(private val capacity: Int) : Freelist {
        private val nextIdx = IntArray(capacity) { EMPTY }
        private val slots = arrayOfNulls<Node>(capacity)
        private val head = AtomicLong(pack(EMPTY, 0))

        override fun push(node: Node) {
            val idx = node.id
            require(idx in 0 until capacity)
            slots[idx] = node
            while (true) {
                val h = head.get()
                nextIdx[idx] = indexOf(h)
                if (head.compareAndSet(h, pack(idx, versionOf(h) + 1))) return
            }
        }

        override fun pop(): Node? {
            while (true) {
                val h = head.get()
                val idx = indexOf(h)
                if (idx == EMPTY) return null
                val next = nextIdx[idx]
                if (head.compareAndSet(h, pack(next, versionOf(h) + 1))) return slots[idx]
            }
        }

        private companion object {
            const val EMPTY = -1
            fun pack(index: Int, version: Int): Long =
                (index.toLong() and 0xFFFF_FFFFL) or (version.toLong() shl 32)
            fun indexOf(packed: Long): Int = (packed and 0xFFFF_FFFFL).toInt()
            fun versionOf(packed: Long): Int = (packed ushr 32).toInt()
        }
    }

    private companion object {
        @Volatile
        @JvmStatic
        var blackhole: Long = 0
        const val POOL_NODES = 64
        const val ITERS_PER_THREAD = 10_000_000
        const val WARMUP_CYCLES = 5_000_000
        const val TRIAL_CYCLES = 20_000_000
    }
}
