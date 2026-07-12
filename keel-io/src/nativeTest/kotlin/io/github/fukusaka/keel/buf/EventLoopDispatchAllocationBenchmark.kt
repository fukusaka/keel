package io.github.fukusaka.keel.buf

import kotlin.concurrent.AtomicReference
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.time.TimeSource
import kotlinx.coroutines.Runnable

/**
 * Decision record for whether a pooled/typed dispatch task design would
 * reduce per-`dispatch()` allocation cost on the POSIX Native EventLoops.
 * Every POSIX Native engine (epoll/kqueue/io_uring) independently
 * reimplements the same
 * shape: a `taskQueue = MpscQueue<Runnable>()` field, and every call site
 * (`Runnable { onWorkerAccept(clientFd, workerLoop, listener) }` per accept,
 * `Runnable { submitAddOrModifyEpoll(fd, events) }` per fd (re)registration,
 * per-`awaitPendingFlush` registration, io_uring's per-suspended-op
 * cancellation/prep Runnables, etc.) constructs a fresh closure per
 * `dispatch()` call. No Native allocation-rate measurement API exists on
 * Kotlin/Native (confirmed: `kotlin.native.runtime.GCInfo`/`MemoryUsage`
 * expose only *retained-after-sweep* heap bytes, not a cumulative
 * mutator-allocation counter — no JVM `ThreadMXBean`-equivalent). This
 * benchmark instead uses wall-clock timing (same technique as
 * [InterfaceDispatchProbeBenchmark]) to compare dispatch cost, on the
 * premise that allocation pressure on Kotlin/Native's GC dominates the time
 * cost of these tiny per-call operations, so a time delta between an
 * allocating and a non-allocating variant is a meaningful proxy.
 *
 * Three variants, isolating **which layer** contributes cost — the two
 * candidate designs from the backlog item's "candidate (a)" (typed task
 * class + per-kind pool) are not equivalent in how much of the pipeline
 * they de-allocate:
 *
 * - **A (current production shape)**: `Runnable { ... }` closure capturing
 *   2 `Int` fields, offered to `MpscQueue<Runnable>` (which itself always
 *   allocates a `Node<T>` wrapper per [MpscQueue.offer]), drained, run.
 * - **B (pooled typed task, `MpscQueue<T>` kept)**: a mutable `DispatchTask`
 *   with settable fields, borrowed from a free-list instead of constructed
 *   fresh, offered to `MpscQueue<DispatchTask>` — removes the closure
 *   allocation but still pays `MpscQueue`'s own `Node<T>` wrapper allocation
 *   on every [MpscQueue.offer].
 * - **C (pooled intrusive task, no `Node<T>` wrapper)**: a mutable task that
 *   *is* the linked-list node (`next` field baked in), pushed/popped via a
 *   minimal CAS-loop MPSC mirroring [MpscQueue]'s own algorithm but
 *   operating on `T` directly — removes both the closure and the wrapper
 *   allocation. This is a bespoke probe (not a change to [MpscQueue]
 *   itself); a real implementation would need this shape built into the
 *   engine's own dispatch queue.
 *
 * Not a unit test — runs as a `@Test` so it executes under the normal
 * native test tasks; inspect stdout for the numbers. Does not assert.
 *
 * **Result (Kotlin/Native 2.3.20, `-opt` release-equivalent, median of 7
 * samples, 2M iterations/sample, single-threaded offer+drain+run cycle)**:
 *
 * | | A (closure, current) | B (pooled task, `Node<T>` kept) | C (pooled intrusive) |
 * |---|---:|---:|---:|
 * | macOS arm64 | 45.13 ns | 50.80 ns (**+13%**) | 43.55 ns (-3.5%) |
 * | Linux x86_64 (2 runs) | 27.80–29.51 ns | 32.34–36.81 ns (**+16–25%**) | 30.20–34.05 ns (**+9–15%**) |
 *
 * **Pooling makes dispatch *slower*, not faster, on both targets — the
 * backlog item's premise does not survive measurement.** Reproduced across
 * 2 independent runs on Linux (same direction and rough magnitude both
 * times). On Linux — the platform epoll and io_uring actually run on — even
 * variant C (fully allocation-free: no closure, no `Node<T>` wrapper) is
 * *slower* than the current allocating baseline. The `ArrayDeque`
 * free-list's own bookkeeping cost (`removeLastOrNull`/`addLast`, bounds
 * checks, backing-array management) exceeds what it saves by skipping a
 * tiny (2-`Int`-field) short-lived allocation on Kotlin/Native's current
 * allocator — consistent with a fast bump-pointer/thread-local young-gen
 * allocator where short-lived allocation is already cheap relative to any
 * pool-management indirection.
 *
 * **This is a signal against the backlog premise, not a closing verdict.**
 * Per `design-principles.md`'s "パフォーマンス改善の検証", a microbenchmark
 * informs a design decision but does not by itself decide it — this probe
 * uses a synthetic 2-`Int`-field closure, lighter than the production call
 * sites (which capture `fd` plus object references — see the class KDoc's
 * call-site enumeration) and a single-threaded steady-state loop, not a
 * production access pattern. Re-measure with a realistic closure shape
 * and/or a production workload before treating the pooled/typed dispatch
 * task design as settled either way.
 */
// Re-run: remove @Ignore, then
//   ./gradlew :keel-io:macosArm64Test --tests "*EventLoopDispatchAllocationBenchmark"
//   ./gradlew :keel-io:linuxX64Test   --tests "*EventLoopDispatchAllocationBenchmark"
@Ignore
class EventLoopDispatchAllocationBenchmark {

    @Test
    fun `dispatch cost across closure and pooled-task shapes`() {
        println("=== EventLoop dispatch cost probe (Native, release, ns/cycle) ===")
        println("A closure + MpscQueue<Runnable> (current)|${fmt(closureTrial())}")
        println("B pooled DispatchTask + MpscQueue<DispatchTask>|${fmt(pooledTaskTrial())}")
        println("C pooled intrusive task, no Node<T> wrapper|${fmt(intrusiveTrial())}")
        println("blackhole=$blackhole")
    }

    private var blackhole = 0

    // --- Variant A: current production shape ---

    private val queueA = MpscQueue<Runnable>()
    private val drainA = mutableListOf<Runnable>()

    private fun closureTrial(): Double = measure {
        queueA.offer(Runnable { blackhole += fdVar + ctxVar })
        queueA.drain(drainA)
        for (i in drainA.indices) drainA[i].run()
        drainA.clear()
    }

    // --- Variant B: pooled typed task, MpscQueue<T> kept ---

    private class DispatchTask {
        var fd: Int = 0
        var ctx: Int = 0
    }

    private val queueB = MpscQueue<DispatchTask>()
    private val drainB = mutableListOf<DispatchTask>()
    private val poolB = ArrayDeque<DispatchTask>()

    private fun pooledTaskTrial(): Double = measure {
        val task = poolB.removeLastOrNull() ?: DispatchTask()
        task.fd = fdVar
        task.ctx = ctxVar
        queueB.offer(task)
        queueB.drain(drainB)
        for (i in drainB.indices) {
            val t = drainB[i]
            blackhole += t.fd + t.ctx
            poolB.addLast(t)
        }
        drainB.clear()
    }

    // --- Variant C: pooled intrusive task (task IS the linked-list node) ---

    private class IntrusiveTask {
        var fd: Int = 0
        var ctx: Int = 0
        var next: IntrusiveTask? = null
    }

    private val headC = AtomicReference<IntrusiveTask?>(null)
    private val poolC = ArrayDeque<IntrusiveTask>()

    /** Mirrors [MpscQueue.offer]'s CAS loop, operating on `T` directly (no `Node<T>` wrapper). */
    private fun offerC(item: IntrusiveTask) {
        while (true) {
            val cur = headC.value
            item.next = cur
            if (headC.compareAndSet(cur, item)) return
        }
    }

    /** Mirrors [MpscQueue.drain]'s reversal, operating on `T` directly. */
    private fun drainC(out: MutableList<IntrusiveTask>) {
        val h = headC.getAndSet(null) ?: return
        var node: IntrusiveTask? = h
        var reversed: IntrusiveTask? = null
        while (node != null) {
            val next = node.next
            node.next = reversed
            reversed = node
            node = next
        }
        var cur = reversed
        while (cur != null) {
            out.add(cur)
            cur = cur.next
        }
    }

    private val drainOutC = mutableListOf<IntrusiveTask>()

    private fun intrusiveTrial(): Double = measure {
        val task = poolC.removeLastOrNull() ?: IntrusiveTask()
        task.fd = fdVar
        task.ctx = ctxVar
        offerC(task)
        drainC(drainOutC)
        for (i in drainOutC.indices) {
            val t = drainOutC[i]
            blackhole += t.fd + t.ctx
            poolC.addLast(t)
        }
        drainOutC.clear()
    }

    // --- shared harness ---

    // Vary per-iteration to prevent the compiler from constant-folding the
    // closure capture / field writes away.
    private var fdVar = 0
    private var ctxVar = 0

    private inline fun measure(op: () -> Unit): Double {
        var w = 0
        while (w < WARMUP_ITERS) {
            fdVar = w
            ctxVar = w xor 1
            op()
            w++
        }
        val samples = DoubleArray(SAMPLES)
        for (t in 0 until SAMPLES) {
            val mark = TimeSource.Monotonic.markNow()
            var i = 0
            while (i < TRIAL_ITERS) {
                fdVar = i
                ctxVar = i xor 1
                op()
                i++
            }
            samples[t] = mark.elapsedNow().inWholeNanoseconds.toDouble() / TRIAL_ITERS
        }
        samples.sort()
        return samples[SAMPLES / 2]
    }

    private fun fmt(v: Double): String {
        val scaled = (v * 100).toLong()
        return "${scaled / 100}.${(scaled % 100).toString().padStart(2, '0')}"
    }

    private companion object {
        const val WARMUP_ITERS = 200_000
        const val TRIAL_ITERS = 2_000_000
        const val SAMPLES = 7
    }
}
