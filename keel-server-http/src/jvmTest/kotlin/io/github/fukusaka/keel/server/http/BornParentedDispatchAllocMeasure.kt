package io.github.fukusaka.keel.server.http

import com.sun.management.ThreadMXBean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.lang.management.ManagementFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression guard for the born-parented inline dispatch technique used by
 * [HttpServerHandler.onRequestHead]: running a synchronously-completing
 * `suspend` body via `startCoroutineUninterceptedOrReturn` (with a completion
 * carrying the connection scope's context) allocates materially less than
 * dispatching it through `CoroutineScope.launch` on a real task-dispatching
 * (EventLoop-style) dispatcher.
 *
 * A JFR allocation-by-site profile of `server-http-nio` `/hello` on the real
 * NIO EventLoop dispatcher measured the `launch` path allocating a
 * `StandaloneCoroutine` + `DispatchedContinuation` + `ChildHandleNode` + the
 * EventLoop task-queue node that the born-parented path avoids (~111 B/req). The
 * `Dispatchers.Unconfined` harness [HttpServerHandlerFixture] uses runs inline and
 * hides that dispatch-task cost, so this guard uses a single-thread executor
 * dispatcher — which enqueues a real dispatch task like an EventLoop — and reads
 * allocation on that thread with the extended [ThreadMXBean]. Driver and
 * machinery share one thread so a single reading captures the launch-side cost.
 *
 * `*Measure`: an invariant assertion (born-parented allocates substantially less
 * than launch), not a throughput comparison. Runs in the gate; well under a
 * second.
 */
class BornParentedDispatchAllocMeasure {

    private val threadMx = ManagementFactory.getThreadMXBean() as ThreadMXBean

    /** A `suspend` body that completes without ever suspending (the /hello shape). */
    private val syncBody: suspend () -> Unit = { /* returns immediately */ }

    private class NoopCompletion(override val context: CoroutineContext) : Continuation<Unit> {
        override fun resumeWith(result: Result<Unit>) = Unit
    }

    /**
     * Runs `warmup` then `iterations` dispatches of [syncBody] on a single
     * executor thread and returns the bytes that thread allocated over the
     * measured loop. `useLaunch` picks the strategy; both run on the same thread
     * so the launch dispatch-task allocation is captured (the launched empty
     * bodies queue behind the driver and are dropped on shutdown — only the
     * launch-side allocation is measured).
     */
    private fun measure(useLaunch: Boolean, warmup: Int, iterations: Int): Long {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val dispatcher = executor.asCoroutineDispatcher()
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val result = CompletableFuture<Long>()
            executor.execute {
                try {
                    val tid = Thread.currentThread().threadId()
                    fun once() {
                        if (useLaunch) {
                            scope.launch(dispatcher) { syncBody() }
                        } else {
                            syncBody.startCoroutineUninterceptedOrReturn(NoopCompletion(scope.coroutineContext))
                        }
                    }
                    repeat(warmup) { once() }
                    val before = threadMx.getThreadAllocatedBytes(tid)
                    repeat(iterations) { once() }
                    val after = threadMx.getThreadAllocatedBytes(tid)
                    result.complete(after - before)
                } catch (t: Throwable) {
                    result.completeExceptionally(t)
                }
            }
            val bytes = result.get()
            scope.coroutineContext[Job]!!.cancel()
            return bytes
        } finally {
            executor.shutdownNow()
        }
    }

    // NOTE: the L4-big SuspendLambda hoist (reusing one per-connection dispatch
    // body vs a fresh per-request capturing lambda) is NOT guarded by a microbench
    // here: a synthetic fresh-lambda loop is defeated by JIT escape analysis
    // (the un-escaping lambda is scalarised away, measuring ~0 B/op), so it cannot
    // reliably show the reduction. The real evidence is the JFR allocation-by-site
    // profile of server-http-nio /hello, where the per-request SuspendLambda drops
    // from `HttpServerHandler$onRequestHead$1` 46 B/req to `$dispatchBody$1` 23 B/req
    // (only the unavoidable state-machine copy remains) — see
    // benchmark/results-summary/2026-07-14-l4big-alloc-real-el-dispatcher.md.

    @Test
    fun `born-parented dispatch allocates substantially less than launch for a sync body`() {
        val warmup = 20_000
        val iterations = 200_000

        val launchBytes = measure(useLaunch = true, warmup, iterations)
        val bornBytes = measure(useLaunch = false, warmup, iterations)

        val launchPerOp = launchBytes.toDouble() / iterations
        val bornPerOp = bornBytes.toDouble() / iterations
        println(
            "[born-parented alloc] launch=${"%.1f".format(launchPerOp)} B/op, " +
                "born-parented=${"%.1f".format(bornPerOp)} B/op, saved=${"%.1f".format(launchPerOp - bornPerOp)} B/op",
        )

        // Robust bound: launch pays a StandaloneCoroutine + DispatchedContinuation
        // + dispatch-task-queue node per op; born-parented pays none of those. The
        // real gap is large (launch ~100+ B/op vs born-parented near-zero), so a
        // conservative "born-parented < half of launch, and launch clearly nonzero"
        // catches a regression without being brittle to JIT/allocator noise.
        assertTrue(
            launchPerOp > 40.0,
            "sanity: launch should allocate the coroutine machinery per op, got $launchPerOp B/op",
        )
        assertTrue(
            bornPerOp < launchPerOp * 0.5,
            "born-parented ($bornPerOp B/op) must allocate well under half of launch ($launchPerOp B/op)",
        )
    }
}
