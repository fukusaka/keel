package io.github.fukusaka.keel.buf

import java.io.File
import java.net.InetAddress
import java.nio.ByteBuffer
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * High-load behaviour / robustness stress check for the pooled allocator's
 * **central chunk arena under saturation**.
 *
 * [NoCacheAllocator] installs an empty ladder, so every `allocate` is a freelist
 * miss routed straight to a shard's `ChunkArena.carve` and every `release` routes
 * to `freeBacking` → `freeRun`. The per-EL freelist front is bypassed, so the
 * **per-shard arena locks are the whole cost** — exactly the central back-end that
 * the N-way [ShardedChunkArena] shards.
 *
 * **Production model**: a root allocator plus **one child per thread** (the
 * per-EventLoop `createChild()` shape), each child pinned to its own shard and
 * carrying its **own** freelist / `trimCountdown` / stats. This matters: a single
 * *shared* allocator instead makes every thread hammer one `trimCountdown` cache
 * line, whose bouncing dominates the median once sharding lets the threads run in
 * parallel — a harness artifact (the single arena lock used to hide it by
 * serialising) that the per-EL child shape avoids, the way real EventLoops do.
 *
 * This is still an **idealised condition** — a real workload keeps the freelist hit
 * ~100 %, so the carve (and thus the central) is rarely reached. The check is a
 * *stress / behaviour* verification, **not** a measurement of the common path and
 * **not** a perf gate for a design change. Its purpose is:
 *
 * 1. **correctness under load** (asserted): with the production-safe central
 *    (per-class [MutexFreelist] + per-shard arena locks), every thread must finish
 *    all iterations with **zero exceptions** — no corruption / crash / deadlock when
 *    N threads carve concurrently. A regression like a subpage-run double-free
 *    surfaces here.
 * 2. **degradation shape** (observed, not asserted): per-op latency p50 / p99 / max
 *    under contention. Sharding the central cuts the p99 tail (~7–25× on a 32-core
 *    A/B) while keeping p50 flat; the recorded shape informs the design, it does not
 *    by itself decide it (the realistic-path e2e is the no-regression decider).
 *
 * The p99 / max are recorded with a per-op [System.nanoTime] pair, which inflates
 * absolute latency (especially the low-contention rows where an op is only tens of
 * ns); the **shape** (tail vs median, sharded vs single-lock) is the signal, not the
 * absolute value.
 *
 * Gating: returns early unless `KEEL_STRESS` is set — `quick` runs the light
 * thread counts, `full` adds the high counts.
 *
 * Result stocking: the degradation record is always written to
 * `benchmark/stress/<host>/arena-saturation-<mode>-<timestamp>.txt` (host = `BENCH_HOST_LABEL`
 * or the local hostname) so stress results are stocked, not run-and-discard. The
 * repo root is found by walking up for `settings.gradle.kts`; if not found the
 * record is only printed.
 */
class PooledAllocatorArenaSaturationStress {

    @Test
    fun arenaSaturation() {
        val mode = stressMode() ?: return
        if (mode !in QUICK_OR_FULL) return
        // full subsumes quick (FULL_THREAD_COUNTS ⊇ QUICK_THREAD_COUNTS), so a
        // single mode-driven run covers both gates — no double run / double file.
        val full = mode == "full"
        val iters = if (full) FULL_ITERS else QUICK_ITERS
        runScenarios(mode, iters, if (full) FULL_THREAD_COUNTS else QUICK_THREAD_COUNTS)
    }

    private fun runScenarios(mode: String, itersPerThread: Int, threadCounts: IntArray) {
        val rows = StringBuilder()
        rows.appendLine("# arena-saturation stress — every op hits the arena lock (freelist front bypassed)")
        rows.appendLine("scenario|threads|iters|p50ns|p99ns|maxns|surv|exc")
        for (tc in threadCounts) {
            for (sc in SCENARIOS) {
                val r = contendedTrial(sc.sizes, tc, sc.distinct, itersPerThread)
                // Correctness under load: the production-safe central must not
                // corrupt or deadlock — every thread completes, zero exceptions.
                assertEquals(0, r.exceptions, "${sc.name}@${tc}t: arena corrupted under saturation")
                assertEquals(tc, r.survivors, "${sc.name}@${tc}t: thread(s) died under saturation")
                val line = "${sc.name}|$tc|$itersPerThread|${r.p50}|${r.p99}|${r.max}|${r.survivors}/$tc|${r.exceptions}"
                rows.appendLine(line)
                println(line)
            }
        }
        stock(mode, rows.toString())
    }

    private fun contendedTrial(sizes: IntArray, nThreads: Int, distinct: Boolean, itersPerThread: Int): TrialResult {
        // Production model: a root allocator plus one child per thread (the
        // per-EventLoop createChild() shape). Each child owns its own freelist /
        // trimCountdown / stats and is pinned to one shard, so threads do NOT share a
        // single allocator's mutable counters — the way real EventLoops run. A single
        // shared allocator instead makes every thread hammer one trimCountdown / stats
        // cache line, whose bouncing dominates once sharding lets the threads run in
        // parallel (a harness artifact, not a production cost — see this class's notes).
        val root = NoCacheAllocator(freelistFactory = ::MutexFreelist)
        val children = Array(nThreads) { root.createChild() }
        try {
            val perThreadLat = arrayOfNulls<LongArray>(nThreads)
            val perThreadOps = IntArray(nThreads)
            val exceptions = AtomicInteger(0)
            val threads = ArrayList<Thread>(nThreads)
            for (t in 0 until nThreads) {
                val tid = t
                val child = children[tid]
                threads += Thread {
                    val lat = LongArray(itersPerThread)
                    var ops = 0
                    try {
                        // Warm this child's pinned shard before measuring, so the first
                        // measured carves don't pay fresh-chunk allocation.
                        var w = 0
                        while (w < WARMUP_ITERS) {
                            val s = if (distinct) sizes[tid % sizes.size] else sizes[(tid + w) % sizes.size]
                            val b = child.allocate(s)
                            b.writeByte(0)
                            b.release()
                            w++
                        }
                        var i = 0
                        while (i < itersPerThread) {
                            val s = if (distinct) sizes[tid % sizes.size] else sizes[(tid + i) % sizes.size]
                            val t0 = System.nanoTime()
                            val buf = child.allocate(s)
                            buf.writeByte(0)
                            buf.release()
                            lat[i] = System.nanoTime() - t0
                            ops++
                            i++
                        }
                    } catch (@Suppress("SwallowedException", "TooGenericExceptionCaught") e: Throwable) {
                        exceptions.incrementAndGet()
                    }
                    perThreadOps[tid] = ops
                    perThreadLat[tid] = if (ops == itersPerThread) lat else lat.copyOf(ops)
                }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join() }

            // Only threads that completed every iteration contribute to the tail,
            // so a crashed thread (unsafe regression) does not skew the percentiles.
            var survivors = 0
            var survivorOps = 0
            for (tid in 0 until nThreads) {
                if (perThreadOps[tid] == itersPerThread) {
                    survivors++
                    survivorOps += perThreadOps[tid]
                }
            }
            // Merge survivor per-op latencies into one primitive array (no boxing),
            // sort once, read percentiles.
            val merged = LongArray(survivorOps)
            var off = 0
            for (tid in 0 until nThreads) {
                if (perThreadOps[tid] == itersPerThread) {
                    val lat = perThreadLat[tid]!!
                    lat.copyInto(merged, off)
                    off += lat.size
                }
            }
            merged.sort()
            return TrialResult(
                p50 = percentile(merged, P50),
                p99 = percentile(merged, P99),
                max = if (merged.isEmpty()) 0L else merged[merged.size - 1],
                survivors = survivors,
                exceptions = exceptions.get(),
            )
        } finally {
            // Closing the root closes every child and the shared sharded arena.
            runCatching { root.close() }
        }
    }

    private fun percentile(sorted: LongArray, p: Int): Long {
        if (sorted.isEmpty()) return 0L
        val idx = ((sorted.size.toLong() * p) / PERCENT_BASE).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }

    private fun stock(mode: String, record: String) {
        val root = findRepoRoot() ?: return
        val host = System.getenv("BENCH_HOST_LABEL")
            ?: runCatching { InetAddress.getLocalHost().hostName.substringBefore('.') }.getOrDefault("unknown")
        val dir = File(root, "benchmark/stress/$host").apply { mkdirs() }
        val ts = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
        File(dir, "arena-saturation-$mode-$ts.txt").writeText(record)
    }

    private fun findRepoRoot(): File? {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile
        }
        return null
    }

    private class TrialResult(
        val p50: Long,
        val p99: Long,
        val max: Long,
        val survivors: Int,
        val exceptions: Int,
    )

    private class Scenario(val name: String, val sizes: IntArray, val distinct: Boolean)

    /**
     * [PooledAllocator] subclass that does **not** install the default ladder, so
     * every allocate is a freelist miss → `chunkArena.carve` and every release
     * routes to `freeBacking`. The arena lock fires on every operation.
     */
    private class NoCacheAllocator private constructor(
        freelistFactory: FreelistFactory?,
        sharedArena: ShardedChunkArena?,
        shardIdx: Int,
    ) : PooledAllocator(freelistFactory = freelistFactory, sharedArena = sharedArena, shardIdx = shardIdx) {

        constructor(freelistFactory: FreelistFactory?) : this(freelistFactory, null, 0)

        @Suppress("IoBufLeak")
        override fun newBuffer(capacity: Int): IoBuf = DirectIoBuf(capacity)

        @Suppress("IoBufLeak")
        override fun newChunkView(
            backing: IoBuf,
            byteOffset: Int,
            length: Int,
            pooledChunk: PooledChunk,
            handle: Long,
        ): IoBuf = DirectIoBuf.chunkView(backing, byteOffset, length, pooledChunk, handle)

        override fun defaultFreelist(maxSlots: Int): Freelist = MutexFreelist(maxSlots)

        // Each per-thread child shares the root's sharded arena and is pinned to its
        // own shard (the production per-EventLoop model), carrying its own freelist /
        // trimCountdown / stats. The base shardIndexForCarve (the pinned shardIdx)
        // routes the child to its shard — no thread-hash needed, since the children are
        // already spread one per shard by createChild.
        override fun newChildInstance(
            maxTotalBytes: Long,
            sharedArena: ShardedChunkArena,
            shardIdx: Int,
        ): PooledAllocator = NoCacheAllocator(freelistFactory, sharedArena, shardIdx)

        override fun wrapBytes(bytes: ByteArray, offset: Int, length: Int): IoBuf? {
            val heapBuffer = if (offset == 0 && length == bytes.size) {
                ByteBuffer.wrap(bytes)
            } else {
                ByteBuffer.wrap(bytes, offset, length).slice()
            }
            return DirectIoBuf.wrapExternal(heapBuffer, bytesWritten = length)
        }
    }

    private companion object {
        val QUICK_OR_FULL = setOf("quick", "full")

        fun stressMode(): String? = System.getenv("KEEL_STRESS")

        /** Subpage tier representative. */
        const val SUBPAGE_SIZE = 256

        /** Page tier representative. */
        const val PAGE_SIZE = 8192

        /** Subpage classes; with `distinct = true` each thread pins to its own. */
        val MIXED_SUBPAGE_SIZES = intArrayOf(16, 64, 256, 1024)

        /** Subpage + page tiers rotated per iteration. */
        val MIXED_FULL_SIZES = intArrayOf(16, 64, 256, 1024, 4096, 8192)

        val SCENARIOS = listOf(
            Scenario("uniformSubpage", intArrayOf(SUBPAGE_SIZE), distinct = false),
            Scenario("uniformPage", intArrayOf(PAGE_SIZE), distinct = false),
            Scenario("mixedSubpage", MIXED_SUBPAGE_SIZES, distinct = true),
            Scenario("mixedFull", MIXED_FULL_SIZES, distinct = false),
        )

        /** quick = PR-gate budget (~seconds); full = saturation tail (manual). */
        val QUICK_THREAD_COUNTS = intArrayOf(4, 16)
        val FULL_THREAD_COUNTS = intArrayOf(4, 16, 32, 64)

        const val WARMUP_ITERS = 20_000
        const val QUICK_ITERS = 20_000
        const val FULL_ITERS = 100_000

        const val P50 = 50
        const val P99 = 99
        const val PERCENT_BASE = 100
    }
}
