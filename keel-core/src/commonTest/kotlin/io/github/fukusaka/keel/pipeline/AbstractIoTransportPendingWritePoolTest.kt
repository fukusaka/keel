package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the [AbstractIoTransport.PendingWrite] free-list semantics:
 * recycled entries are reused by the next [AbstractIoTransport.write]
 * with every field re-initialised, an empty pool allocates fresh, and
 * the pool capacity is bounded.
 *
 * Synchronous data-structure tests — no I/O, no dispatch — so no
 * wall-clock timeout is required.
 */
class AbstractIoTransportPendingWritePoolTest {

    /** Minimal concrete transport: queue + pool behaviour only, no real I/O. */
    private class PoolProbeTransport : AbstractIoTransport(DefaultAllocator) {
        override var readEnabled: Boolean = false
        override val ioDispatcher: CoroutineDispatcher = Dispatchers.Default

        override fun flush(): Boolean = true
        override fun shutdownOutput() {}
        override fun close() {}

        val queue: ArrayDeque<PendingWrite> get() = pendingWrites

        /** Drains the queue the way a sync engine's flush does: release + recycle. */
        fun drainAndRecycle() {
            for (pw in pendingWrites) {
                pw.buf.release()
                recyclePendingWrite(pw)
            }
            pendingWrites.clear()
        }

        fun obtain(buf: IoBuf, offset: Int, length: Int): PendingWrite =
            obtainPendingWrite(buf, offset, length)

        fun recycle(pw: PendingWrite) = recyclePendingWrite(pw)
    }

    private fun buf(bytes: Int): IoBuf = DefaultAllocator.allocate(16).also { b ->
        repeat(bytes) { b.writeByte(0x61) }
    }

    @Test
    fun `a recycled entry is reused by the next write with fields re-initialised`() {
        val t = PoolProbeTransport()
        t.write(buf(3))
        val first = t.queue.single()
        t.drainAndRecycle()

        t.write(buf(5))
        val second = t.queue.single()
        assertSame(first, second, "the recycled entry must be reused, not re-allocated")
        assertEquals(0, second.offset, "offset must be re-initialised on reuse")
        assertEquals(5, second.length, "length must be re-initialised on reuse")
        assertTrue(second.buf.readableBytes == 5, "buf must be the new write's buffer")
        t.drainAndRecycle()
    }

    @Test
    fun `an empty pool allocates a fresh entry per write`() {
        val t = PoolProbeTransport()
        t.write(buf(1))
        t.write(buf(2))
        val (a, b) = t.queue.toList()
        assertNotSame(a, b, "without recycling every write gets its own entry")
        // No recycle (the async-engine shape): the next write allocates fresh.
        for (pw in t.queue) pw.buf.release()
        t.queue.clear()
        t.write(buf(3))
        val c = t.queue.single()
        assertNotSame(a, c)
        assertNotSame(b, c)
        t.drainAndRecycle()
    }

    @Test
    fun `the pool capacity is bounded - excess recycles are dropped`() {
        val t = PoolProbeTransport()
        val overCap = 40 // PENDING_WRITE_POOL_MAX (32) + 8
        val released = buf(1)
        val distinct = ArrayList<AbstractIoTransport.PendingWrite>(overCap)
        repeat(overCap) { distinct.add(AbstractIoTransport.PendingWrite(released, 0, 1)) }
        for (pw in distinct) t.recycle(pw)

        var pooledHits = 0
        repeat(overCap) {
            val out = t.obtain(released, 0, 1)
            if (distinct.any { it === out }) pooledHits++
        }
        assertEquals(32, pooledHits, "the free list must cap at PENDING_WRITE_POOL_MAX")
        released.release()
    }
}
