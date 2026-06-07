package io.github.fukusaka.keel.benchmark

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlin.concurrent.AtomicLong
import kotlin.concurrent.AtomicReference
import kotlin.time.DurationUnit
import kotlin.time.TimeSource
import kotlin.time.measureTime
import platform.posix.pthread_mutex_init
import platform.posix.pthread_mutex_lock
import platform.posix.pthread_mutex_t
import platform.posix.pthread_mutex_unlock

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
    val labels = listOf("ArrayDequeNoLock", "ArrayDequeSpinLock", "ArrayDequeMutex", "IntrusiveTreiber", "VersionedIndexTreiber")

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

/**
 * Quantifies the cost of making the freelist a runtime-pluggable strategy: the
 * per-op dispatch overhead of calling through the `Freelist` interface (vtable)
 * versus a concrete final type (inlinable). On Kotlin/Native a call whose static
 * type is the interface is a virtual dispatch regardless of how many impls
 * exist, while a concrete-typed call is a direct/inlinable call — so this is the
 * conservative worst case for "pluggable" overhead.
 *
 * Invocation: `benchmark.kexe --bench=freelist-dispatch`
 */
fun runFreelistDispatchBench() {
    println("Freelist dispatch overhead (Kotlin/Native): concrete (inlinable) vs interface (vtable)")
    println("===================================================================================")
    println("ns per pop+push roundtrip, depth 1")
    println("impl|concrete ns|interface ns|delta ns")
    val spinC = measureSpinConcrete()
    val spinI = measureIface(ArrayDequeSpinLockFreelist())
    val treiberC = measureTreiberConcrete()
    val treiberI = measureIface(IntrusiveTreiberFreelist())
    println("SpinLock|${fmt2(spinC)}|${fmt2(spinI)}|${fmt2(spinI - spinC)}")
    println("Treiber|${fmt2(treiberC)}|${fmt2(treiberI)}|${fmt2(treiberI - treiberC)}")
    println()
    println("blackhole=$freelistBlackhole")
}

private fun measureSpinConcrete(): Double {
    val fl = ArrayDequeSpinLockFreelist() // concrete static type -> direct/inlinable
    fl.push(Node(0))
    val warm = TimeSource.Monotonic.markNow()
    while (warm.elapsedNow().toDouble(DurationUnit.MILLISECONDS) < FREELIST_WARMUP_MS) {
        repeat(1_000) { val n = fl.pop(); if (n != null) fl.push(n) }
    }
    val ns = DoubleArray(3)
    for (t in 0 until 3) {
        var cnt = 0L
        val e = measureTime {
            val d = TimeSource.Monotonic.markNow()
            while (d.elapsedNow().toDouble(DurationUnit.MILLISECONDS) < FREELIST_TRIAL_MS) {
                repeat(10_000) { val n = fl.pop(); if (n != null) { fl.push(n); freelistBlackhole += 1 }; cnt++ }
            }
        }
        ns[t] = e.toDouble(DurationUnit.NANOSECONDS) / cnt.toDouble()
    }
    ns.sort()
    return ns[1]
}

private fun measureTreiberConcrete(): Double {
    val fl = IntrusiveTreiberFreelist() // concrete static type -> direct/inlinable
    fl.push(Node(0))
    val warm = TimeSource.Monotonic.markNow()
    while (warm.elapsedNow().toDouble(DurationUnit.MILLISECONDS) < FREELIST_WARMUP_MS) {
        repeat(1_000) { val n = fl.pop(); if (n != null) fl.push(n) }
    }
    val ns = DoubleArray(3)
    for (t in 0 until 3) {
        var cnt = 0L
        val e = measureTime {
            val d = TimeSource.Monotonic.markNow()
            while (d.elapsedNow().toDouble(DurationUnit.MILLISECONDS) < FREELIST_TRIAL_MS) {
                repeat(10_000) { val n = fl.pop(); if (n != null) { fl.push(n); freelistBlackhole += 1 }; cnt++ }
            }
        }
        ns[t] = e.toDouble(DurationUnit.NANOSECONDS) / cnt.toDouble()
    }
    ns.sort()
    return ns[1]
}

private fun measureIface(fl: Freelist): Double {
    // static type is Freelist -> virtual dispatch per pop/push
    fl.push(Node(0))
    val warm = TimeSource.Monotonic.markNow()
    while (warm.elapsedNow().toDouble(DurationUnit.MILLISECONDS) < FREELIST_WARMUP_MS) {
        repeat(1_000) { val n = fl.pop(); if (n != null) fl.push(n) }
    }
    val ns = DoubleArray(3)
    for (t in 0 until 3) {
        var cnt = 0L
        val e = measureTime {
            val d = TimeSource.Monotonic.markNow()
            while (d.elapsedNow().toDouble(DurationUnit.MILLISECONDS) < FREELIST_TRIAL_MS) {
                repeat(10_000) { val n = fl.pop(); if (n != null) { fl.push(n); freelistBlackhole += 1 }; cnt++ }
            }
        }
        ns[t] = e.toDouble(DurationUnit.NANOSECONDS) / cnt.toDouble()
    }
    ns.sort()
    return ns[1]
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
    val nodes = Array(depth) { Node(it) }
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
    "ArrayDequeMutex" -> ArrayDequeMutexFreelist()
    "IntrusiveTreiber" -> IntrusiveTreiberFreelist()
    "VersionedIndexTreiber" -> VersionedIndexTreiberFreelist()
    else -> error("unknown $label")
}

/** Max distinct node id the [VersionedIndexTreiberFreelist] handles (covers both benches). */
internal const val MAX_INDEX_CAPACITY = 256

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

/**
 * ArrayDeque + blocking `pthread_mutex` — parks the waiter on contention instead
 * of busy-waiting, avoiding the userspace-spinlock preemption pathology on Linux
 * (a preempted lock holder does not make waiters burn CPU). Uncontended,
 * `pthread_mutex_lock` takes a CAS fast-path comparable to the spin lock.
 */
@OptIn(ExperimentalForeignApi::class)
internal class ArrayDequeMutexFreelist : Freelist {
    private val list = ArrayDeque<Node>(16)
    private val mutex = nativeHeap.alloc<pthread_mutex_t>()

    init {
        pthread_mutex_init(mutex.ptr, null)
    }

    override fun push(node: Node) {
        pthread_mutex_lock(mutex.ptr)
        try {
            list.addLast(node)
        } finally {
            pthread_mutex_unlock(mutex.ptr)
        }
    }

    override fun pop(): Node? {
        pthread_mutex_lock(mutex.ptr)
        try {
            return if (list.isEmpty()) null else list.removeLast()
        } finally {
            pthread_mutex_unlock(mutex.ptr)
        }
    }
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

/**
 * ABA-safe lock-free freelist: a Treiber stack keyed by node **index** with a
 * version tag, packed into a single `AtomicLong` head as `(index:32 | version:32)`.
 *
 * Defeats ABA without a lock: every successful pop/push increments the version,
 * so a thread's CAS succeeds only if no operation touched the head since it read
 * it — making the `nextIdx[idx]` it read provably current. The classic Treiber
 * "pointer-identity" CAS cannot tell A-removed-and-returned from A-unchanged;
 * the version can. (The 32-bit version wraps after 2^32 ops between one thread's
 * read and CAS — astronomically unlikely.)
 *
 * Index-based because Kotlin/Native cannot tag an object reference (an
 * `AtomicReference<Node>` CAS compares identity, no room for a version), so the
 * pool is addressed by a fixed `Int` id and links live in an `IntArray`.
 *
 * Requires `node.id in 0 until capacity`. This is a bench-only candidate for the
 * "reinforced Treiber" escalation path; production wiring would need to reconcile
 * the index representation with io_uring pooled-buffer enumeration and the
 * NativeIoBuf lifecycle.
 */
internal class VersionedIndexTreiberFreelist(private val capacity: Int = MAX_INDEX_CAPACITY) : Freelist {
    private val nextIdx = IntArray(capacity) { EMPTY }
    private val slots = arrayOfNulls<Node>(capacity)
    private val head = AtomicLong(pack(EMPTY, 0))

    override fun push(node: Node) {
        val idx = node.id
        require(idx in 0 until capacity) { "node id $idx out of range [0,$capacity)" }
        slots[idx] = node
        while (true) {
            val h = head.value
            nextIdx[idx] = indexOf(h)
            if (head.compareAndSet(h, pack(idx, versionOf(h) + 1))) return
        }
    }

    override fun pop(): Node? {
        while (true) {
            val h = head.value
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
