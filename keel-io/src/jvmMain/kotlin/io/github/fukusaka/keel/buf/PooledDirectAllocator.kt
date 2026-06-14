@file:OptIn(UnsafeIoBufApi::class)

package io.github.fukusaka.keel.buf

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * JVM [BufferAllocator] backed by [PooledAllocator] with an intrusive Treiber
 * stack freelist per size class.
 *
 * The full Netty-style size-class ladder is installed at construction (see
 * [PooledAllocator] for the round-up scheme). Per-class freelist concurrency is
 * the [TreiberStackFreelist] — lock-free and fastest uncontended, **and
 * ABA-unsafe under genuine MPMC**: keel's JVM engines (NIO, Netty) hold pooled
 * buffers EL-pinned and never truly contended (NIO event thread / Netty
 * `EventLoop` thread is the sole producer/consumer for each pool instance), so
 * the algorithm is safe in production. See `benchmark --bench=freelist-contended`
 * for the ABA evidence on both platforms, and `FreelistContendedBenchmark` for
 * the JVM measurement.
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
 *   the intrusive `TreiberStackFreelist` per size class. Pass `::MutexFreelist`
 *   or any other `FreelistFactory` to swap the strategy — see [PooledAllocator]
 *   for the selection trade-offs.
 */
class PooledDirectAllocator(
    maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
    freelistFactory: FreelistFactory? = null,
    missProfile: PoolMissProfile? = null,
) : PooledAllocator(maxTotalBytes, freelistFactory, missProfile) {

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

    override fun defaultFreelist(maxSlots: Int): Freelist = TreiberStackFreelist(maxSlots)

    override fun newChildInstance(maxTotalBytes: Long): PooledAllocator =
        PooledDirectAllocator(maxTotalBytes, freelistFactory, missProfile)

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

/**
 * Lock-free intrusive Treiber stack of [DirectIoBuf]s using each buffer's own
 * `nextLink` field as the freelist link — avoids wrapper-node allocations.
 *
 * **Concurrency invariant**: ABA-unsafe under genuine MPMC. Safe here because
 * each [PooledDirectAllocator] instance is owned by a single EventLoop thread
 * after `createChild()`, so push/pop never race in practice. An
 * arbitrary-concurrency allocator should select a blocking or ABA-safe variant
 * instead (see `benchmark --bench=freelist-contended`).
 */
private class TreiberStackFreelist(private val maxSlots: Int) : Freelist {
    private val head = AtomicReference<DirectIoBuf?>(null)
    private val size = AtomicInteger(0)

    override fun push(buf: IoBuf): Boolean {
        val newSize = size.incrementAndGet()
        if (newSize > maxSlots) {
            size.decrementAndGet()
            return false
        }
        val dbuf = buf as DirectIoBuf
        while (true) {
            val cur = head.get()
            dbuf.nextLink = cur
            if (head.compareAndSet(cur, dbuf)) return true
        }
    }

    override fun pop(): IoBuf? {
        while (true) {
            val cur = head.get() ?: return null
            if (head.compareAndSet(cur, cur.nextLink)) {
                cur.nextLink = null
                size.decrementAndGet()
                return cur
            }
        }
    }

    override fun snapshotInto(out: MutableList<IoBuf>) {
        var cur = head.get()
        while (cur != null) {
            out.add(cur)
            cur = cur.nextLink
        }
    }
}
