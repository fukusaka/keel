@file:OptIn(ExperimentalForeignApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package io.github.fukusaka.keel.buf

import kotlinx.cinterop.Arena
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.get
import kotlinx.cinterop.ptr
import kotlinx.cinterop.staticCFunction
import platform.posix.pthread_create
import platform.posix.pthread_join
import platform.posix.pthread_tVar
import kotlin.concurrent.atomics.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

        // Non-owner worker threads churning allocate+release concurrently (all route
        // releases to the MPSC and drain it on allocate-miss → concurrent drains).
        private const val CHURN_WORKERS = 4

        // Iterations per worker; enough to surface the concurrent-drain timing race
        // while staying well under the slow-test budget.
        private const val CHURN_ITERS = 50_000
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
    fun `an always-owner confinement routes every release to the freelist fast path`() {
        val allocator = SlabAllocator()
        // A serial-queue engine (e.g. NWConnection on GCD) installs a token whose
        // on-context check holds for every same-queue release regardless of which
        // worker pthread runs it; modelled here by a token that always reports owner.
        allocator.installConfinement(AlwaysOwnerConfinement)
        try {
            val buf = allocator.allocate(CLASS)
            // Release on a worker pthread: a thread-id token would see cross-thread,
            // but the installed token reports same-context, so it pools directly.
            releaseOnWorkerThread(listOf(buf))
            assertEquals(0L, allocator.crossThreadReturnCount(), "always-owner token must not enqueue")
            assertEquals(1, allocator.cachedCountOf(CLASS), "release pooled directly on the freelist")
        } finally {
            allocator.close()
        }
    }

    @Test
    fun `an off-context confinement routes even a same-thread release through the queue`() {
        val allocator = SlabAllocator()
        // Routing follows the installed token, not a hardcoded thread id: a token
        // reporting "not the owner" funnels even a same-thread release to the MPSC
        // queue. This is the path a genuinely off-queue NWConnection release (e.g. an
        // asSource refill on the caller's thread) now takes, instead of racing the
        // queue's freelist as the old blanket opt-out allowed.
        allocator.installConfinement(NeverOwnerConfinement)
        try {
            val buf = allocator.allocate(CLASS)
            buf.release() // same thread, but the token reports off-context
            assertEquals(1L, allocator.crossThreadReturnCount(), "off-context release routes through the queue")
            assertEquals(0, allocator.cachedCountOf(CLASS), "not pooled on the freelist; queued for the owner")
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
        // A counting listener verifies the close-race free still fires onReleased
        // (so leak detection stays balanced) even though returnToPool bypasses
        // recordRelease on this path to avoid racing onClose's stat counters.
        val released = AtomicLong(0)
        val listener = object : BufferAllocatorLifecycleListener {
            override fun onAllocated(buf: IoBuf) {}
            override fun onReleased(buf: IoBuf) {
                released.fetchAndAdd(1L)
            }
        }
        val allocator = SlabAllocator(lifecycleListener = listener)
        val buf = allocator.allocate(CLASS) // binds ownerTid
        allocator.close() // closed = true; the owner EventLoop is gone

        // A release held across close arrives cross-thread: returnToPool sees the
        // closed flag and frees the backing directly instead of enqueuing into a
        // queue nobody will drain.
        releaseOnWorkerThread(listOf(buf))
        assertEquals(0L, allocator.crossThreadReturnCount(), "post-close release must not enqueue")
        assertEquals(1L, released.load(), "the close-race free fires onReleased exactly once (leak-balanced)")
    }

    @Test
    fun `concurrent allocate-release churn drains the return queue without losing buffers`() {
        // Several worker threads churn allocate+release on one allocator whose owner is
        // this (main) thread. Each worker is a non-owner, so its releases route to the
        // MPSC queue and its allocate-misses drain that queue — meaning multiple workers
        // can drain concurrently. Before per-call drain scratch this raced one shared
        // ArrayList: a lost element silently dropped a buffer (leak) and a resize race
        // threw out of bounds (crash). The leak-balance assertion catches a lost/double
        // buffer (every allocated buffer must eventually fire onReleased); a crash fails
        // the test outright. Stress over many iterations to surface the timing race.
        val allocated = AtomicLong(0)
        val released = AtomicLong(0)
        val listener = object : BufferAllocatorLifecycleListener {
            override fun onAllocated(buf: IoBuf) {
                allocated.fetchAndAdd(1L)
            }

            override fun onReleased(buf: IoBuf) {
                released.fetchAndAdd(1L)
            }
        }
        val allocator = SlabAllocator(lifecycleListener = listener)
        allocator.allocate(CLASS).release() // latch ownerTid = this (main) thread
        churnAllocReleaseOnWorkers(allocator, CHURN_WORKERS, CHURN_ITERS)
        allocator.close() // drains the MPSC + pool, settling every outstanding release
        assertEquals(
            allocated.load(),
            released.load(),
            "every allocated buffer must fire onReleased exactly once — a lost or double buffer signals drain-scratch corruption",
        )
    }

    @Test
    fun `concurrent offers racing close are each emitted xor rejected exactly once`() {
        // The offer-vs-close XOR contract of IntrusiveMpscReturnQueue: under a race
        // between RACE_BUFFERS worker-pthread offer()s and the owner's close(), every
        // buffer is either emitted by close() (its offer won the race to the CLOSED
        // sentinel) XOR rejected by offer() (returned false, caller frees) — never
        // both, never neither. The queue is lock-free (a CAS head + one atomic CLOSED
        // swap), so this races only the queue, not the allocator's ChunkArena
        // teardown — that has its own single-threaded-teardown contract (every
        // release-capable thread is joined before close), which the engines honor and
        // a unit test must not violate. Probabilistic interleaving, not a proof — a
        // regression guard run over many iterations.
        repeat(RACE_ITERATIONS) {
            val q = IntrusiveMpscReturnQueue()
            val bufs = Array(RACE_BUFFERS) { NativeIoBuf(CLASS) }
            val accepted = BooleanArray(RACE_BUFFERS)
            offerEachConcurrentlyWithClose(q, bufs, accepted) { drained ->
                // offer won (accepted[i]) iff bufs[i] was captured by close's drain —
                // exactly the XOR. close() atomically swaps the head to CLOSED, so an
                // offer is in `drained` iff its CAS landed before that swap, which is
                // also iff it returned true. A rejected buffer was freed by its worker;
                // an emitted one is freed here — every buffer accounted for once.
                for (i in bufs.indices) {
                    assertEquals(
                        accepted[i],
                        bufs[i] in drained,
                        "buffer $i: emitted-by-close iff its offer was accepted",
                    )
                }
                assertEquals(
                    RACE_BUFFERS,
                    drained.size + accepted.count { !it },
                    "every buffer emitted xor rejected — no strand, no double-free",
                )
                for (b in drained) (b as NativeIoBuf).close()
            }
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun offerEachConcurrentlyWithClose(
        q: IntrusiveMpscReturnQueue,
        bufs: Array<NativeIoBuf>,
        accepted: BooleanArray,
        verifyAndFree: (drained: List<IoBuf>) -> Unit,
    ) {
        val arena = Arena()
        try {
            val threadPtrs = Array(bufs.size) { arena.alloc<pthread_tVar>() }
            for (i in bufs.indices) {
                val ref = StableRef.create(OfferArg(q, bufs[i], accepted, i))
                val rc = pthread_create(
                    threadPtrs[i].ptr,
                    null,
                    staticCFunction { arg ->
                        val a = arg!!.asStableRef<OfferArg>().get()
                        val won = a.q.offer(a.buf)
                        a.accepted[a.idx] = won
                        if (!won) a.buf.close() // rejected by the closed queue: caller frees
                        arg.asStableRef<OfferArg>().dispose()
                        null
                    },
                    ref.asCPointer(),
                )
                check(rc == 0) { "pthread_create failed: rc=$rc" }
            }
            // Owner closes concurrently with the in-flight worker offers, then joins so
            // every accepted[] write and every reject-free has completed before the
            // verification reads them.
            val drained = ArrayList<IoBuf>()
            q.close(drained)
            for (i in bufs.indices) pthread_join(threadPtrs[i].ptr[0], null)
            verifyAndFree(drained)
        } finally {
            arena.clear()
        }
    }

    private object AlwaysOwnerConfinement : ConfinementToken {
        override fun isCurrentContextOwner(): Boolean = true
    }

    private object NeverOwnerConfinement : ConfinementToken {
        override fun isCurrentContextOwner(): Boolean = false
    }

    private class OfferArg(
        val q: IntrusiveMpscReturnQueue,
        val buf: NativeIoBuf,
        val accepted: BooleanArray,
        val idx: Int,
    )

    private class ChurnArg(val allocator: SlabAllocator, val iters: Int)

    /**
     * Spawns [workers] pthreads, each running [iters] `allocate(CLASS)` + `release`
     * cycles on [allocator], and joins them. Each worker is a non-owner thread, so
     * its releases route through the MPSC return queue and its allocate-misses drain
     * it — driving concurrent drains across the workers.
     */
    @OptIn(ExperimentalForeignApi::class)
    private fun churnAllocReleaseOnWorkers(allocator: SlabAllocator, workers: Int, iters: Int) {
        val arena = Arena()
        try {
            val threadPtrs = Array(workers) { arena.alloc<pthread_tVar>() }
            for (w in 0 until workers) {
                val ref = StableRef.create(ChurnArg(allocator, iters))
                val rc = pthread_create(
                    threadPtrs[w].ptr,
                    null,
                    staticCFunction { arg ->
                        val a = arg!!.asStableRef<ChurnArg>().get()
                        repeat(a.iters) { a.allocator.allocate(CLASS).release() }
                        arg.asStableRef<ChurnArg>().dispose()
                        null
                    },
                    ref.asCPointer(),
                )
                check(rc == 0) { "pthread_create failed: rc=$rc" }
            }
            for (w in 0 until workers) pthread_join(threadPtrs[w].ptr[0], null)
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
                threadPtr.ptr,
                null,
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
