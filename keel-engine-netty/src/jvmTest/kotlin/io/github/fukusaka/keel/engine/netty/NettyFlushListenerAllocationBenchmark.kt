package io.github.fukusaka.keel.engine.netty

import com.sun.management.ThreadMXBean
import io.netty.util.concurrent.DefaultPromise
import io.netty.util.concurrent.Future
import io.netty.util.concurrent.GenericFutureListener
import io.netty.util.concurrent.ImmediateEventExecutor
import java.lang.management.ManagementFactory
import kotlin.test.Ignore
import kotlin.test.Test

/**
 * Measures per-flush-cycle JVM allocation for [NettyIoTransport]'s pooled
 * `FlushCompletionListener` shape against the Kotlin lambda closure it
 * replaced in `flush()`'s `lastFuture.addListener { ... }` call.
 *
 * `FlushCompletionListener` itself is a `private inner class` of
 * [NettyIoTransport] (it needs `updatePendingBytes`, a `protected` member,
 * and is not meant to be a public surface) so this benchmark can't call the
 * production class directly. It instead reconstructs both variants'
 * allocation shape standalone, at the same fidelity
 * [PendingWriteSnapshotPoolAllocationBenchmark] used for the sibling
 * `ArrayList` pool (which likewise benchmarks the *pattern* the old code
 * used, not a still-live copy of the removed code):
 *
 * - **A (lambda baseline)**: a Kotlin lambda capturing 3 locals
 *   (`writes`/`totalBytes`/`callback`-equivalent), SAM-converted to
 *   [GenericFutureListener] — the shape `addListener { ... }` compiles to.
 * - **B (pooled)**: a free-list-backed class with mutable fields, borrowed
 *   and recycled per cycle — the shape `FlushCompletionListener` +
 *   `flushListenerPool` use in production.
 *
 * Both variants register against a real [DefaultPromise] (on
 * [ImmediateEventExecutor], which notifies listeners synchronously — the
 * same shape [io.netty.channel.ChannelFuture] uses on a fast/already-done
 * write) and complete it, exactly mirroring `flush()`'s
 * `lastFuture.addListener(...)`. A prior version of this benchmark
 * constructed the listener but never registered it anywhere observable;
 * escape analysis eliminated the allocation entirely (measured 0
 * bytes/cycle for both variants) — registering against a real `Promise`
 * makes the listener genuinely escape the local scope, as it does in
 * production.
 *
 * Not a unit test — runs as a `@Test` so it executes under the normal
 * `jvmTest` task; inspect stdout for the numbers. Does not assert.
 *
 * **Result (2026-07-12, JVM)**: A (lambda baseline) median 56 B/cycle, B
 * (pooled) median 32 B/cycle — 24 B/cycle saved (43% reduction). The
 * residual 32 B in B is `DefaultPromise`'s own per-`addListener` bookkeeping
 * (allocated regardless of listener shape), not further reducible from this
 * side of the API.
 */
// @Ignore: one-time measurement (no functional assertion) — a decision aid
// recording the allocation delta the pool was adopted for. The correctness
// properties (no aliasing under backpressure, no leaked completions) are
// pinned by NettyIoTransportFlushListenerPoolTest, which does assert and
// runs in the gate.
// Re-run: remove @Ignore, then
//   ./gradlew :keel-engine-netty:jvmTest --tests "*NettyFlushListenerAllocationBenchmark"
@Ignore
class NettyFlushListenerAllocationBenchmark {

    private val tmx = ManagementFactory.getThreadMXBean() as ThreadMXBean
    private val executor = ImmediateEventExecutor.INSTANCE

    private fun measure(iterations: Int, cycle: () -> Unit): Long {
        repeat(WARMUP) { cycle() }
        val start = tmx.getThreadAllocatedBytes(Thread.currentThread().threadId())
        repeat(iterations) { cycle() }
        val end = tmx.getThreadAllocatedBytes(Thread.currentThread().threadId())
        return (end - start) / iterations
    }

    private class PooledListener : GenericFutureListener<Future<Void>> {
        lateinit var writes: List<Int>
        var totalBytes: Int = 0
        var callback: (() -> Unit)? = null
        var pool: ArrayDeque<PooledListener>? = null

        override fun operationComplete(future: Future<Void>) {
            writes.size + totalBytes
            callback?.invoke()
            pool?.addLast(this)
        }
    }

    @Test
    fun `per-cycle allocation lambda baseline vs pooled`() {
        val writesSample = listOf(1, 2, 3)
        val callbackSample: () -> Unit = {}

        val trialsA = LongArray(TRIALS) {
            measure(ITERS) {
                val promise = DefaultPromise<Void>(executor)
                val writes = writesSample
                val totalBytes = 6
                val callback = callbackSample
                promise.addListener(
                    GenericFutureListener<Future<in Void>> { _ ->
                        writes.size + totalBytes
                        callback.invoke()
                    },
                )
                promise.setSuccess(null)
            }
        }

        val pool = ArrayDeque<PooledListener>()
        val trialsB = LongArray(TRIALS) {
            measure(ITERS) {
                val promise = DefaultPromise<Void>(executor)
                val listener = pool.removeLastOrNull() ?: PooledListener()
                listener.writes = writesSample
                listener.totalBytes = 6
                listener.callback = callbackSample
                listener.pool = pool
                promise.addListener(listener)
                promise.setSuccess(null)
            }
        }

        trialsA.sort()
        trialsB.sort()
        val medA = trialsA[TRIALS / 2]
        val medB = trialsB[TRIALS / 2]

        println("=== NettyFlushListener allocation (bytes / cycle, iters=$ITERS × $TRIALS trials) ===")
        println("  A (lambda closure, baseline)  median=$medA bytes  samples=${trialsA.toList()}")
        println("  B (pooled listener)           median=$medB bytes  samples=${trialsB.toList()}")
        println("  Δ (A-B)                       ${medA - medB} bytes / cycle saved by pooling")
    }

    companion object {
        private const val WARMUP = 2000
        private const val ITERS = 10_000
        private const val TRIALS = 5
    }
}
