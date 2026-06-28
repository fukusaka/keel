@file:OptIn(UnsafeIoBufApi::class)

package io.github.fukusaka.keel.buf

import io.github.fukusaka.keel.scope.scopeLocal
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Ignore
import kotlin.test.Test

/**
 * Feasibility spike for the allocator shape: per-thread cache front
 * (lock-free) + central [MutexFreelist] back-end. Goal is to turn the
 * estimated per-thread-cache benefit into measured data on three checkpoints:
 *
 * - **C1** cache hit rate ≥ 85% under representative shapes
 * - **C2** per-thread-cache hot path cost ≤ 2x of the current per-EL
 *   [PooledDirectAllocator] uncontended baseline
 * - **C3** scaling curve under contention — the central arena must not become
 *   the bottleneck the way a single shared [MutexFreelist] does under genuine MPMC contention
 *
 * The spike is intentionally minimal: one fixed size class (`CLASS_SIZE`),
 * direct [ByteBuffer] backing, no ladder / no chunk arena / no hintSizeClass /
 * no createChild. Just the allocate/release shape so the hot-path numbers
 * are clean.
 *
 * Three scenarios:
 * - **uncontendedSpike**: single thread tight loop — measures the per-thread
 *   cache hot path against the [PooledDirectAllocator] uncontended baseline
 *   for the same class. Direct C2 input.
 * - **contendedSameThread**: N threads, each thread does
 *   `allocate → writeByte → release` in its own thread → cached buffer comes
 *   back to the same thread next iteration. C1 best case + C3 scaling under
 *   no cross-thread sharing.
 * - **crossThreadRelease**: producer thread allocates and hands the buffer to
 *   a consumer queue; consumer thread releases. Buffer crosses threads on
 *   release → cache miss on producer's next alloc → central path stress.
 *   C1 worst case + C3 central-arena bottleneck check.
 *
 * Prints cache hit / cache miss / central hit / central miss per scenario so
 * the C1 number is directly readable.
 *
 * Re-run: remove `@Ignore`, then
 *   ./gradlew :keel-io:jvmTest --tests "*PerThreadCacheFrontSpikeBenchmark"
 */
@Ignore
class PerThreadCacheFrontSpikeBenchmark {

    @Test
    fun uncontendedSpike() {
        println(
            "=== per-thread-cache-front spike: uncontended single-thread allocate+release (JVM, $CLASS_SIZE-byte class) ===",
        )
        println("variant|ns/op|cacheHit|cacheMiss|centralHit|centralMiss")
        val ns = uncontendedTrial()
        println(
            "cachedFront|${"%.2f".format(
                ns.nsPerOp,
            )}|${ns.cacheHit}|${ns.cacheMiss}|${ns.centralHit}|${ns.centralMiss}",
        )
        println("blackhole=$blackhole")
    }

    @Test
    fun contendedSameThread() {
        println("=== per-thread-cache-front spike: contended same-thread allocate+release ($CLASS_SIZE-byte class) ===")
        println("threads|ns/op|Mops/sec|cacheHit|cacheMiss|centralHit|centralMiss|exceptions")
        for (n in THREAD_COUNTS) {
            val r = sameThreadTrial(n)
            println(formatContended(n, r))
        }
        println("blackhole=$blackhole")
    }

    @Test
    fun crossThreadRelease() {
        println("=== per-thread-cache-front spike: cross-thread release (alloc on producer, release on consumer) ===")
        println("threads|ns/op|Mops/sec|cacheHit|cacheMiss|centralHit|centralMiss|exceptions")
        for (n in THREAD_COUNTS) {
            val r = crossThreadTrial(n)
            println(formatContended(n, r))
        }
        println("blackhole=$blackhole")
    }

    private fun uncontendedTrial(): TrialResult {
        val spike = PerThreadCacheFrontSpike()
        try {
            repeat(WARMUP_ITERS) {
                val buf = spike.allocate(CLASS_SIZE)
                buf.writeByte(0)
                buf.release()
            }
            val samples = DoubleArray(SAMPLES)
            val baselineHit = spike.cacheHits.get()
            val baselineMiss = spike.cacheMisses.get()
            val baselineCHit = spike.centralHits.get()
            val baselineCMiss = spike.centralMisses.get()
            for (t in 0 until SAMPLES) {
                val start = System.nanoTime()
                var i = 0
                while (i < TRIAL_ITERS) {
                    val buf = spike.allocate(CLASS_SIZE)
                    buf.writeByte(0)
                    buf.release()
                    i++
                }
                samples[t] = (System.nanoTime() - start).toDouble() / TRIAL_ITERS
                blackhole += i.toLong()
            }
            samples.sort()
            return TrialResult(
                wallNs = samples[SAMPLES / 2] * TRIAL_ITERS,
                totalOps = TRIAL_ITERS.toLong(),
                exceptions = 0,
                cacheHit = spike.cacheHits.get() - baselineHit,
                cacheMiss = spike.cacheMisses.get() - baselineMiss,
                centralHit = spike.centralHits.get() - baselineCHit,
                centralMiss = spike.centralMisses.get() - baselineCMiss,
                nsPerOp = samples[SAMPLES / 2],
            )
        } finally {
            spike.close()
        }
    }

    private fun sameThreadTrial(nThreads: Int): TrialResult {
        val spike = PerThreadCacheFrontSpike()
        try {
            repeat(WARMUP_ITERS) {
                val buf = spike.allocate(CLASS_SIZE)
                buf.writeByte(0)
                buf.release()
            }
            val baselineHit = spike.cacheHits.get()
            val baselineMiss = spike.cacheMisses.get()
            val baselineCHit = spike.centralHits.get()
            val baselineCMiss = spike.centralMisses.get()
            val perThreadOps = LongArray(nThreads)
            val exceptionCount = AtomicInteger(0)
            val threads = ArrayList<Thread>(nThreads)
            val start = System.nanoTime()
            for (t in 0 until nThreads) {
                val tid = t
                threads += Thread {
                    var ops = 0L
                    try {
                        var i = 0
                        while (i < ITERS_PER_THREAD) {
                            val buf = spike.allocate(CLASS_SIZE)
                            buf.writeByte(0)
                            buf.release()
                            ops++
                            i++
                        }
                    } catch (@Suppress("SwallowedException", "TooGenericExceptionCaught") t: Throwable) {
                        exceptionCount.incrementAndGet()
                    }
                    perThreadOps[tid] = ops
                }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join() }
            val wallNs = (System.nanoTime() - start).toDouble()
            var totalOps = 0L
            for (o in perThreadOps) totalOps += o
            blackhole += totalOps
            return TrialResult(
                wallNs = wallNs,
                totalOps = totalOps,
                exceptions = exceptionCount.get(),
                cacheHit = spike.cacheHits.get() - baselineHit,
                cacheMiss = spike.cacheMisses.get() - baselineMiss,
                centralHit = spike.centralHits.get() - baselineCHit,
                centralMiss = spike.centralMisses.get() - baselineCMiss,
                nsPerOp = if (totalOps > 0) wallNs / totalOps else 0.0,
            )
        } finally {
            runCatching { spike.close() }
        }
    }

    private fun crossThreadTrial(nThreads: Int): TrialResult {
        // N/2 producer threads alloc into a shared queue; N/2 consumer threads drain
        // and release. Buffers cross threads on release → producer's next allocate
        // must miss the per-thread cache and fall to the central path.
        val pairs = (nThreads / 2).coerceAtLeast(1)
        val spike = PerThreadCacheFrontSpike()
        try {
            repeat(WARMUP_ITERS) {
                val buf = spike.allocate(CLASS_SIZE)
                buf.writeByte(0)
                buf.release()
            }
            val baselineHit = spike.cacheHits.get()
            val baselineMiss = spike.cacheMisses.get()
            val baselineCHit = spike.centralHits.get()
            val baselineCMiss = spike.centralMisses.get()
            val perThreadOps = LongArray(pairs * 2)
            val exceptionCount = AtomicInteger(0)
            val threads = ArrayList<Thread>(pairs * 2)
            // One bounded MPMC handoff queue per producer-consumer pair to
            // keep buffer ownership predictable. ArrayBlockingQueue gives both
            // sides backpressure so neither runs ahead far enough to mask
            // contention behind a deep queue.
            val queues = Array(pairs) { java.util.concurrent.ArrayBlockingQueue<IoBuf>(QUEUE_CAP) }
            val start = System.nanoTime()
            for (p in 0 until pairs) {
                val pid = p
                threads += Thread {
                    var ops = 0L
                    try {
                        var i = 0
                        while (i < ITERS_PER_THREAD) {
                            val buf = spike.allocate(CLASS_SIZE)
                            buf.writeByte(0)
                            queues[pid].put(buf)
                            ops++
                            i++
                        }
                        // Sentinel: a buffer with refCount that signals "stop". We
                        // can't enqueue null, so use a magic capacity-0 buffer.
                        queues[pid].put(STOP_SENTINEL)
                    } catch (@Suppress("SwallowedException", "TooGenericExceptionCaught") t: Throwable) {
                        exceptionCount.incrementAndGet()
                    }
                    perThreadOps[pid * 2] = ops
                }
                threads += Thread {
                    var ops = 0L
                    try {
                        while (true) {
                            val buf = queues[pid].take()
                            if (buf === STOP_SENTINEL) break
                            buf.release()
                            ops++
                        }
                    } catch (@Suppress("SwallowedException", "TooGenericExceptionCaught") t: Throwable) {
                        exceptionCount.incrementAndGet()
                    }
                    perThreadOps[pid * 2 + 1] = ops
                }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join() }
            val wallNs = (System.nanoTime() - start).toDouble()
            // Count only alloc ops (allocs and releases are 1:1); use the
            // producer's op count to avoid double-counting.
            var allocOps = 0L
            for (p in 0 until pairs) allocOps += perThreadOps[p * 2]
            blackhole += allocOps
            return TrialResult(
                wallNs = wallNs,
                totalOps = allocOps,
                exceptions = exceptionCount.get(),
                cacheHit = spike.cacheHits.get() - baselineHit,
                cacheMiss = spike.cacheMisses.get() - baselineMiss,
                centralHit = spike.centralHits.get() - baselineCHit,
                centralMiss = spike.centralMisses.get() - baselineCMiss,
                nsPerOp = if (allocOps > 0) wallNs / allocOps else 0.0,
            )
        } finally {
            runCatching { spike.close() }
        }
    }

    private fun formatContended(threads: Int, r: TrialResult): String {
        val mops = if (r.wallNs > 0) r.totalOps / (r.wallNs / 1e9) / 1e6 else 0.0
        return "$threads|${"%.2f".format(
            r.nsPerOp,
        )}|${"%.2f".format(mops)}|${r.cacheHit}|${r.cacheMiss}|${r.centralHit}|${r.centralMiss}|${r.exceptions}"
    }

    private class TrialResult(
        val wallNs: Double,
        val totalOps: Long,
        val exceptions: Int,
        val cacheHit: Long,
        val cacheMiss: Long,
        val centralHit: Long,
        val centralMiss: Long,
        val nsPerOp: Double,
    )

    private companion object {
        @Volatile
        @JvmStatic
        var blackhole: Long = 0

        const val CLASS_SIZE = 8192
        const val PER_THREAD_CACHE_CAP = 64
        const val CENTRAL_CAP = 4096
        const val QUEUE_CAP = 256

        val THREAD_COUNTS = intArrayOf(2, 4, 8, 16)

        const val WARMUP_ITERS = 200_000
        const val TRIAL_ITERS = 2_000_000
        const val ITERS_PER_THREAD = 500_000
        const val SAMPLES = 5

        /** Sentinel buffer signalling "no more work" through the handoff queue. */
        val STOP_SENTINEL: IoBuf = DirectIoBuf(0)
    }
}

/**
 * Spike allocator: per-thread cache (lock-free) front + [MutexFreelist] central
 * back-end. Single fixed [classSize], `DirectIoBuf` backing. Goal is to fact-
 * check per-thread-cache-front scaling without bringing the full PooledAllocator ladder and
 * chunk arena machinery into the experiment.
 */
internal class PerThreadCacheFrontSpike(
    private val classSize: Int = 8192,
    private val perThreadCacheCap: Int = 64,
    private val centralCap: Int = 4096,
) : BufferAllocator {

    private val central: Freelist = MutexFreelist(centralCap)
    private val perThread = scopeLocal { PerThreadCache(perThreadCacheCap) }

    val cacheHits = AtomicLong()
    val cacheMisses = AtomicLong()
    val centralHits = AtomicLong()
    val centralMisses = AtomicLong()

    private val owner: IoBufOwner = PoolOwner { buf -> returnFor(buf) }

    @Suppress("IoBufLeak")
    override fun allocate(capacity: Int): IoBuf {
        require(capacity == classSize || capacity == 0) {
            "spike supports only classSize=$classSize or 0, got $capacity"
        }
        if (capacity == 0) {
            val empty = DirectIoBuf(0)
            (empty as AbstractIoBuf).owner = owner
            return empty
        }
        val cache = perThread.current()
        val cached = cache.pop()
        if (cached != null) {
            cacheHits.incrementAndGet()
            (cached as AbstractIoBuf).resetForReuse()
            cached.owner = owner
            return cached
        }
        cacheMisses.incrementAndGet()
        val fromCentral = central.pop()
        if (fromCentral != null) {
            centralHits.incrementAndGet()
            (fromCentral as AbstractIoBuf).resetForReuse()
            fromCentral.owner = owner
            return fromCentral
        }
        centralMisses.incrementAndGet()
        val fresh = DirectIoBuf(classSize)
        (fresh as AbstractIoBuf).owner = owner
        return fresh
    }

    override fun wrapBytes(bytes: ByteArray, offset: Int, length: Int): IoBuf? = null

    override fun slice(source: IoBuf, offset: Int, length: Int): IoBuf =
        sliceDefaultIoBuf(source, offset, length)

    override fun createChild(): BufferAllocator = this

    override fun close() {
        // Drain central; per-thread caches stay live to be GC'd with their
        // owning threads (matches the JVM ThreadLocal lifecycle).
        while (true) {
            val buf = central.pop() ?: break
            (buf as AbstractIoBuf).freeBacking()
        }
        central.close()
    }

    private fun returnFor(buf: IoBuf) {
        val cache = perThread.current()
        if (cache.push(buf)) return
        if (central.push(buf)) return
        (buf as AbstractIoBuf).freeBacking()
    }

    /**
     * Bounded LIFO per-thread cache. Single-threaded access by construction
     * (read/write only via [ScopeLocal.current] on the owning thread), so
     * no synchronisation is required.
     */
    private class PerThreadCache(private val cap: Int) {
        private val list = ArrayDeque<IoBuf>(cap)
        fun pop(): IoBuf? = if (list.isEmpty()) null else list.removeLast()
        fun push(buf: IoBuf): Boolean {
            if (list.size < cap) {
                list.addLast(buf)
                return true
            }
            return false
        }
    }
}
