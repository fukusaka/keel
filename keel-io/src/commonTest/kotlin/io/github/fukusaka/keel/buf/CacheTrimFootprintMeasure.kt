package io.github.fukusaka.keel.buf

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Measurement (not a strict contract test) for the footprint effect of
 * cache trim + idle-chunk reclaim under a *variable* working set.
 *
 * Why a dedicated measurement: the steady-state `/hello` A/B keeps every cache
 * full, so the trim pass evicts nothing — it can only show the per-allocation
 * counters add no hot-path cost, never the trim's actual job. Footprint is
 * dominated by resident **chunk count** (one chunk = `CHUNK_SIZE` = 256 KiB);
 * cache-entry count (what the evict formula moves) is byte-noise by comparison.
 * This drives a burst → idle pattern and reports peak vs reclaimed chunks, i.e.
 * the with-trim / without-trim footprint delta the I/O bench cannot surface.
 *
 * Numbers are printed (not asserted to exact values) so they can be read off the
 * test log; the assertions only pin the qualitative invariant (trim reclaims the
 * idle chunks a no-trim run would pin forever).
 */
class CacheTrimFootprintMeasure {
    private fun pooled(): PooledAllocator = createPoolAllocator() as PooledAllocator

    private companion object {
        const val CLASS = 8192
        const val BURST = 512 // 512 × 8 KiB = 4 MiB live → many 256 KiB chunks
    }

    @Test
    fun `footprint - burst then idle - trim reclaims chunks the I_O bench cannot show`() {
        if (!isPoolAllocator()) return

        // --- Run A: no trim (simulates the behaviour before cache trim existed) ---
        val noTrim = pooled()
        val liveA = (0 until BURST).map { noTrim.allocate(CLASS) }
        val peakA = noTrim.chunkCount
        liveA.forEach { it.release() } // returns to cache (cap entries) + frees the rest
        // No trimNow() call: idle chunks stay pinned/resident.
        val idleNoTrim = noTrim.chunkCount

        // --- Run B: with trim ---
        val withTrim = pooled()
        val liveB = (0 until BURST).map { withTrim.allocate(CLASS) }
        val peakB = withTrim.chunkCount
        liveB.forEach { it.release() }
        withTrim.trimNow()
        val afterTrim = withTrim.chunkCount

        val chunkKiB = PooledAllocator.CHUNK_SIZE / 1024
        println("== cache-trim footprint (class=$CLASS, burst=$BURST, chunk=${chunkKiB}KiB) ==")
        println("  peak chunks            : A=$peakA  B=$peakB  (~${peakB * chunkKiB} KiB live)")
        println("  resident after release : no-trim=$idleNoTrim  with-trim=$afterTrim")
        println(
            "  reclaimed by trim      : ${idleNoTrim - afterTrim} chunks " +
                "(~${(idleNoTrim - afterTrim) * chunkKiB} KiB returned to OS)",
        )

        // Qualitative invariant: a burst spans multiple chunks, and trim brings the
        // resident set down to the warm reserve while a no-trim run stays elevated.
        assertTrue(peakB >= 2, "burst should span multiple chunks (was $peakB)")
        assertTrue(afterTrim < idleNoTrim, "trim should reclaim idle chunks ($afterTrim !< $idleNoTrim)")
        assertTrue(
            afterTrim <= PooledAllocator.WARM_RESERVE + 1,
            "trim should approach the warm reserve (was $afterTrim)",
        )
    }
}
