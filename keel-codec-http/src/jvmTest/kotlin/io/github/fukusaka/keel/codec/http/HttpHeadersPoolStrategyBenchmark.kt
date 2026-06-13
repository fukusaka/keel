package io.github.fukusaka.keel.codec.http

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.test.Ignore
import kotlin.test.Test

/**
 * Compares candidate fixes for the [HttpHeadersPool] thread-safety bug
 * (the global non-thread-safe `ArrayDeque` crashes under concurrent
 * `borrow` from multiple NIO worker EventLoop threads).
 *
 * Models the worker-thread borrow/release pattern: N threads each loop
 * `borrow -> touch -> giveBack` for a fixed wall-clock window, mirroring
 * how each EventLoop thread cycles `HttpHeaders` instances across the
 * requests it serves. Measures, per strategy, at thread counts that
 * span a 32-core box:
 *
 * - **throughput**: total borrow/release cycles per second (higher is
 *   better) — exposes lock / CAS contention at high thread counts.
 * - **allocation**: bytes allocated per cycle, summed across worker
 *   threads via [ThreadMXBean.getThreadAllocatedBytes] (lower is
 *   better) — answers "does this strategy retain the pool's
 *   alloc-reduction benefit?".
 * - **correctness**: a run that completes without
 *   `ArrayIndexOutOfBoundsException` is correct; the current global
 *   `ArrayDeque` strategy is expected to crash above 1 thread (see
 *   [`unsafe global ArrayDeque crashes under concurrency`]).
 *
 * Strategies:
 * - **NoPool**: allocate a fresh [HttpHeaders] each borrow, drop on
 *   release. Baseline alloc, zero contention, trivially correct.
 * - **GlobalSync**: one `ArrayDeque` guarded by a lock. Cross-thread
 *   amortization, correct, but every cycle contends the lock.
 * - **GlobalLockFree**: one [ConcurrentLinkedDeque]. Cross-thread
 *   amortization, correct, CAS contention instead of a lock.
 * - **ThreadLocal**: a per-thread `ArrayDeque`. Per-thread (==
 *   per-EventLoop, since keel confines each EL to one thread)
 *   amortization, zero contention, correct. Retains the benefit iff
 *   each thread cycles enough instances to warm its local deque.
 */
// @Ignore: one-time measurement (no functional assertion) — a decision
// aid that caught no regression, so it is not run in the gate / CI; kept
// for re-verification. The verified content + conclusion is the class
// KDoc above.
// Re-run: remove @Ignore, then
//   ./gradlew :keel-codec-http:jvmTest --tests "*HttpHeadersPoolStrategyBenchmark"
@Ignore
class HttpHeadersPoolStrategyBenchmark {

    private val tmx = ManagementFactory.getThreadMXBean() as ThreadMXBean

    /** Borrow/giveBack abstraction over the candidate pool strategies. */
    private interface Strategy {
        val name: String
        fun borrow(): HttpHeaders
        fun giveBack(h: HttpHeaders)
    }

    private object NoPool : Strategy {
        override val name = "NoPool"
        override fun borrow(): HttpHeaders = HttpHeaders()
        override fun giveBack(h: HttpHeaders) { /* drop */ }
    }

    private class GlobalSync : Strategy {
        override val name = "GlobalSync"
        private val lock = Any()
        private val stack = ArrayDeque<HttpHeaders>()
        override fun borrow(): HttpHeaders =
            synchronized(lock) { if (stack.isEmpty()) null else stack.removeLast() }
                ?: HttpHeaders()
        override fun giveBack(h: HttpHeaders) {
            h.resetForReuse()
            synchronized(lock) { if (stack.size < MAX_POOLED) stack.addLast(h) }
        }
    }

    private class GlobalLockFree : Strategy {
        override val name = "GlobalLockFree"
        private val deque = ConcurrentLinkedDeque<HttpHeaders>()
        private val size = AtomicLong(0)
        override fun borrow(): HttpHeaders {
            val h = deque.pollLast()
            if (h != null) {
                size.decrementAndGet()
                return h
            }
            return HttpHeaders()
        }
        override fun giveBack(h: HttpHeaders) {
            h.resetForReuse()
            if (size.get() < MAX_POOLED) {
                deque.addLast(h)
                size.incrementAndGet()
            }
        }
    }

    private class ThreadLocalPool : Strategy {
        override val name = "ThreadLocal"
        private val local = ThreadLocal.withInitial { ArrayDeque<HttpHeaders>() }
        override fun borrow(): HttpHeaders {
            val stack = local.get()
            return if (stack.isEmpty()) HttpHeaders() else stack.removeLast()
        }
        override fun giveBack(h: HttpHeaders) {
            h.resetForReuse()
            val stack = local.get()
            if (stack.size < MAX_POOLED) stack.addLast(h)
        }
    }

    /**
     * Drives [threads] worker threads through `borrow -> touch ->
     * giveBack` cycles for [DURATION_MS], returns total cycles and
     * total bytes allocated across the worker threads.
     */
    private fun runStrategy(strategy: Strategy, threads: Int): Pair<Long, Long> {
        val start = CountDownLatch(1)
        val ready = CountDownLatch(threads)
        val totalCycles = AtomicLong(0)
        val totalAlloc = AtomicLong(0)
        val workers = (0 until threads).map { i ->
            thread(name = "pool-bench-$i") {
                val tid = Thread.currentThread().threadId()
                ready.countDown()
                start.await()
                val allocStart = tmx.getThreadAllocatedBytes(tid)
                var cycles = 0L
                val deadline = System.nanoTime() + DURATION_MS * 1_000_000L
                while (System.nanoTime() < deadline) {
                    repeat(BATCH) {
                        val h = strategy.borrow()
                        // Touch: a handful of adds, mirroring a small
                        // header set, so the reuse actually exercises
                        // the backing arrays.
                        h.add("Connection", "keep-alive")
                        h.add("Content-Length", "13")
                        strategy.giveBack(h)
                    }
                    cycles += BATCH
                }
                val allocEnd = tmx.getThreadAllocatedBytes(tid)
                totalCycles.addAndGet(cycles)
                totalAlloc.addAndGet(allocEnd - allocStart)
            }
        }
        ready.await()
        start.countDown()
        workers.forEach { it.join() }
        return totalCycles.get() to totalAlloc.get()
    }

    @Test
    fun `pool strategy throughput and alloc across thread counts`() {
        val strategies = listOf(NoPool, GlobalSync(), GlobalLockFree(), ThreadLocalPool())
        val threadCounts = intArrayOf(1, 2, 4, 8, 16, 32)

        // Warmup all strategies at a low thread count to trigger JIT.
        for (s in strategies) runStrategy(s, 2)

        println("=== HttpHeadersPool strategy comparison (duration=${DURATION_MS}ms/run) ===")
        println("  throughput = million cycles/sec (higher better)")
        println("  alloc      = bytes/cycle (lower better)")
        println()
        printf("%-15s", "strategy")
        for (t in threadCounts) printf("  %10s", "${t}t")
        println()
        for (s in strategies) {
            printf("%-15s", "${s.name} Mc/s")
            for (t in threadCounts) {
                val (cycles, _) = runStrategy(s, t)
                val mcps = cycles.toDouble() / (DURATION_MS / 1000.0) / 1_000_000.0
                printf("  %10.2f", mcps)
            }
            println()
            printf("%-15s", "  └ B/cycle")
            for (t in threadCounts) {
                val (cycles, alloc) = runStrategy(s, t)
                val bpc = if (cycles > 0) alloc.toDouble() / cycles else 0.0
                printf("  %10.1f", bpc)
            }
            println()
        }
    }

    private fun printf(fmt: String, vararg args: Any) {
        print(fmt.format(*args))
    }

    companion object {
        private const val MAX_POOLED = 64
        private const val DURATION_MS = 1_000L
        private const val BATCH = 64
    }
}
