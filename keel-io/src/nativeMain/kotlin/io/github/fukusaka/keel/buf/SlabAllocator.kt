@file:OptIn(UnsafeIoBufApi::class)

package io.github.fukusaka.keel.buf

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.pin
import kotlin.concurrent.AtomicReference

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
class SlabAllocator(
    maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
    freelistFactory: FreelistFactory? = null,
    missProfile: PoolMissProfile? = null,
) : PooledAllocator(maxTotalBytes, freelistFactory, missProfile) {

    init {
        installDefaultLadder()
    }

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

    override fun newChildInstance(maxTotalBytes: Long): PooledAllocator =
        SlabAllocator(maxTotalBytes, freelistFactory, missProfile)

    @OptIn(ExperimentalForeignApi::class)
    override fun wrapBytes(bytes: ByteArray, offset: Int, length: Int): IoBuf? {
        if (length == 0) return null
        val pinned = bytes.pin()
        val ptr = pinned.addressOf(offset)
        return NativeIoBuf.wrapExternal(ptr, length, bytesWritten = length, owner = ExternalWrapOwner { pinned.unpin() })
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
