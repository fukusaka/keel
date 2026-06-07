package io.github.fukusaka.keel.benchmark

import kotlin.concurrent.AtomicReference
import kotlin.time.DurationUnit
import kotlin.time.TimeSource
import kotlin.time.measureTime

/**
 * Variant bench for the per-size-class **freelist** used by `SlabAllocator`
 * (Native) on the allocation hot path.
 *
 * Context: Phase 1 of the chunk-based allocator roadmap unifies the size-class
 * map (decided: lock-free `IntArrayScan`). The freelist underneath each class is
 * a separate concurrency-bearing structure. On Native it must be thread-safe
 * because NWConnection releases buffers on a GCD thread distinct from the
 * allocating thread (the current `SlabAllocator` spin lock guards both the map
 * lookup and the `ArrayDeque` op in one critical section; once the map becomes
 * lock-free the freelist still needs its own protection). kqueue / epoll are
 * EL-pinned and access the freelist uncontended.
 *
 * This bench isolates the steady-state hot-path cost (1 pop + 1 push roundtrip
 * at pool depth) of the candidate freelist structures, single-threaded
 * (uncontended = the kqueue / epoll majority case + the uncontended NWConnection
 * case). Contended MPMC (concurrent NWConnection release) is not covered here —
 * it needs a pthread harness and is a separate measurement.
 *
 * Strategies:
 * - **ArrayDequeNoLock**: `ArrayDeque<T>` removeLast/addLast, no lock. Floor /
 *   theoretical kqueue-epoll-only (NOT NWConnection-safe). Backing array, no
 *   per-element node alloc.
 * - **ArrayDequeSpinLock**: `ArrayDeque<T>` guarded by `AtomicReference<Boolean>`
 *   spin lock — the current `SlabAllocator` freelist shape (NWConnection-safe).
 * - **IntrusiveTreiber**: lock-free CAS stack via the element's `nextLink` field
 *   (the JVM `PooledDirectAllocator` shape; would unify Native with JVM but
 *   reverses design.md's "Native ArrayDeque has no benefit from intrusive").
 *
 * Invocation: `benchmark.kexe --bench=freelist-variants`
 */
fun runFreelistVariantBench() {
    println("Per-size-class freelist variant bench (Kotlin/Native)")
    println("=====================================================")
    println("Single-thread steady-state 1 pop + 1 push roundtrip; ns/op = ns per roundtrip")
    println("variant|depth|ns/op|ops/sec")

    val depths = intArrayOf(1, 8)
    val labels = listOf("ArrayDequeNoLock", "ArrayDequeSpinLock", "IntrusiveTreiber")

    for (depth in depths) {
        for (label in labels) {
            val ns = execTrial(freelistFactory(label), depth)
            val ops = 1_000_000_000.0 / ns
            println("$label|$depth|${fmt2(ns)}|${fmtE2(ops)}")
        }
    }
    println()
    println("blackhole=$freelistBlackhole")
}

@kotlin.concurrent.Volatile
private var freelistBlackhole: Long = 0

private const val FREELIST_WARMUP_MS = 1_500L
private const val FREELIST_TRIAL_MS = 3_000L

/**
 * Warmup + 3-trial median. Returns ns per roundtrip. Pre-fills the freelist to
 * [depth] so pop always hits (steady state). Each iteration pops one element and
 * pushes it back, keeping the depth constant.
 */
private fun execTrial(freelist: Freelist, depth: Int): Double {
    val nodes = Array(depth) { Node() }
    for (n in nodes) freelist.push(n)

    val warmupMark = TimeSource.Monotonic.markNow()
    var iters = 0L
    while (warmupMark.elapsedNow().toDouble(DurationUnit.MILLISECONDS) < FREELIST_WARMUP_MS) {
        repeat(1_000) {
            val n = freelist.pop()
            if (n != null) freelist.push(n)
            iters++
        }
    }
    freelistBlackhole += iters

    val nsPerOp = DoubleArray(3)
    for (t in 0 until 3) {
        var trialIters = 0L
        val elapsed = measureTime {
            val deadline = TimeSource.Monotonic.markNow()
            while (deadline.elapsedNow().toDouble(DurationUnit.MILLISECONDS) < FREELIST_TRIAL_MS) {
                repeat(10_000) {
                    val n = freelist.pop()
                    if (n != null) {
                        freelist.push(n)
                        freelistBlackhole += 1
                    }
                    trialIters++
                }
            }
        }
        nsPerOp[t] = elapsed.toDouble(DurationUnit.NANOSECONDS) / trialIters.toDouble()
    }
    nsPerOp.sort()
    return nsPerOp[1]
}

internal fun freelistFactory(label: String): Freelist = when (label) {
    "ArrayDequeNoLock" -> ArrayDequeNoLockFreelist()
    "ArrayDequeSpinLock" -> ArrayDequeSpinLockFreelist()
    "IntrusiveTreiber" -> IntrusiveTreiberFreelist()
    else -> error("unknown $label")
}

private fun fmt2(v: Double): String = (kotlin.math.round(v * 100.0) / 100.0).toString()

private fun fmtE2(v: Double): String {
    if (v <= 0.0) return "0"
    val exp = kotlin.math.floor(kotlin.math.log10(v)).toInt()
    var p = 1.0
    val absExp = if (exp < 0) -exp else exp
    repeat(absExp) { p *= 10.0 }
    val mantissa = if (exp < 0) v * p else v / p
    return "${kotlin.math.round(mantissa * 100.0) / 100.0}e$exp"
}

// -------------------------------------------------------------------------
// Pooled element with an intrusive freelist link (mirrors NativeIoBuf.nextLink).
// -------------------------------------------------------------------------

internal class Node(val id: Int = -1) {
    var nextLink: Node? = null
}

internal interface Freelist {
    fun push(node: Node)
    fun pop(): Node?
}

/** ArrayDeque, no lock. Floor; kqueue/epoll-only (not NWConnection-safe). */
internal class ArrayDequeNoLockFreelist : Freelist {
    private val list = ArrayDeque<Node>(16)
    override fun push(node: Node) { list.addLast(node) }
    override fun pop(): Node? = if (list.isEmpty()) null else list.removeLast()
}

/** ArrayDeque + spin lock — current SlabAllocator freelist shape. */
internal class ArrayDequeSpinLockFreelist : Freelist {
    private val list = ArrayDeque<Node>(16)
    private val lock = AtomicReference(false)

    private inline fun <T> withSpinLock(block: () -> T): T {
        while (!lock.compareAndSet(false, true)) { /* spin */ }
        try {
            return block()
        } finally {
            lock.value = false
        }
    }

    override fun push(node: Node) = withSpinLock { list.addLast(node) }
    override fun pop(): Node? = withSpinLock { if (list.isEmpty()) null else list.removeLast() }
}

/** Lock-free intrusive Treiber stack via [Node.nextLink] — the JVM shape. */
internal class IntrusiveTreiberFreelist : Freelist {
    private val head = AtomicReference<Node?>(null)

    override fun push(node: Node) {
        while (true) {
            val cur = head.value
            node.nextLink = cur
            if (head.compareAndSet(cur, node)) return
        }
    }

    override fun pop(): Node? {
        while (true) {
            val cur = head.value ?: return null
            if (head.compareAndSet(cur, cur.nextLink)) {
                cur.nextLink = null
                return cur
            }
        }
    }
}
