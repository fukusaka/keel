@file:OptIn(UnsafeIoBufApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package io.github.fukusaka.keel.buf

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.pin
import kotlin.concurrent.AtomicReference
import kotlin.concurrent.atomics.AtomicLong

/**
 * Native [BufferAllocator] backed by [PooledAllocator] with a spin-lock
 * `ArrayDeque` freelist per size class.
 *
 * The full Netty-style size-class ladder is installed at construction (see
 * [PooledAllocator] for the round-up scheme). Per-class freelist concurrency is
 * the [SpinLockFreelist] — measured ABA-immune and correct under the genuine
 * cross-thread release patterns NWConnection produces (kqueue / epoll engines
 * are EL-pinned and access the freelist uncontended, where the spin lock is
 * essentially free; see `benchmark --bench=freelist-variants` /
 * `--bench=freelist-contended`).
 *
 * **Per-EventLoop pooling**: [createChild] returns a fresh sibling that
 * installs the same ladder, so each EventLoop owns its own pool confined to a
 * single thread. The parent allocator (the instance passed to `IoEngineConfig`)
 * serves size-class setup and the single-buffer check a Native engine makes of
 * it while being built; the per-EL children perform every allocation after
 * that.
 *
 * @param maxTotalBytes Worst-case cache-byte safety valve. Default:
 *   [DEFAULT_MAX_TOTAL_BYTES].
 * @param freelistFactory Optional [FreelistFactory]. `null` (default) selects
 *   `SpinLockFreelist` per size class. Pass `::MutexFreelist` or any other
 *   `FreelistFactory` to swap the strategy — see [PooledAllocator] for the
 *   selection trade-offs.
 */
class SlabAllocator private constructor(
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

    /**
     * The confinement model used to classify a release as same-owner (freelist
     * fast path) or cross-context (route through [mpscReturnQueue]). Defaults to
     * [ThreadIdConfinement] for the pthread-pinned POSIX engines; a serial-queue
     * engine (NWConnection on GCD) installs a queue-identity token via
     * [installConfinement]. Set once at child creation, before the first allocate.
     */
    @kotlin.concurrent.Volatile
    private var confinement: ConfinementToken = ThreadIdConfinement()

    /** Lock-free queue cross-thread releases land in; drained by the owner thread. */
    private val mpscReturnQueue = IntrusiveMpscReturnQueue()

    /** Count of releases routed through [mpscReturnQueue] (the true cross-thread rate). */
    private val xthreadReturnCount = AtomicLong(0)

    @Suppress("IoBufLeak") // Allocator returns ownership to caller
    override fun newBuffer(capacity: Int): IoBuf = NativeIoBuf(capacity)

    @Suppress("IoBufLeak") // Carved view returns ownership to caller
    override fun newChunkView(
        backing: IoBuf,
        byteOffset: Int,
        length: Int,
        pooledChunk: PooledChunk,
        handle: Long,
    ): IoBuf = NativeIoBuf.chunkView(backing, byteOffset, length, pooledChunk, handle)

    override fun defaultFreelist(maxSlots: Int): Freelist = SpinLockFreelist(maxSlots)

    override fun newChildInstance(maxTotalBytes: Long, sharedArena: ShardedChunkArena, shardIdx: Int): PooledAllocator =
        SlabAllocator(
            maxTotalBytes,
            freelistFactory,
            statsCounter,
            lifecycleListener,
            sharedArena,
            shardIdx,
            sharedArena.shardCount,
        )

    /**
     * Routes a carve to the owner EventLoop's pinned shard ([shardIdx]) when the
     * caller is the owning thread, else spreads the foreign thread across shards via
     * [mixShardKey] — a raw [currentThreadId] is a pthread address that clusters in
     * its low bits, landing every off-EL thread on one shard (measured: 256 threads
     * all hit 1 of 32 shards on both Linux and macOS), so a SplitMix64 finalizer
     * avalanches it first. This keeps concurrent off-EL carves (or many threads
     * sharing one allocator) spread over shard locks instead of serialising on one.
     * The returned id is masked to the shard count by [ShardedChunkArena.carve].
     */
    override fun shardIndexForCarve(): Int =
        if (confinement.isCurrentContextOwner()) shardIdx else mixShardKey(currentThreadId())

    @OptIn(ExperimentalForeignApi::class)
    override fun wrapBytes(bytes: ByteArray, offset: Int, length: Int): IoBuf? {
        if (length == 0) return null
        val pinned = bytes.pin()
        val ptr = pinned.addressOf(offset)
        return NativeIoBuf.wrapExternal(
            ptr,
            length,
            bytesWritten = length,
            owner = ExternalWrapOwner { pinned.unpin() },
        )
    }

    /**
     * Returns the native pointers and capacities of all pooled buffers.
     *
     * Used by io_uring to register buffers with the kernel for SEND_ZC_FIXED.
     * Each pair is `(CPointer<ByteVar>, capacity)`. Returns empty list if no
     * buffers are currently pooled.
     *
     * Retained for backward compatibility with callers that hold a
     * [SlabAllocator]-typed reference. New engine code should downcast to the
     * common [PooledAllocator] and extract pointers via the
     * [enumerateNativePooledBuffers] helper, so an out-of-tree
     * [PooledAllocator] subclass is supported too.
     */
    @OptIn(ExperimentalForeignApi::class)
    fun nativePooledBuffers(): List<Pair<CPointer<ByteVar>, Int>> =
        enumerateNativePooledBuffers(this)

    override fun captureOwnerThread() {
        confinement.captureOwner()
    }

    override fun returnToPool(buf: IoBuf) {
        // Same confinement context (same pthread for ThreadIdConfinement, same
        // serial queue for a queue-identity token), or not yet bound: freelist
        // fast path. A serial-queue engine (NWConnection on GCD) reports its
        // on-queue releases as same-context here even across pthread migration,
        // while a genuinely off-queue release (e.g. a pull-mode asSource refill on
        // the caller's thread) falls through to the cross-context routing below.
        if (confinement.isCurrentContextOwner()) {
            returnToPoolLocal(buf)
            return
        }
        // Cross-context: hand the buffer to the owner via the lock-free queue. If the
        // queue is closed (owner gone), offer returns false and we free the backing
        // here. A buffer is thus emitted by onClose's drain XOR freed by this
        // false-path, never both.
        if (mpscReturnQueue.offer(buf as NativeIoBuf)) {
            xthreadReturnCount.fetchAndAdd(1L)
        } else {
            // This closed-queue free runs on the freeing thread, possibly
            // concurrently with onClose's drain on the owner thread. We deliberately
            // bypass returnToPoolLocal here: its recordRelease bumps the cumulative
            // stat counters with plain `++`, and onClose's drain bumps the same
            // counters — two writers would race them. Instead free the backing
            // directly and fire the lifecycle listener so leak detection stays
            // balanced. The few buffers freed on this close-race path are not
            // reflected in the cumulative stats: a deterministic, benign teardown
            // undercount rather than a data race on the counters. Memory safety is
            // unaffected — the backing free goes through ChunkArena.returnRun, which
            // is ArenaLock-guarded during onClose's drain window and single-threaded
            // once teardown has completed.
            lifecycleListener.onReleased(buf)
            (buf as AbstractIoBuf).freeBacking()
        }
    }

    override fun beforePoolMiss(idx: Int): Boolean {
        if (!mpscReturnQueue.isNotEmpty()) return false
        drainReturns()
        return true
    }

    override fun beforeTrim() {
        if (mpscReturnQueue.isNotEmpty()) drainReturns()
    }

    override fun onClose() {
        // The closed flag is already set (PooledAllocator.close set it before this).
        // Atomically swap the queue to its closed sentinel and free everything still
        // enqueued: any release that loses the race to the sentinel frees its own
        // backing via the offer == false path in returnToPool, so each buffer is
        // emitted here XOR freed there — never both, never stranded.
        val scratch = ArrayList<IoBuf>(DRAIN_SCRATCH_INITIAL)
        mpscReturnQueue.close(scratch)
        for (i in scratch.indices) returnToPoolLocal(scratch[i])
    }

    /**
     * Drains the cross-thread return queue back through [returnToPoolLocal].
     *
     * Uses a per-call scratch list, not a shared field. [beforePoolMiss] /
     * [beforeTrim] run on whichever thread is allocating, and a pooled channel
     * consumed via `asSuspendSource` from a non-EventLoop coroutine has the engine
     * push read path and the caller's pull refill both allocate-missing on this
     * allocator — so two threads can reach [drainReturns] concurrently. The MPSC
     * head CAS hands each caller a disjoint chain ([IntrusiveMpscReturnQueue.drain]),
     * so a per-call list keeps concurrent drains from corrupting one shared
     * ArrayList. The allocation is off the hot path — only on a pool miss (or trim)
     * with a non-empty return queue.
     */
    private fun drainReturns() {
        val scratch = ArrayList<IoBuf>(DRAIN_SCRATCH_INITIAL)
        mpscReturnQueue.drain(scratch)
        for (i in scratch.indices) returnToPoolLocal(scratch[i])
    }

    /** Cumulative count of cross-thread releases routed through the MPSC queue. */
    internal fun crossThreadReturnCount(): Long = xthreadReturnCount.load()

    /**
     * Installs the [ConfinementToken] this allocator routes releases against. See
     * [BufferAllocator.installConfinement]. Per-instance, not inherited through
     * [createChild], so it must be called on each child before its first allocate.
     */
    override fun installConfinement(token: ConfinementToken) {
        confinement = token
    }

    private companion object {
        private const val DRAIN_SCRATCH_INITIAL = 32
    }
}

/**
 * Enumerates the native (pointer, capacity) pairs for every pooled buffer in
 * [allocator] that carries a Native-resident pointer. Buffers whose backing is
 * not a `NativePointerAccess` (e.g. an out-of-tree [PooledAllocator] subclass
 * returning a non-native carrier) are silently skipped.
 *
 * The common entry point Linux engines (e.g. io_uring fixed-buffer registration)
 * use to enumerate any [PooledAllocator] without hard-coding [SlabAllocator].
 */
@OptIn(ExperimentalForeignApi::class)
fun enumerateNativePooledBuffers(
    allocator: PooledAllocator,
): List<Pair<CPointer<ByteVar>, Int>> {
    val snapshot = allocator.pooledBuffers()
    if (snapshot.isEmpty()) return emptyList()
    val out = ArrayList<Pair<CPointer<ByteVar>, Int>>(snapshot.size)
    for (buf in snapshot) {
        val ptrAccess = buf as? NativePointerAccess ?: continue
        out.add(ptrAccess.unsafePointer to buf.capacity)
    }
    return out
}

/**
 * Spin-lock-protected `ArrayDeque` freelist (LIFO).
 *
 * Default Native [Freelist]: simple, ABA-immune, fast uncontended (the spin
 * lock's atomic acquire/release cost is hidden behind the surrounding allocator
 * work and engine I/O). Under genuine MPMC contention the spin lock becomes a
 * busy-wait — keel's EL-pinned engines never reach that regime; an
 * arbitrary-concurrency allocator should select a blocking variant instead.
 */
private class SpinLockFreelist(private val maxSlots: Int) : Freelist {
    private val list = ArrayDeque<IoBuf>(maxSlots)
    private val lock = AtomicReference(false)

    private inline fun <T> withSpinLock(block: () -> T): T {
        while (!lock.compareAndSet(false, true)) { /* spin */ }
        try {
            return block()
        } finally {
            lock.value = false
        }
    }

    override fun push(buf: IoBuf): Boolean = withSpinLock {
        if (list.size < maxSlots) {
            list.addLast(buf)
            true
        } else {
            false
        }
    }

    override fun pop(): IoBuf? = withSpinLock {
        if (list.isEmpty()) null else list.removeLast()
    }

    override fun size(): Int = withSpinLock { list.size }

    override fun snapshotInto(out: MutableList<IoBuf>) {
        withSpinLock { out.addAll(list) }
    }
}
