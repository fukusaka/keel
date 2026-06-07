package io.github.fukusaka.keel.benchmark

import kotlinx.cinterop.Arena
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.get
import kotlinx.cinterop.plus
import kotlinx.cinterop.staticCFunction
import kotlin.time.TimeSource
import platform.posix.pthread_create
import platform.posix.pthread_join
import platform.posix.pthread_tVar

/**
 * Contended MPMC bench for the per-size-class freelist, the realistic
 * NWConnection scenario: buffers are popped (allocated) and pushed (released)
 * concurrently from multiple GCD threads against one shared per-EL freelist.
 *
 * Complements `--bench=freelist-variants` (single-thread, uncontended). The
 * single-thread bench showed the lock-free intrusive Treiber stack beats the
 * spin-lock ArrayDeque uncontended; this bench checks whether that holds under
 * real contention **and**, crucially, whether the Treiber stack stays correct.
 *
 * **Why correctness matters here**: a Treiber stack of reused nodes is exposed
 * to the ABA problem. The JVM `PooledDirectAllocator` uses a Treiber stack but
 * its engines (NIO / Netty) are EL-pinned, so it is never truly contended and
 * never hits ABA. Native NWConnection genuinely releases cross-thread, so ABA is
 * reachable. This bench drives N threads doing pop+push roundtrips on a shared
 * freelist, then **drains and verifies** the node set is intact (no loss, no
 * duplication, no cycle). A spin lock is ABA-immune by construction.
 *
 * Reports aggregate throughput (ns/op across all threads = wall / total ops, so
 * lower = better scaling) and a correctness verdict. `ArrayDequeNoLock` is
 * excluded — it is not thread-safe and would corrupt or crash.
 *
 * Invocation: `benchmark.kexe --bench=freelist-contended`
 */
@OptIn(ExperimentalForeignApi::class)
fun runContendedFreelistBench() {
    println("Contended MPMC freelist bench (Kotlin/Native, raw pthread)")
    println("==========================================================")
    println("N threads pop+push a shared freelist; ns/op = wall / total ops (lower = better scaling)")
    println("variant|threads|ns/op|Mops/sec|correctness")

    val threadCounts = intArrayOf(2, 4, 8)
    val labels = listOf("ArrayDequeSpinLock", "ArrayDequeMutex", "IntrusiveTreiber", "VersionedIndexTreiber")

    for (label in labels) {
        for (n in threadCounts) {
            val r = runContendedTrial(label, POOL_NODES, n, ITERS_PER_THREAD)
            val mops = if (r.wallNs > 0) r.totalOps.toDouble() / (r.wallNs / 1e9) / 1e6 else 0.0
            val nsPerOp = if (r.totalOps > 0) r.wallNs / r.totalOps.toDouble() else 0.0
            val verdict = if (r.correct) "OK (${r.drained}/$POOL_NODES)" else "CORRUPT (${r.drained}/$POOL_NODES)"
            println("$label|$n|${fmt2c(nsPerOp)}|${fmt2c(mops)}|$verdict")
        }
    }
    println()
    println("blackhole=$contendedBlackhole")
}

private const val POOL_NODES = 64
private const val ITERS_PER_THREAD = 10_000_000

@kotlin.concurrent.Volatile
private var contendedBlackhole: Long = 0

private class ContendedCtx(val freelist: Freelist, val iters: Int)

private class ThreadArg(val ctx: ContendedCtx) {
    var ops: Long = 0
}

private class TrialResult(
    val wallNs: Double,
    val totalOps: Long,
    val correct: Boolean,
    val drained: Int,
)

@OptIn(ExperimentalForeignApi::class)
private fun runContendedTrial(label: String, poolNodes: Int, nThreads: Int, iters: Int): TrialResult {
    val freelist = freelistFactory(label)
    val nodes = Array(poolNodes) { Node(it) }
    for (node in nodes) freelist.push(node)

    val ctx = ContendedCtx(freelist, iters)
    val arena = Arena()
    val args = ArrayList<StableRef<ThreadArg>>(nThreads)
    var wallNs: Double
    try {
        val threads = arena.allocArray<pthread_tVar>(nThreads)
        val mark = TimeSource.Monotonic.markNow()
        for (i in 0 until nThreads) {
            val ref = StableRef.create(ThreadArg(ctx))
            args.add(ref)
            pthread_create(
                (threads + i.toLong())!!, null,
                staticCFunction { arg ->
                    val a = arg!!.asStableRef<ThreadArg>().get()
                    val fl = a.ctx.freelist
                    var ops = 0L
                    repeat(a.ctx.iters) {
                        val node = fl.pop()
                        if (node != null) {
                            fl.push(node)
                            ops++
                        }
                    }
                    a.ops = ops
                    null
                },
                ref.asCPointer(),
            )
        }
        for (i in 0 until nThreads) pthread_join(threads[i], null)
        wallNs = mark.elapsedNow().inWholeNanoseconds.toDouble()
    } finally {
        arena.clear()
    }

    var totalOps = 0L
    for (ref in args) {
        totalOps += ref.get().ops
        ref.dispose()
    }
    contendedBlackhole += totalOps

    // Correctness: drain the quiescent freelist; every original node must appear
    // exactly once (no loss, no duplication, no cycle).
    val seen = BooleanArray(poolNodes)
    var drained = 0
    var corrupt = false
    var guard = 0
    while (true) {
        val node = freelist.pop() ?: break
        if (guard++ > poolNodes * 4) { corrupt = true; break } // cycle guard
        val id = node.id
        if (id < 0 || id >= poolNodes || seen[id]) { corrupt = true; break }
        seen[id] = true
        drained++
    }
    if (drained != poolNodes) corrupt = true

    return TrialResult(wallNs, totalOps, correct = !corrupt, drained = drained)
}

private fun fmt2c(v: Double): String = (kotlin.math.round(v * 100.0) / 100.0).toString()
