package io.github.fukusaka.keel.buf

/**
 * The number of hardware threads available to this process, used to size the
 * pooled allocator's default shard count. JVM `Runtime.availableProcessors()`,
 * Native `sysconf(_SC_NPROCESSORS_ONLN)`, JS `1` (the JS allocator does not shard).
 */
internal expect fun availableProcessors(): Int

/**
 * Normalises a requested shard count to a valid [ShardedChunkArena] size: rounded
 * up to the next power of two (so the shard index can be masked) and clamped to
 * `[1, MAX_SHARD_COUNT]` (so the per-shard metadata + warm-reserve footprint stays
 * bounded). Lets a caller pass a raw EventLoop count and get a usable shard count.
 */
internal fun normalizeShardCount(requested: Int): Int {
    val capped = requested.coerceIn(1, MAX_SHARD_COUNT)
    return if (capped <= 1) 1 else (capped - 1).takeHighestOneBit() shl 1
}

/**
 * The default [ShardedChunkArena] shard count: [normalizeShardCount] of the core
 * count, so each EventLoop pins to its own shard (Netty `leastUsedArena` style —
 * roughly one EventLoop per shard) on a host whose default EventLoop count equals
 * the core count.
 *
 * Tying the count to the core count (rather than a fixed 8) removes the
 * EventLoop-per-shard collisions a high-core host hits: measured on a 32-core host,
 * the carve-saturation stress at 32 threads drops ~5-10x p50 / ~3-11x p99 going from
 * 8 to 32 shards (the per-shard arena lock stops serialising ~4 EventLoops each).
 * The gain is in carve-heavy / held-buffer (capability) workloads that reach the
 * central; the common path is freelist-front absorbed and unaffected. Override via
 * the allocator's `shardCount` constructor parameter to match a custom EventLoop
 * count (`config.threads`) when it differs from the core count.
 */
internal fun defaultShardCount(): Int = normalizeShardCount(availableProcessors())

/** Footprint cap on the shard count (each shard keeps a warm-reserve chunk). */
private const val MAX_SHARD_COUNT = 64
