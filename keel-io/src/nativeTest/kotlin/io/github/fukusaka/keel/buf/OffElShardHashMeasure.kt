@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package io.github.fukusaka.keel.buf

import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Measures how evenly off-EventLoop threads spread across shards under the old raw
 * selector (`id.toInt() and mask`) vs the new [mixShardKey] selector.
 *
 * [currentThreadId] is a pthread id — an address on Native — whose low bits are
 * alignment-skewed, so masking it directly can cluster off-EventLoop carves onto a
 * few shards. This spawns real threads (all alive at once, so each has a distinct
 * pthread address), collects their ids, and asserts the mixed selector distributes
 * them roughly uniformly. The raw selector is reported for comparison only — it is
 * platform-dependent, so whether it is actually skewed is observed, not asserted.
 */
class OffElShardHashMeasure {

    @Test
    fun `mixShardKey spreads real thread ids roughly uniformly across shards`() {
        val threadCount = 256
        val shardCount = 32
        val mask = shardCount - 1

        val workers = List(threadCount) { Worker.start() }
        val ids: List<Long> = try {
            workers
                .map { it.execute(TransferMode.SAFE, {}) { currentThreadId() } }
                .map { it.result }
        } finally {
            workers.forEach { it.requestTermination().result }
        }

        val rawHist = IntArray(shardCount)
        val mixedHist = IntArray(shardCount)
        for (id in ids) {
            rawHist[id.toInt() and mask]++
            mixedHist[mixShardKey(id) and mask]++
        }

        val mean = threadCount.toDouble() / shardCount
        println("OffElShardHash: $threadCount real thread ids over $shardCount shards (mean $mean)")
        println("  raw   max=${rawHist.max()} min=${rawHist.min()} empty=${rawHist.count { it == 0 }}")
        println("  mixed max=${mixedHist.max()} min=${mixedHist.min()} empty=${mixedHist.count { it == 0 }}")

        // The mixed selector should keep the busiest shard within a generous multiple
        // of the mean (uniform-with-noise tolerance). Poisson(mean=8) tails well under
        // 3x; a skewed selector that clusters would blow far past it.
        assertTrue(
            mixedHist.max() <= mean * 3,
            "mixed selector busiest shard ${mixedHist.max()} exceeds 3x mean $mean: ${mixedHist.toList()}",
        )
    }
}
