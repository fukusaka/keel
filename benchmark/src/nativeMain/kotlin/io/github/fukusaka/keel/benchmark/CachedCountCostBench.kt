package io.github.fukusaka.keel.benchmark

import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicReference
import kotlin.time.DurationUnit
import kotlin.time.TimeSource
import kotlin.time.measureTime

/**
 * Quantifies the marginal hot-path cost of making the per-size-class `cachedCount`
 * counter (`PooledAllocator`) thread-safe. This decides between two fixes for the
 * concurrent-allocate data race that arises when a pooled channel is consumed via
 * `asSource` from a non-EventLoop coroutine (the engine's push read path and the
 * caller's pull refill both allocate from the same per-connection allocator):
 * confinement (give the pull refill its own allocator) versus a shared-but-
 * thread-safe allocator (atomic counter).
 *
 * The allocate / release hot path already pays a [ArrayDequeSpinLockFreelist] CAS
 * (pop + push). `cachedCount` is decremented on the pop and incremented on the
 * push. Three counter strategies, all over the same real spin-lock freelist:
 * - **plain-outside** — the current racy form: plain `IntArray` ++/-- *outside* the
 *   freelist lock. The baseline (fast but not concurrency-safe).
 * - **atomic-outside** — an atomic ++/-- outside the lock. The cost of the
 *   "thread-safe shared allocator" option for every allocate, push and pull.
 * - **plain-under-lock** — the counter maintained *inside* the freelist's existing
 *   spin-lock critical section (no new atomic; race-free because the lock already
 *   serialises). The "correct without a new atomic" candidate.
 *
 * ns/op = one pop + push roundtrip including its counter update. Single-thread,
 * steady-state (depth keeps the pop hitting), warmup + 3-trial median — same shape
 * as `--bench=freelist-variants`. The counter is a small part of the real
 * allocate path (which also does class lookup, reset, record, trim cadence), so
 * isolating it here gives a *conservative* (upper-bound) estimate of the atomic's
 * relative cost on a full allocate.
 *
 * Invocation: `benchmark.kexe --bench=cachedcount-cost`
 */
fun runCachedCountCostBench() {
    println("cachedCount counter-strategy cost (Kotlin/Native)")
    println("=================================================")
    println("ns per pop+push roundtrip incl. counter update; spin-lock freelist depth $CC_DEPTH")
    println("strategy|ns/op|ops/sec")
    for (label in listOf("plain-outside", "atomic-outside", "plain-under-lock")) {
        val ns = measureCachedCount(label)
        val ops = 1_000_000_000.0 / ns
        println("$label|${(kotlin.math.round(ns * 100.0) / 100.0)}|${(kotlin.math.round(ops / 1_000_000.0 * 100.0) / 100.0)}M")
    }
    println()
    println("blackhole=$ccBlackhole")
}

@kotlin.concurrent.Volatile
private var ccBlackhole: Long = 0

private const val CC_WARMUP_MS = 1_500L
private const val CC_TRIAL_MS = 3_000L
private const val CC_DEPTH = 8

private fun measureCachedCount(label: String): Double {
    // plain-outside / atomic-outside run over a plain spin-lock freelist with the
    // counter updated by the loop; plain-under-lock folds the counter into the
    // freelist's own lock.
    val fl = ArrayDequeSpinLockFreelist()
    val counting = SpinLockCountingFreelist()
    val target = if (label == "plain-under-lock") counting else fl
    repeat(CC_DEPTH) { i -> if (label == "plain-under-lock") counting.push(Node(i)) else fl.push(Node(i)) }

    val plain = IntArray(1)
    val atomic = AtomicInt(0)

    // One pop+push+counter roundtrip for the chosen strategy. Returns a value to
    // fold into the blackhole so the counter work is not elided.
    fun roundtrip(): Long = when (label) {
        "plain-outside" -> {
            val n = fl.pop()
            plain[0]--
            if (n != null) { fl.push(n); plain[0]++ }
            plain[0].toLong()
        }
        "atomic-outside" -> {
            val n = fl.pop()
            atomic.addAndGet(-1)
            if (n != null) { fl.push(n); atomic.addAndGet(1) }
            atomic.value.toLong()
        }
        else -> { // plain-under-lock: counter lives inside counting's spin lock
            val n = counting.pop()
            if (n != null) counting.push(n)
            counting.size.toLong()
        }
    }

    val warm = TimeSource.Monotonic.markNow()
    while (warm.elapsedNow().toDouble(DurationUnit.MILLISECONDS) < CC_WARMUP_MS) {
        var acc = 0L
        repeat(1_000) { acc += roundtrip() }
        ccBlackhole += acc
    }

    val nsPerOp = DoubleArray(3)
    for (t in 0 until 3) {
        var iters = 0L
        var acc = 0L
        val elapsed = measureTime {
            val deadline = TimeSource.Monotonic.markNow()
            while (deadline.elapsedNow().toDouble(DurationUnit.MILLISECONDS) < CC_TRIAL_MS) {
                repeat(10_000) { acc += roundtrip(); iters++ }
            }
        }
        ccBlackhole += acc
        nsPerOp[t] = elapsed.toDouble(DurationUnit.NANOSECONDS) / iters.toDouble()
    }
    nsPerOp.sort()
    ccBlackhole += target.hashCode().toLong()
    return nsPerOp[1]
}

/**
 * [ArrayDequeSpinLockFreelist] that also maintains its element count inside the
 * spin-lock critical section, so the count is race-free with no extra atomic — the
 * "plain-under-lock" candidate for `cachedCount`.
 */
internal class SpinLockCountingFreelist {
    private val list = ArrayDeque<Node>(16)
    private val lock = AtomicReference(false)

    // Plain: every read/write is inside the spin lock (push / pop here; the real
    // trim / cachedCountOf consumers would read under the same lock), so the lock
    // provides visibility and no volatile fence is needed.
    var size: Int = 0
        private set

    private inline fun <T> withSpinLock(block: () -> T): T {
        while (!lock.compareAndSet(false, true)) { /* spin */ }
        try {
            return block()
        } finally {
            lock.value = false
        }
    }

    fun push(node: Node) = withSpinLock {
        list.addLast(node)
        size++
    }

    fun pop(): Node? = withSpinLock {
        if (list.isEmpty()) {
            null
        } else {
            size--
            list.removeLast()
        }
    }
}
