package io.github.fukusaka.keel.buf

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Mixed-class footprint measurement — companion to [SustainedFootprintMeasure],
 * exposing intra-chunk fragmentation that the 8K-mono workload cannot exhibit.
 *
 * The 8K-mono measurement showed steady-state churn bounded at ×1.0 of the
 * working-set minimum and connection turnover recovering via first-fit re-pack.
 * That result rests on every carve landing in the same size class so chunks go
 * idle the moment their last carve is freed. It does not generalize to workloads
 * where two size classes co-exist with different lifetimes — this measurement is
 * the representative example of that broader workload shape.
 *
 * Two scenarios contrast where the fragmentation comes from:
 *
 * 1. **Long-lived first (control)** — all the 32 KiB carves are allocated up
 *    front, then the 8 KiB churn begins. First-fit places the 32 KiB carves
 *    contiguously in the lowest chunks, so the long-lived working set occupies
 *    as few chunks as physically possible (its minimum packing). Releasing the
 *    8 KiB working set then leaves only the densely-packed long-lived chunks
 *    resident — **no fragmentation**.
 * 2. **Interleaved** — a 32 KiB carve followed by a burst of 8 KiB carves that
 *    fill the rest of that chunk, repeated. First-fit places each 32 KiB carve
 *    in a fresh chunk because the previous chunk is already full of 8 KiB carves,
 *    so the long-lived working set **scatters across N chunks** even though its
 *    bytes would fit into one chunk. Releasing the 8 KiB working set leaves each
 *    scattered chunk holding one 4-page run live and the rest in free runs — but
 *    the chunk holds a live run so `freeBytes < chunkSize`, and [ChunkArena.reclaim]
 *    (which frees only fully-free chunks) cannot release it.
 *    The result is intra-chunk fragmentation that no `trim` policy can resolve.
 *
 * The interleaved shape happens when allocation lifetimes are decoupled from
 * allocation classes (the most common production shape: new connections joining
 * an EventLoop mid-churn allocate long-lived TLS plaintext while other
 * connections continue short-lived reads). It is also what every additional
 * protocol on keel's roadmap — HTTP/2 streams, gRPC, MQTT sessions, QUIC packet
 * retransmission buffers — produces by construction: the same size class hosts
 * carves with wildly different lifetimes that the allocator cannot see at
 * allocation time.
 *
 * The contrast pins what *does* and *does not* trigger the fragmentation:
 * a mixed-class workload alone does not (scenario A), but **temporal
 * interleaving of long and short lifetimes within the same class arena** does
 * (scenario B). What design ultimately addresses this in keel is left open —
 * the candidates (per-class chunk segregation, usage-ring binning, soft cap,
 * pressure response, anti-generational adaptive chunk sizing) trade off
 * differently against keel's intended multi-protocol workload, and committing
 * to one now would be premature ahead of real gRPC / MQTT / QUIC profile data.
 *
 * The value this measurement carries forward is:
 * 1. A reproducible demonstration that the 8K-mono finding does not generalize.
 * 2. A measurement harness that future allocator changes can be evaluated
 *    against — both the synthetic scenarios here and the larger workloads the
 *    roadmap will add.
 *
 * Like [SustainedFootprintMeasure], this asserts only safety invariants (the
 * allocator drains to the warm reserve on full release). The over-retention
 * numbers are **reported**, not targeted by assertions — locking in a ratio
 * would freeze the diagnosis into a contract test, the wrong shape for an
 * exploratory measurement.
 */
class MixedClassFootprintMeasure {

    private fun pooled(): PooledAllocator = createPoolAllocator() as PooledAllocator

    @Test
    fun `footprint - long-lived 32K first then 8K churn keeps long-lived densely packed`() {
        if (!isPoolAllocator()) return
        val a = pooled()

        // Allocate the entire long-lived working set up front: first-fit places every
        // 32 KiB carve into the lowest chunk that still has a 32 KiB-aligned free run,
        // so the long-lived bytes pack densely (the minimum chunk count).
        val longLived = Array(LONG_BUFFERS) { a.allocate(LONG_CLASS) }
        val afterLong = a.chunkCount

        // Add a churning 8 KiB working set. First-fit puts the 8 KiB carves into the
        // remaining gaps in the long-lived chunks first, then spills into fresh chunks.
        val shortLived = arrayOfNulls<IoBuf>(SHORT_BUFFERS)
        for (i in 0 until SHORT_BUFFERS) shortLived[i] = a.allocate(SHORT_CLASS)
        val afterAllocate = a.chunkCount

        // Sustained churn — the same shape as SustainedFootprintMeasure's steady-state
        // case, but with the long-lived buffers held live throughout.
        val rnd = kotlin.random.Random(SEED)
        repeat(CHURN_CYCLES) {
            val idx = rnd.nextInt(SHORT_BUFFERS)
            shortLived[idx]?.release()
            shortLived[idx] = a.allocate(SHORT_CLASS)
        }
        val afterChurn = a.chunkCount

        // Release the short-lived working set, leaving only the long-lived in place.
        // Because the long-lived were packed densely up front, only the chunks they
        // occupy stay pinned; the higher chunks (which held only 8 KiB carves) go
        // idle and trim reclaims them.
        for (i in 0 until SHORT_BUFFERS) shortLived[i]?.release()
        a.trimNow()
        val afterShortDrop = a.chunkCount

        // Drain: full release + trim should reach the warm reserve.
        for (i in 0 until LONG_BUFFERS) longLived[i].release()
        a.trimNow()
        val drained = a.chunkCount

        val minLong = ((LONG_BUFFERS * LONG_CLASS) + PooledAllocator.CHUNK_SIZE - 1) / PooledAllocator.CHUNK_SIZE
        println("== mixed-class footprint, scenario A: long-lived first (no scatter) ==")
        // Named because wrapping puts the literal on its own indented line, past the length cap.
        val shape = "  long=$LONG_BUFFERS × ${LONG_CLASS / 1024}KiB held, " +
            "short=$SHORT_BUFFERS × ${SHORT_CLASS / 1024}KiB churning ($CHURN_CYCLES cycles)"
        println(shape)
        println("  after long allocate      : $afterLong chunks")
        println("  after short allocate     : $afterAllocate chunks")
        println("  after sustained churn    : $afterChurn chunks")
        println("  after short-lived drop   : $afterShortDrop chunks (long-lived min packing = $minLong)")
        println("  drained (all released)   : $drained chunks (warm reserve = ${PooledAllocator.WARM_RESERVE})")

        // Safety invariants only.
        assertTrue(afterChurn <= afterAllocate + 2, "sustained churn over-retained: $afterChurn > $afterAllocate (+2)")
        assertTrue(drained <= PooledAllocator.WARM_RESERVE + 1, "did not drain to warm reserve: $drained")
    }

    @Test
    fun `footprint - long-lived interleaved with 8K bursts scatters into one chunk per long-lived carve`() {
        if (!isPoolAllocator()) return
        val a = pooled()

        // Force the long-lived class to scatter across chunks. The realistic shape:
        // every long-lived carve lands in its own chunk because the previous chunk is
        // already filled with the prior burst of 8 KiB carves.
        //
        // Per-iteration burst sized to fill the remainder of the chunk the long-lived
        // carve just opened: chunk capacity minus the long-lived run, divided by the
        // short class. With the default 256 KiB chunk, 32 KiB long, and 8 KiB short
        // this is `(256 - 32) / 8 = 28` short carves per long.
        val perBurst = (PooledAllocator.CHUNK_SIZE - LONG_CLASS) / SHORT_CLASS
        val longLived = mutableListOf<IoBuf>()
        val shortLived = mutableListOf<IoBuf>()
        repeat(LONG_BUFFERS) {
            longLived.add(a.allocate(LONG_CLASS))
            repeat(perBurst) { shortLived.add(a.allocate(SHORT_CLASS)) }
        }
        val afterSetup = a.chunkCount

        // Sustained churn of the 8 KiB working set while the long-lived carves remain
        // live in those scattered chunks.
        val rnd = kotlin.random.Random(SEED)
        repeat(CHURN_CYCLES) {
            val idx = rnd.nextInt(shortLived.size)
            shortLived[idx].release()
            shortLived[idx] = a.allocate(SHORT_CLASS)
        }
        val afterChurn = a.chunkCount

        // Drop the entire short-lived working set. Each chunk hosting a long-lived
        // carve now holds one 4-page run live + ~28 pages of coalesced free runs, but
        // the live run keeps `freeBytes < chunkSize` (not fully free), so reclaim cannot release it.
        // The headline number: residentChunks vs the long-lived minimum packing.
        shortLived.forEach { it.release() }
        a.trimNow()
        val afterShortDrop = a.chunkCount
        val minLong = ((LONG_BUFFERS * LONG_CLASS) + PooledAllocator.CHUNK_SIZE - 1) / PooledAllocator.CHUNK_SIZE

        // Drain: releasing the long-lived too makes every chunk idle and trim
        // reclaims them. This is the safety invariant the assertions pin — a leak
        // here would invalidate the diagnosis above.
        longLived.forEach { it.release() }
        a.trimNow()
        val drained = a.chunkCount

        println("== mixed-class footprint, scenario B: long-lived interleaved (scatter) ==")
        val shape = "  long=$LONG_BUFFERS × ${LONG_CLASS / 1024}KiB held, " +
            "short=${LONG_BUFFERS * perBurst} × ${SHORT_CLASS / 1024}KiB ($CHURN_CYCLES cycles)"
        println(shape)
        println("  after setup              : $afterSetup chunks")
        println("  after sustained churn    : $afterChurn chunks")
        println(
            "  after short-lived drop   : $afterShortDrop chunks vs long-lived min packing $minLong  (over-retention ×${ratio(
                afterShortDrop,
                minLong,
            )})",
        )
        println("  drained (all released)   : $drained chunks (warm reserve = ${PooledAllocator.WARM_RESERVE})")
        println("  → over-retention > 1 = intra-chunk fragmentation invisible to the 8K-mono measurement.")

        assertTrue(afterChurn <= afterSetup + 2, "sustained churn over-retained: $afterChurn > $afterSetup (+2)")
        assertTrue(drained <= PooledAllocator.WARM_RESERVE + 1, "did not drain to warm reserve: $drained")
    }

    private fun ratio(n: Int, d: Int): String {
        val x = (n * 100) / d
        return "${x / 100}.${(x % 100).toString().padStart(2, '0')}"
    }

    private companion object {
        // TLS plaintext / WS session class — long-lived, held across short-lived churn.
        const val LONG_CLASS = 32 * 1024

        // Read-buffer class — short-lived, churned.
        const val SHORT_CLASS = 8 * 1024

        // Long-lived working set: 8 × 32 KiB = 256 KiB. With a 256 KiB chunk this
        // packs into exactly 1 chunk under perfect placement (the minimum), so the
        // over-retention ratio reported by scenario B counts pinned chunks directly.
        const val LONG_BUFFERS = 8

        // Short-lived working set for the long-lived-first scenario. Sized to a few
        // chunks' worth so the post-drop reclaim has something to release.
        const val SHORT_BUFFERS = 128 // 1 MiB at 8 KiB

        // Cycle counts mirror SustainedFootprintMeasure's per-buffer ratio.
        const val CHURN_CYCLES = SHORT_BUFFERS * 50
        const val SEED = 42
    }
}
