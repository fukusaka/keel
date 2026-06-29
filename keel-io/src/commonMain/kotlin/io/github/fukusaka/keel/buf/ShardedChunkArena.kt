package io.github.fukusaka.keel.buf

/**
 * Routes carves across [shardCount] independent [ChunkArena] shards — each with its
 * own [ArenaLock] — to cut the single-lock serialisation of the central back-end
 * under **concurrent carve** (multiple EventLoops / off-EL threads missing their
 * per-EL freelist front at the same time). A single shared [ChunkArena] serialises
 * every cross-thread carve on one lock; N shards spread that contention ~1/N.
 *
 * The EL-pinned common path never reaches here (the freelist front absorbs it) and
 * cross-thread *free* routes through the owning allocator's MPSC return queue
 * (drained on the owner thread), so the only traffic that hits a shard lock is a
 * concurrent freelist miss — exactly what sharding targets.
 *
 * **Return needs no shard lookup**: [ChunkArena.carve] stamps the owning shard onto
 * the [PooledChunk] (`pc.arena`), and a run is returned through that chunk
 * ([ChunkArena.returnRun]). So this type routes only the carve (allocate); a release
 * always finds its way back to the shard it came from.
 *
 * **Thread → shard mapping is the caller's** (see [PooledAllocator]'s
 * `shardIndexForCarve`): an EventLoop carves from its pinned shard (uncontended in
 * the common 1-EL-per-shard case, Netty `leastUsedArena` style), an off-EL thread
 * hashes its id. [shardCount] is a power of two so the caller can mask with [mask]
 * (`tid and mask`).
 *
 * Not thread-safe to construct or [close] concurrently; carve/reclaim are safe from
 * any thread because each delegates to a shard's own [ArenaLock]. Only the owning
 * root allocator constructs and closes it (single-threaded teardown, see
 * [PooledAllocator.close]).
 */
internal class ShardedChunkArena(
    val shardCount: Int,
    sizeClasses: SizeClasses,
    newChunkBacking: () -> IoBuf,
    newChunkView: ChunkViewFactory,
) {
    init {
        require(shardCount > 0 && (shardCount and (shardCount - 1)) == 0) {
            "shardCount must be a positive power of two, was $shardCount"
        }
    }

    /** `index and mask` maps any int to a shard (valid because [shardCount] is power-of-two). */
    val mask: Int = shardCount - 1

    private val shards: Array<ChunkArena> = Array(shardCount) {
        ChunkArena(sizeClasses, newChunkBacking, newChunkView)
    }

    /** Carves a [sizeIdx] view from shard `shardIndex and mask`. Runs under that shard's lock. */
    fun carve(sizeIdx: Int, shardIndex: Int): IoBuf = shards[shardIndex and mask].carve(sizeIdx)

    /** Reclaims idle chunks in shard `shardIndex and mask` (the caller's pinned shard). */
    fun reclaim(shardIndex: Int, warmReserve: Int) = shards[shardIndex and mask].reclaim(warmReserve)

    /** Closes every shard. Called once by the owning root allocator at teardown. */
    fun close() {
        for (i in shards.indices) shards[i].close()
    }

    /** Sum of resident chunks across all shards (test / diagnostic observability). */
    val chunkCount: Int
        get() {
            var total = 0
            for (i in shards.indices) total += shards[i].chunkCount
            return total
        }

    /** Sum of cumulative chunk allocations across all shards (stats snapshot). */
    val cumulativeChunksAllocated: Long
        get() {
            var total = 0L
            for (i in shards.indices) total += shards[i].cumulativeChunksAllocated
            return total
        }

    /** Sum of cumulative chunk frees across all shards (stats snapshot). */
    val cumulativeChunksFreed: Long
        get() {
            var total = 0L
            for (i in shards.indices) total += shards[i].cumulativeChunksFreed
            return total
        }
}
