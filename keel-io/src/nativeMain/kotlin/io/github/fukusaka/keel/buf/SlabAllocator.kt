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
 * is used only for size-class setup at startup; the per-EL children perform the
 * actual allocations.
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
    sharedChunkArena: ChunkArena?,
) : PooledAllocator(maxTotalBytes, freelistFactory, statsCounter, lifecycleListener, sharedChunkArena) {

    /** Public root constructor: creates and owns a fresh chunk arena. */
    constructor(
        maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
        freelistFactory: FreelistFactory? = null,
        statsCounter: BufferAllocatorStatsCounter = NoOpStatsCounter,
        lifecycleListener: BufferAllocatorLifecycleListener = NoOpLifecycleListener,
    ) : this(maxTotalBytes, freelistFactory, statsCounter, lifecycleListener, null)

    init {
        installDefaultLadder()
    }

    /**
     * Thread id of the owning EventLoop, captured lazily on the first allocation.
     * The EventLoop's loop pthread does not exist when [createChild] runs (that
     * happens on the bootstrap thread), so a constructor-time capture is
     * impossible; [UNSET] until the first [allocate]. Read on every release to
     * classify same- vs cross-thread.
     */
    @kotlin.concurrent.Volatile
    private var ownerTid: Long = UNSET

    /**
     * Set by a serial-confined engine (NWConnection on GCD) via
     * [disableCrossThreadRouting] to opt out of thread-id-based cross-thread
     * routing. Such an engine serialises every allocate / release on one queue but
     * is not pinned to one pthread, so thread-id routing would false-positive on GCD
     * worker-pthread migration. Set once at child creation, before the first
     * allocate. When set, [returnToPool] always takes the freelist fast path.
     */
    @kotlin.concurrent.Volatile
    private var crossThreadRoutingDisabled: Boolean = false

    /** Lock-free queue cross-thread releases land in; drained by the owner thread. */
    private val mpscReturnQueue = IntrusiveMpscReturnQueue()

    /** Count of releases routed through [mpscReturnQueue] (the true cross-thread rate). */
    private val xthreadReturnCount = AtomicLong(0)

    /** Reused scratch list for [drainReturns]; only ever touched by the owner thread. */
    private val drainScratch = ArrayList<IoBuf>(DRAIN_SCRATCH_INITIAL)

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

    override fun newChildInstance(maxTotalBytes: Long, sharedChunkArena: ChunkArena): PooledAllocator =
        SlabAllocator(maxTotalBytes, freelistFactory, statsCounter, lifecycleListener, sharedChunkArena)

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
        if (ownerTid == UNSET) ownerTid = currentThreadId()
    }

    override fun returnToPool(buf: IoBuf) {
        // Serial-confined engine (e.g. NWConnection on GCD): allocate / release are
        // serialised on one queue but not pinned to one pthread, so thread-id
        // routing would misclassify same-queue releases as cross-thread. Opt out to
        // the freelist fast path — the serial queue keeps it uncontended.
        if (crossThreadRoutingDisabled) {
            returnToPoolLocal(buf)
            return
        }
        val owner = ownerTid
        // Same-thread or not-yet-bound: freelist fast path.
        if (owner == UNSET || currentThreadId() == owner) {
            returnToPoolLocal(buf)
            return
        }
        // Cross-thread: hand the buffer to the owner via the lock-free queue. If the
        // queue is closed (owner gone), offer returns false and we free the backing
        // here — returnToPoolLocal's closed-flag branch calls freeBacking. A buffer
        // is thus emitted by onClose's drain XOR freed by this false-path, never both.
        if (mpscReturnQueue.offer(buf as NativeIoBuf)) {
            xthreadReturnCount.fetchAndAdd(1L)
        } else {
            returnToPoolLocal(buf)
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
        drainScratch.clear()
        mpscReturnQueue.close(drainScratch)
        for (i in drainScratch.indices) returnToPoolLocal(drainScratch[i])
        drainScratch.clear()
    }

    /**
     * Drains the cross-thread return queue back through [returnToPoolLocal] on the
     * owner thread. Reuses [drainScratch] so the drain allocates nothing; the list
     * is cleared afterwards so it retains no buffer references.
     */
    private fun drainReturns() {
        drainScratch.clear()
        mpscReturnQueue.drain(drainScratch)
        for (i in drainScratch.indices) returnToPoolLocal(drainScratch[i])
        drainScratch.clear()
    }

    /** Cumulative count of cross-thread releases routed through the MPSC queue. */
    internal fun crossThreadReturnCount(): Long = xthreadReturnCount.load()

    /**
     * Sets [crossThreadRoutingDisabled] so [returnToPool] always takes the freelist
     * fast path. See [BufferAllocator.disableCrossThreadRouting]. The opt-out is
     * per-instance, not inherited through [createChild], so it must be called on the
     * engine child and each per-connection child.
     */
    override fun disableCrossThreadRouting() {
        crossThreadRoutingDisabled = true
    }

    private companion object {
        private const val UNSET = -1L
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

    override fun snapshotInto(out: MutableList<IoBuf>) {
        withSpinLock { out.addAll(list) }
    }
}
