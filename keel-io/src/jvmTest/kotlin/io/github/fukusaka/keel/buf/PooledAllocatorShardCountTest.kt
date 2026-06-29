package io.github.fukusaka.keel.buf

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies the `shardCount` constructor parameter threads through to the central
 * [ShardedChunkArena]. Exercised via [PooledDirectAllocator]; the wiring is shared
 * commonMain code in [PooledAllocator], so [SlabAllocator] behaves identically.
 */
class PooledAllocatorShardCountTest {

    @Test
    fun `the default shardCount sizes the arena to the core-count default`() {
        val allocator = PooledDirectAllocator()
        try {
            assertEquals(defaultShardCount(), allocator.centralShardCount)
        } finally {
            allocator.close()
        }
    }

    @Test
    fun `a custom shardCount is normalised up to a power of two`() {
        // A raw EventLoop count of 10 rounds up to 16 shards.
        val allocator = PooledDirectAllocator(shardCount = 10)
        try {
            assertEquals(16, allocator.centralShardCount)
        } finally {
            allocator.close()
        }
    }

    @Test
    fun `a custom shardCount is clamped to the footprint cap`() {
        val allocator = PooledDirectAllocator(shardCount = 1000)
        try {
            assertEquals(64, allocator.centralShardCount)
        } finally {
            allocator.close()
        }
    }

    @Test
    fun `a per-EventLoop child shares the parent's shard count`() {
        val parent = PooledDirectAllocator(shardCount = 10)
        try {
            val child = parent.createChild() as PooledAllocator
            try {
                assertEquals(16, child.centralShardCount)
            } finally {
                child.close()
            }
        } finally {
            parent.close()
        }
    }
}
