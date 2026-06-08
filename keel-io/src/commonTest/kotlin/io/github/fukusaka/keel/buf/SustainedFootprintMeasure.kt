package io.github.fukusaka.keel.buf

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Measurement (not a strict contract test) asking the one question that decides
 * whether Phase 6's soft cap is needed: under a *sustained high-concurrency* 8 KiB
 * working set with churn, does the resident chunk footprint stay bounded by the
 * working set, or does it over-retain (grow beyond the minimum and not come back)?
 *
 * This is the representable measurement for keel's *actual* workload. keel's read
 * loop allocates a fixed `readBufferSize` (default 8 KiB) per read, so real traffic
 * is 8 KiB-dominated by construction — a cross-class *fragmentation* measurement
 * cannot be representative (there is no multi-class workload to drive it), but a
 * *total-bytes* measurement on the 8 KiB class is. If Phase 5's idle-chunk reclaim
 * already bounds the footprint here, a soft cap is premature for this workload.
 *
 * The rolling live set holds N buffers at all times (allocate a new one into a
 * randomly chosen slot after releasing its previous occupant), simulating N
 * concurrent connections each holding a read buffer. Trim runs periodically as in
 * production.
 *
 * Findings (the evidence that deferred a soft cap / usage-ring — Phase 6):
 * 1. **Steady-state churn is bounded** (resident ≈ working-set minimum, ×1.0) — the
 *    Phase 5 reclaim already bounds the footprint of the actual 8 KiB workload.
 * 2. **A one-shot in-place load drop does NOT recover** (resident stays at the peak):
 *    surviving buffers are pinned in place (the cache recycles the same buffer) and
 *    keel does no compaction, so reclaim — which needs a fully-idle chunk — frees
 *    nothing while live carves are scattered. A soft cap cannot help (it cannot evict
 *    live buffers); only compaction could, and that is incompatible with keel's
 *    zero-copy pointer stability.
 * 3. **Connection turnover recovers fully** (resident → working-set minimum): once the
 *    survivors are released and re-carved, the existing first-fit-from-oldest carve
 *    re-packs them into the lowest chunks, so the higher chunks drain and reclaim
 *    recovers — without a usage-ring. first-fit already supplies the locality the
 *    Netty `PoolChunkList` ring would; its marginal benefit here is unproven.
 *
 * Numbers are printed; the assertions only pin the two robust invariants (steady
 * churn stays bounded, a full release drains to the warm reserve). The one-shot-drop
 * and turnover numbers are reported, not asserted — they are the Phase 6 evidence.
 */
class SustainedFootprintMeasure {
    private fun pooled(): PooledAllocator = createPoolAllocator() as PooledAllocator

    private companion object {
        const val CLASS = 8192
        const val LIVE = 256 // 256 × 8 KiB = 2 MiB live working set
        const val CHURN_CYCLES = LIVE * 50 // many alloc/release cycles at steady occupancy
        const val SEED = 42
    }

    @Test
    fun `footprint - sustained 8K churn then drop turnover and drain`() {
        if (!isPoolAllocator()) return
        val a = pooled()
        val chunkKiB = PooledAllocator.CHUNK_SIZE / 1024
        val minChunks = (LIVE * CLASS + PooledAllocator.CHUNK_SIZE - 1) / PooledAllocator.CHUNK_SIZE

        // Fill the live set: N concurrent 8 KiB buffers held at once.
        val slots = arrayOfNulls<IoBuf>(LIVE)
        for (i in 0 until LIVE) slots[i] = a.allocate(CLASS)
        val peak = a.chunkCount

        // Sustained churn at steady occupancy: replace a random slot each cycle so
        // freed runs scatter across chunks (the over-retention stress).
        val rnd = Random(SEED)
        repeat(CHURN_CYCLES) {
            val idx = rnd.nextInt(LIVE)
            slots[idx]?.release()
            slots[idx] = a.allocate(CLASS)
        }
        val afterChurn = a.chunkCount

        // (1) One-shot load drop: release 3/4 in place, keep 1/4 live, trim. Live
        // carves stay where they were placed (the cache recycles the same buffers),
        // so reclaim can free a chunk only if none of its runs are still live.
        val quarter = (minChunks + 3) / 4
        for (i in 0 until LIVE) {
            if (i % 4 != 0) {
                slots[i]?.release()
                slots[i] = null
            }
        }
        a.trimNow()
        val afterOneShotDrop = a.chunkCount

        // (2) Connection turnover at the reduced occupancy: fully release the kept 1/4
        // and carve a fresh 1/4. With the cache drained by trim, the re-carves miss and
        // first-fit re-packs them into the lowest chunks — testing whether turnover lets
        // the footprint recover without compaction or a usage-ring.
        for (i in 0 until LIVE step 4) {
            slots[i]?.release()
            slots[i] = null
        }
        a.trimNow()
        for (i in 0 until LIVE / 4) slots[i] = a.allocate(CLASS)
        a.trimNow()
        val afterTurnover = a.chunkCount

        // (3) Release everything and trim: footprint should drain to the warm reserve.
        for (i in 0 until LIVE) slots[i]?.release()
        a.trimNow()
        val drained = a.chunkCount

        println("== sustained 8K footprint (live=$LIVE, churn=$CHURN_CYCLES, chunk=${chunkKiB}KiB) ==")
        println("  working-set minimum     : $minChunks chunks (~${minChunks * chunkKiB} KiB)")
        println("  peak / after churn      : $peak / $afterChurn  (over-retention ×${ratio(afterChurn, minChunks)})")
        println("  (1) one-shot drop to 1/4: $afterOneShotDrop  (1/4 minimum ≈ $quarter) — in-place, no turnover")
        println("  (2) after 1/4 turnover  : $afterTurnover  (re-carved fresh, first-fit re-pack)")
        println("  (3) drained (all freed) : $drained  (warm reserve = ${PooledAllocator.WARM_RESERVE})")

        // Steady-state is bounded (Phase 5). One-shot drop and turnover are reported,
        // not asserted to a target — they are the Phase 6 evidence.
        assertTrue(afterChurn <= minChunks + 2, "sustained churn over-retained: $afterChurn > $minChunks (+2)")
        assertTrue(drained <= PooledAllocator.WARM_RESERVE + 1, "did not drain to warm reserve: $drained")
    }

    private fun ratio(n: Int, d: Int): String {
        val x = (n * 100) / d
        return "${x / 100}.${(x % 100).toString().padStart(2, '0')}"
    }
}
