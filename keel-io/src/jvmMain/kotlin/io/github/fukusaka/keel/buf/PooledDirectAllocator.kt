@file:OptIn(UnsafeIoBufApi::class)

package io.github.fukusaka.keel.buf

import java.nio.ByteBuffer

/**
 * JVM [BufferAllocator] backed by [PooledAllocator] with a per-size-class
 * [MutexFreelist] (ReentrantLock + ArrayDeque).
 *
 * The full Netty-style size-class ladder is installed at construction (see
 * [PooledAllocator] for the round-up scheme). The freelist is lock-guarded because
 * allocate is not single-thread-confined in general: a pooled channel consumed via
 * `asSource` from a non-EventLoop coroutine has the engine's push read path
 * (EventLoop thread) and the caller's pull refill (consuming coroutine) allocate on
 * the same allocator concurrently. The earlier lock-free intrusive Treiber stack
 * was ABA-unsafe under that genuine concurrency — it corrupted into a double-free
 * (`"Buffer already released"`). [MutexFreelist] is safe under any concurrency; its
 * uncontended cost is a ReentrantLock CAS fast-path (measured +~6 ns per pop+push
 * roundtrip vs the Treiber, negligible against a full allocate). See
 * `FreelistContendedBenchmark` for the ABA evidence and the uncontended cost.
 *
 * **Per-EventLoop pooling**: [createChild] returns a fresh sibling that
 * installs the same ladder, so each EventLoop owns its own pool confined to a
 * single thread. The parent allocator (the instance passed to `IoEngineConfig`)
 * is used only for size-class setup at startup; the per-EL children perform the
 * actual allocations.
 *
 * @param maxTotalBytes Worst-case cache-byte safety valve. Default:
 *   [DEFAULT_MAX_TOTAL_BYTES].
 * @param freelistFactory Optional [FreelistFactory]. `null` (default) selects a
 *   per-size-class [MutexFreelist]. Pass another `FreelistFactory` to swap the
 *   strategy — see [PooledAllocator] for the selection trade-offs.
 */
class PooledDirectAllocator private constructor(
    maxTotalBytes: Long,
    freelistFactory: FreelistFactory?,
    statsCounter: BufferAllocatorStatsCounter,
    lifecycleListener: BufferAllocatorLifecycleListener,
    sharedArena: ShardedChunkArena?,
    shardIdx: Int,
    shardCount: Int,
) : PooledAllocator(
    maxTotalBytes,
    freelistFactory,
    statsCounter,
    lifecycleListener,
    sharedArena,
    shardIdx,
    shardCount,
) {

    /** Public root constructor: creates and owns a fresh sharded chunk arena. */
    constructor(
        maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
        freelistFactory: FreelistFactory? = null,
        statsCounter: BufferAllocatorStatsCounter = NoOpStatsCounter,
        lifecycleListener: BufferAllocatorLifecycleListener = NoOpLifecycleListener,
        shardCount: Int = defaultShardCount(),
    ) : this(maxTotalBytes, freelistFactory, statsCounter, lifecycleListener, null, 0, shardCount)

    init {
        installDefaultLadder()
    }

    @Suppress("IoBufLeak") // Allocator returns ownership to caller
    override fun newBuffer(capacity: Int): IoBuf = DirectIoBuf(capacity)

    @Suppress("IoBufLeak") // Carved view returns ownership to caller
    override fun newChunkView(
        backing: IoBuf,
        byteOffset: Int,
        length: Int,
        pooledChunk: PooledChunk,
        handle: Long,
    ): IoBuf = DirectIoBuf.chunkView(backing, byteOffset, length, pooledChunk, handle)

    override fun defaultFreelist(maxSlots: Int): Freelist = MutexFreelist(maxSlots)

    override fun newChildInstance(maxTotalBytes: Long, sharedArena: ShardedChunkArena, shardIdx: Int): PooledAllocator =
        PooledDirectAllocator(
            maxTotalBytes,
            freelistFactory,
            statsCounter,
            lifecycleListener,
            sharedArena,
            shardIdx,
            sharedArena.shardCount,
        )

    override fun wrapBytes(bytes: ByteArray, offset: Int, length: Int): IoBuf? {
        if (length == 0) return null
        val heapBuffer = if (offset == 0 && length == bytes.size) {
            ByteBuffer.wrap(bytes)
        } else {
            ByteBuffer.wrap(bytes, offset, length).slice()
        }
        return DirectIoBuf.wrapExternal(heapBuffer, bytesWritten = length)
    }
}
