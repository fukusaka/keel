package io.github.fukusaka.keel.buf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Validates the [availableProcessors] accessor and the [defaultShardCount] /
 * [normalizeShardCount] shard-count derivation across all platforms.
 */
class ShardCountTest {

    @Test
    fun `availableProcessors reports at least one core`() {
        assertTrue(availableProcessors() >= 1, "availableProcessors ${availableProcessors()} < 1")
    }

    @Test
    fun `defaultShardCount is a power of two within the footprint cap`() {
        val n = defaultShardCount()
        assertTrue(n in 1..64, "shard count $n out of range [1, 64]")
        assertTrue(n and (n - 1) == 0, "shard count $n is not a power of two")
    }

    @Test
    fun `defaultShardCount gives each core its own shard up to the cap`() {
        // Each EventLoop pins to one shard; the count should cover the core count
        // (capped) so a high-core host stops colliding EventLoops onto a shared shard.
        val coresCapped = availableProcessors().coerceAtMost(64)
        assertTrue(
            defaultShardCount() >= coresCapped,
            "shards ${defaultShardCount()} < capped cores $coresCapped",
        )
    }

    @Test
    fun `normalizeShardCount rounds a requested count up to a capped power of two`() {
        assertEquals(1, normalizeShardCount(0), "0 clamps to 1")
        assertEquals(1, normalizeShardCount(1))
        assertEquals(2, normalizeShardCount(2))
        assertEquals(4, normalizeShardCount(3), "rounds up to the next power of two")
        assertEquals(16, normalizeShardCount(10), "a raw EventLoop count rounds up")
        assertEquals(32, normalizeShardCount(32))
        assertEquals(64, normalizeShardCount(48))
        assertEquals(64, normalizeShardCount(1000), "clamps to the footprint cap")
        assertEquals(16, normalizeShardCount(normalizeShardCount(10)), "idempotent on its own output")
    }
}
