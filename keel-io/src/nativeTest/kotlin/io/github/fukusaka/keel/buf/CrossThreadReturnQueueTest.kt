@file:OptIn(ExperimentalForeignApi::class)

package io.github.fukusaka.keel.buf

import kotlinx.cinterop.Arena
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.get
import kotlinx.cinterop.ptr
import kotlinx.cinterop.staticCFunction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import platform.posix.pthread_create
import platform.posix.pthread_join
import platform.posix.pthread_tVar

/**
 * Verifies the Native sharded-return path: a cross-thread release (refcount
 * dropping to zero on a thread other than the owning [SlabAllocator]'s) is routed
 * through the lock-free [IntrusiveMpscReturnQueue] and reclaimed in batch by the
 * owner thread, while a same-thread release still takes the freelist fast path.
 *
 * Cross-thread work runs on a raw `pthread` (as keel's Native EventLoops do), so
 * `currentThreadId()` genuinely differs from the owner's. `pthread_join` makes the
 * worker's release deterministically complete before assertions; no wall-clock
 * timeout is needed (the worker releases a fixed set and exits — it cannot hang).
 */
class CrossThreadReturnQueueTest {

    private companion object {
        // A real Netty ladder size class (round-trips through size2SizeIdx exactly).
        private const val CLASS = 512

        // Iterations for the best-effort offer-vs-close race smoke test.
        private const val RACE_ITERATIONS = 50

        // Buffers (= worker pthreads) raced against close() per iteration.
        private const val RACE_BUFFERS = 12
    }

    @Test
    fun `intrusive queue drains in FIFO order and clears each link`() {
        val q = IntrusiveMpscReturnQueue()
        val a = NativeIoBuf(CLASS)
        val b = NativeIoBuf(CLASS)
        val c = NativeIoBuf(CLASS)
        q.offer(a)
        q.offer(b)
        q.offer(c)
        assertTrue(q.isNotEmpty())

        val out = ArrayList<IoBuf>()
        q.drain(out)
        assertEquals(listOf<IoBuf>(a, b, c), out, "drain must restore FIFO insertion order")
        assertTrue(a.nextLink == null && b.nextLink == null && c.nextLink == null, "links must be cleared")
        assertTrue(!q.isNotEmpty(), "queue is empty after drain")

        // A second drain on an empty queue is a no-op.
        out.clear()
        q.drain(out)
        assertTrue(out.isEmpty())

        a.close()
        b.close()
        c.close()
    }

    @Test
    fun `closed queue rejects offers and frees on the false path`() {
        val q = IntrusiveMpscReturnQueue()
        val a = NativeIoBuf(CLASS)
        val b = NativeIoBuf(CLASS)
        assertTrue(q.offer(a), "open queue accepts the offer")

        // close() emits what was queued (FIFO) and switches to the closed state.
        val drained = ArrayList<IoBuf>()
        q.close(drained)
        assertEquals(listOf<IoBuf>(a), drained, "close emits the queued buffer in FIFO order")
        assertTrue(a.nextLink == null, "link cleared on close-drain")

        // An offer after close returns false — the caller must free the buffer
        // itself — and does not enqueue.
        assertTrue(!q.offer(b), "closed queue rejects the offer (caller frees)")
        assertTrue(!q.isNotEmpty(), "closed queue reports empty")

        // A drain after close is a no-op and keeps the queue closed.
        val out = ArrayList<IoBuf>()
        q.drain(out)
        assertTrue(out.isEmpty(), "drain after close yields nothing")
        assertTrue(!q.offer(b), "queue stays closed after a drain")

        a.close()
        b.close()
    }

    @Test
    fun `serial-confined allocator routes every release to the freelist fast path`() {
        val allocator = SlabAllocator()
        allocator.disableCrossThreadRouting()
        try {
            val buf = allocator.allocate(CLASS) // binds ownerTid (unread while disabled)
            // Release on a worker pthread: a thread-id router would see cross-thread,
            // but with routing disabled it takes the freelist path immediately.
            releaseOnWorkerThread(listOf(buf))
            assertEquals(0L, allocator.crossThreadReturnCount(), "disabled routing must not enqueue")
            assertEquals(1, allocator.cachedCountOf(CLASS), "release pooled directly on the freelist")
        } finally {
            allocator.close()
        }
    }

    @Test
    fun `cross-thread release routes through the queue and the owner drains on the next miss`() {
        val allocator = SlabAllocator()
        try {
            val n = 8
            val bufs = ArrayList<IoBuf>(n)
            for (i in 0 until n) bufs.add(allocator.allocate(CLASS)) // binds ownerTid

            // Release every buffer on a worker pthread → cross-thread → MPSC offer.
            releaseOnWorkerThread(bufs)

            assertEquals(n.toLong(), allocator.crossThreadReturnCount(), "all releases route through the queue")
            assertEquals(0, allocator.cachedCountOf(CLASS), "buffers are not pooled until the owner drains")

            // A fresh allocate hits the pool miss → beforePoolMiss drains → re-pop
            // reuses a returned buffer instead of carving (re-carve avoidance).
            val reused = allocator.allocate(CLASS)
            assertEquals(n - 1, allocator.cachedCountOf(CLASS), "drained buffers are pooled, one reused")
            reused.release()
        } finally {
            allocator.close()
        }
    }

    @Test
    fun `same-thread release takes the freelist fast path and never enqueues`() {
        val allocator = SlabAllocator()
        try {
            val buf = allocator.allocate(CLASS)
            buf.release() // owner thread
            assertEquals(0L, allocator.crossThreadReturnCount(), "same-thread release must not enqueue")
            assertEquals(1, allocator.cachedCountOf(CLASS), "same-thread release pools immediately")
        } finally {
            allocator.close()
        }
    }

    @Test
    fun `cross-thread release after close frees directly without enqueuing`() {
        val allocator = SlabAllocator()
        val buf = allocator.allocate(CLASS) // binds ownerTid
        allocator.close() // closed = true; the owner EventLoop is gone

        // A release held across close arrives cross-thread: returnToPool sees the
        // closed flag and frees the backing directly instead of enqueuing into a
        // queue nobody will drain.
        releaseOnWorkerThread(listOf(buf))
        assertEquals(0L, allocator.crossThreadReturnCount(), "post-close release must not enqueue")
    }

    @Test
    fun `concurrent cross-thread releases racing close survive without strand or double-free`() {
        // Best-effort probabilistic guard for the offer-vs-close XOR contract
        // (IntrusiveMpscReturnQueue: a buffer is emitted by close XOR rejected by
        // offer, never both, never neither). Each iteration races RACE_BUFFERS
        // worker-pthread releases against the owner's close(). It cannot force the
        // exact interleaving, but a strand (leak) or a double-free (a crash in
        // freeBacking) surfaces under repetition. Not a proof — a regression guard.
        repeat(RACE_ITERATIONS) {
            val allocator = SlabAllocator()
            val bufs = ArrayList<IoBuf>(RACE_BUFFERS)
            for (i in 0 until RACE_BUFFERS) bufs.add(allocator.allocate(CLASS))
            releaseEachConcurrentlyWithClose(bufs, allocator)
            // Survived (no double-free crash); the count is sane — some releases
            // route through the queue, the rest lose the race to close() and free
            // the backing directly via the offer==false path.
            assertTrue(
                allocator.crossThreadReturnCount() in 0..RACE_BUFFERS.toLong(),
                "cross-thread count out of range",
            )
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun releaseEachConcurrentlyWithClose(bufs: List<IoBuf>, allocator: SlabAllocator) {
        val arena = Arena()
        try {
            val threadPtrs = bufs.map { arena.alloc<pthread_tVar>() }
            for (i in bufs.indices) {
                val ref = StableRef.create(bufs[i])
                val rc = pthread_create(
                    threadPtrs[i].ptr, null,
                    staticCFunction { arg ->
                        val b = arg!!.asStableRef<IoBuf>().get()
                        b.release()
                        arg.asStableRef<IoBuf>().dispose()
                        null
                    },
                    ref.asCPointer(),
                )
                check(rc == 0) { "pthread_create failed: rc=$rc" }
            }
            // Owner closes concurrently with the in-flight worker releases.
            allocator.close()
            for (i in bufs.indices) pthread_join(threadPtrs[i].ptr[0], null)
        } finally {
            arena.clear()
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun releaseOnWorkerThread(bufs: List<IoBuf>) {
        val arena = Arena()
        try {
            val threadPtr = arena.alloc<pthread_tVar>()
            val ref = StableRef.create(bufs)
            val rc = pthread_create(
                threadPtr.ptr, null,
                staticCFunction { arg ->
                    val held = arg!!.asStableRef<List<IoBuf>>().get()
                    for (b in held) b.release()
                    arg.asStableRef<List<IoBuf>>().dispose()
                    null
                },
                ref.asCPointer(),
            )
            check(rc == 0) { "pthread_create failed: rc=$rc" }
            pthread_join(threadPtr.ptr[0], null)
        } finally {
            arena.clear()
        }
    }
}
