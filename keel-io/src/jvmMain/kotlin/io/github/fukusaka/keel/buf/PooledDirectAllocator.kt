@file:OptIn(UnsafeIoBufApi::class)

package io.github.fukusaka.keel.buf

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * JVM [BufferAllocator] backed by [PooledAllocator] with an intrusive Treiber
 * stack freelist per size class.
 *
 * The default 8 KiB class is registered at construction for backward
 * compatibility with engine read buffers and `BufferedSuspendSink`. Per-class
 * freelist concurrency is the [TreiberStackFreelist] — lock-free and fastest
 * uncontended, **and ABA-unsafe under genuine MPMC**: keel's JVM engines (NIO,
 * Netty) hold pooled buffers EL-pinned and never truly contended (NIO event
 * thread / Netty `EventLoop` thread is the sole producer/consumer for each pool
 * instance), so the algorithm is safe in production. See
 * `benchmark --bench=freelist-contended` for the ABA evidence on both platforms,
 * and `FreelistContendedBenchmark` for the JVM measurement.
 *
 * **Per-EventLoop pooling**: [createForEventLoop] returns a fresh sibling with
 * the parent's size classes propagated but per-pool capacity capped at
 * `LOCAL_POOL_SLOTS`, so each EventLoop owns its own pool confined to a single
 * thread. The parent allocator (the instance passed to `IoEngineConfig`) is used
 * only for size-class registration at startup; the per-EL children perform the
 * actual allocations.
 *
 * @param maxTotalBytes Maximum total bytes across all pool classes. Safety
 *   valve. Default: 256 KiB.
 */
class PooledDirectAllocator(
    maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
) : PooledAllocator(maxTotalBytes) {

    init {
        registerPoolSize(SEGMENT_SIZE, DEFAULT_POOL_SLOTS)
    }

    @Suppress("IoBufLeak") // Allocator returns ownership to caller
    override fun newBuffer(capacity: Int): IoBuf = DirectIoBuf(capacity)

    override fun newFreelist(maxSlots: Int): Freelist = TreiberStackFreelist(maxSlots)

    override fun createChild(maxTotalBytes: Long): PooledAllocator = PooledDirectAllocator(maxTotalBytes)

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
 * after `createForEventLoop()`, so push/pop never race in practice. An
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
