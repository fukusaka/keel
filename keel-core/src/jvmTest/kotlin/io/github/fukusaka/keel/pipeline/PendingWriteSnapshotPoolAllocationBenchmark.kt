package io.github.fukusaka.keel.pipeline

import com.sun.management.ThreadMXBean
import io.github.fukusaka.keel.buf.EmptyIoBuf
import io.github.fukusaka.keel.pipeline.AbstractIoTransport.PendingWrite
import java.lang.management.ManagementFactory
import kotlin.test.Ignore
import kotlin.test.Test

/**
 * Measures per-flush-cycle JVM allocation for [PendingWriteSnapshotPool]
 * against the plain `ArrayList(pendingWrites)` snapshot it replaces in
 * `NettyIoTransport.flush()` (and the equivalent io_uring paths).
 *
 * Two variants compared in the same JVM, each modeling one
 * borrow-populate-recycle cycle at a steady-state depth of 3 pending
 * writes (a representative small batch):
 *
 * - **A (baseline)**: `ArrayList(pendingWrites)` allocated fresh every
 *   cycle, as the pre-pool code did.
 * - **B (pooled)**: [PendingWriteSnapshotPool.borrow] / `.recycle()`.
 *
 * Not a unit test — runs as a `@Test` so it executes under the normal
 * `jvmTest` task; inspect stdout for the numbers. Does not assert.
 *
 * **Result (2026-07-12, JVM, depth=3 pending writes)**: A (baseline)
 * median 88 B/cycle, B (pooled) median 32 B/cycle — 56 B/cycle saved
 * (64% reduction). The residual 32 B in B is `ArrayDeque.iterator()`
 * allocated by `addAll(source)` inside [PendingWriteSnapshotPool.borrow],
 * not further reducible without changing the source collection's shape.
 */
// @Ignore: one-time measurement (no functional assertion) — a decision
// aid recording the allocation delta the pool was adopted for. The
// correctness properties (no aliasing under backpressure) are pinned by
// PendingWriteSnapshotPoolTest, which does assert and runs in the gate.
// Re-run: remove @Ignore, then
//   ./gradlew :keel-core:jvmTest --tests "*PendingWriteSnapshotPoolAllocationBenchmark"
@Ignore
class PendingWriteSnapshotPoolAllocationBenchmark {

    private val tmx = ManagementFactory.getThreadMXBean() as ThreadMXBean

    private fun source(): ArrayDeque<PendingWrite> =
        ArrayDeque(
            listOf(PendingWrite(EmptyIoBuf, 0, 1), PendingWrite(EmptyIoBuf, 0, 2), PendingWrite(EmptyIoBuf, 0, 3)),
        )

    private fun measure(iterations: Int, cycle: () -> Unit): Long {
        repeat(WARMUP) { cycle() }
        val start = tmx.getThreadAllocatedBytes(Thread.currentThread().threadId())
        repeat(iterations) { cycle() }
        val end = tmx.getThreadAllocatedBytes(Thread.currentThread().threadId())
        return (end - start) / iterations
    }

    @Test
    fun `per-cycle allocation baseline vs pooled`() {
        val src = source()
        val trialsA = LongArray(TRIALS) {
            measure(ITERS) {
                @Suppress("UNUSED_VARIABLE")
                val writes = ArrayList(src)
            }
        }
        val pool = PendingWriteSnapshotPool()
        val trialsB = LongArray(TRIALS) {
            measure(ITERS) {
                val writes = pool.borrow(src)
                pool.recycle(writes)
            }
        }
        trialsA.sort()
        trialsB.sort()
        val medA = trialsA[TRIALS / 2]
        val medB = trialsB[TRIALS / 2]

        println("=== PendingWriteSnapshotPool allocation (bytes / cycle, depth=3, iters=$ITERS × $TRIALS trials) ===")
        println("  A (fresh ArrayList, baseline)  median=$medA bytes  samples=${trialsA.toList()}")
        println("  B (pooled)                     median=$medB bytes  samples=${trialsB.toList()}")
        println("  Δ (A-B)                        ${medA - medB} bytes / cycle saved by pooling")
    }

    companion object {
        private const val WARMUP = 2000
        private const val ITERS = 10_000
        private const val TRIALS = 5
    }
}
