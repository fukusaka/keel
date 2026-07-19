package io.github.fukusaka.keel.buf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the [SizeClasses] port against Netty's published size-class table and
 * the round-up invariants the cache layer relies on.
 *
 * The keel default parameters (`pageSize = 8 KiB`, `pageShifts = 13`,
 * `chunkSize = 256 KiB`, `alignment = 0`) reproduce Netty's standard ladder; the
 * low classes (idx 0..39) are independent of `chunkSize`, so they are pinned to
 * Netty's canonical values as a porting oracle.
 */
class SizeClassesTest {
    private val sc = SizeClasses(
        pageSize = 8192,
        pageShifts = 13,
        chunkSize = 256 * 1024,
        directMemoryCacheAlignment = 0,
    )

    /** Netty's canonical size classes for idx 0..39 (16 B quantum, 4 classes per doubling). */
    private val canonical = intArrayOf(
        16, 32, 48, 64, // group 0
        80, 96, 112, 128,
        160, 192, 224, 256,
        320, 384, 448, 512,
        640, 768, 896, 1024,
        1280, 1536, 1792, 2048,
        2560, 3072, 3584, 4096,
        5120, 6144, 7168, 8192,
        10240, 12288, 14336, 16384,
        20480, 24576, 28672, 32768,
    )

    @Test
    fun `low size classes match Netty canonical table`() {
        for (idx in canonical.indices) {
            assertEquals(canonical[idx], sc.sizeIdx2size(idx), "sizeIdx2size($idx)")
        }
    }

    @Test
    fun `size2SizeIdx rounds up to the smallest class at boundaries`() {
        assertEquals(0, sc.size2SizeIdx(0), "0 -> idx 0")
        assertEquals(0, sc.size2SizeIdx(1), "1 -> 16")
        assertEquals(0, sc.size2SizeIdx(16), "16 -> 16")
        assertEquals(1, sc.size2SizeIdx(17), "17 -> 32")
        assertEquals(7, sc.size2SizeIdx(128), "128 -> 128")
        assertEquals(8, sc.size2SizeIdx(129), "129 -> 160")
        assertEquals(31, sc.size2SizeIdx(8192), "8192 -> 8192")
        assertEquals(32, sc.size2SizeIdx(8193), "8193 -> 10240")
        // Measured unpooled-bypass sizes from the allocation profile land in a class:
        assertEquals(35, sc.size2SizeIdx(16384), "TLS plaintext 16384 -> 16384")
        assertTrue(sc.sizeIdx2size(sc.size2SizeIdx(17408)) >= 17408, "TLS ciphertext 17408 pools")
        assertTrue(sc.sizeIdx2size(sc.size2SizeIdx(1031)) >= 1031, "SSE 1031 pools")
    }

    @Test
    fun `every class size maps back to its own index`() {
        for (idx in 0 until sc.nSizes) {
            val size = sc.sizeIdx2size(idx)
            assertEquals(idx, sc.size2SizeIdx(size), "round-trip idx $idx (size $size)")
        }
    }

    @Test
    fun `size2SizeIdx returns the smallest class that fits for a full sweep`() {
        // For every byte size in a representative sweep, the chosen class must be
        // >= the request, and the previous class must be < the request (i.e. it is
        // genuinely the *smallest* fitting class — the round-up contract).
        var s = 1
        while (s <= sc.chunkSize) {
            val idx = sc.size2SizeIdx(s)
            val classSize = sc.sizeIdx2size(idx)
            assertTrue(classSize >= s, "class $classSize must satisfy request $s")
            if (idx > 0) {
                assertTrue(sc.sizeIdx2size(idx - 1) < s, "previous class for $s must be smaller")
            }
            // Sweep boundaries densely near class edges, sparsely in between.
            s += if (s < 4096) 1 else 17
        }
    }

    @Test
    fun `requests above chunkSize map to the huge sentinel`() {
        assertEquals(sc.nSizes - 1, sc.size2SizeIdx(sc.chunkSize), "chunkSize is the last class")
        assertEquals(sc.chunkSize, sc.sizeIdx2size(sc.nSizes - 1), "last class == chunkSize")
        assertEquals(sc.nSizes, sc.size2SizeIdx(sc.chunkSize + 1), "chunkSize+1 is huge")
    }

    @Test
    fun `page-multiple classes resolve in the run region`() {
        // 1 page (8 KiB) is the smallest run class.
        val onePageIdx = sc.pages2pageIdx(1)
        assertEquals(8192, sc.pageIdx2size(onePageIdx), "1 page -> 8192")
        // 32 pages (256 KiB) is the chunk-sized run class.
        val maxPages = sc.chunkSize / sc.pageSize
        val maxPageIdx = sc.pages2pageIdx(maxPages)
        assertEquals(sc.chunkSize, sc.pageIdx2size(maxPageIdx), "$maxPages pages -> chunkSize")
        assertTrue(sc.nPSizes > 0, "run region is non-empty")
    }
}
