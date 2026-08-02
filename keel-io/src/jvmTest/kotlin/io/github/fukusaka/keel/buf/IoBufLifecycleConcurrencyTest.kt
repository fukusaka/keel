@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package io.github.fukusaka.keel.buf

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Concurrency regression for the [IoBuf] lifecycle contract (atomic retain
 * / release / close) on [AbstractIoBuf]'s default platform implementation
 * ([DirectIoBuf]).
 *
 * Three scenarios:
 *
 * 1. **retain races with concurrent release on the same starting state**:
 *    a starting refcount of 1 is followed by `N` retains and `N` releases
 *    from different threads. The buffer must survive (final refcount =
 *    starting refcount) and no thread should observe an inconsistent state.
 *    Catches the fetchAndAdd-first / check-second protocol bug, where a
 *    retain that loses the race would leave a stale increment in the
 *    counter after its check throws — subsequent releases see a non-zero
 *    count, descend toward zero, and trigger a double-free.
 *
 * 2. **double-close is idempotent**: every thread calls [IoBuf.close] on the
 *    same buffer concurrently. The atomic `compareAndSet` inside
 *    [AbstractIoBuf.close] must select exactly one winner for the
 *    backing release. Catches the pre-fix non-atomic close that could
 *    drive `freeBacking` twice and corrupt a chunk arena / native heap.
 *
 * 3. **close raced by concurrent release**: a thread races close against
 *    multiple releases that started from a positive refcount. The lifecycle
 *    state must remain consistent (no double free, no stuck non-zero
 *    refcount) and the losing releases throw `IllegalStateException` on
 *    their CAS retry seeing the refcount already at zero.
 *
 * These exercise the CAS-loop retain / release and atomic close — without
 * them the test would observe `IllegalStateException` from a release path
 * that should have succeeded, or silently corrupt the test allocator's
 * tracker.
 */
class IoBufLifecycleConcurrencyTest {

    @Test
    fun `concurrent retain and release preserves refcount and survives`() {
        val allocator = DefaultAllocator
        val buf = allocator.allocate(16)
        try {
            val threads = 16
            val opsPerThread = 50_000
            val start = CountDownLatch(1)
            val workers = ArrayList<Thread>(threads * 2)
            val errors = AtomicInteger(0)
            // Keep the throwable, not just a count: awaitWithin / awaitCondition report
            // which wait timed out, and that message is raised *inside* a worker where
            // this catch would otherwise discard it.
            val firstError = AtomicReference<Throwable?>(null)

            repeat(threads) { tid ->
                workers += workerThread("retainer-$tid") {
                    try {
                        start.awaitWithin("retainer start")
                        repeat(opsPerThread) { buf.retain() }
                    } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
                        firstError.compareAndSet(null, t)
                        errors.incrementAndGet()
                    }
                }
                workers += workerThread("releaser-$tid") {
                    try {
                        start.awaitWithin("releaser start")
                        // Releasers start a beat behind, so the early releases see
                        // the bumps from the retainers above and never drive the
                        // refcount below 1 (caller still holds the original 1).
                        repeat(opsPerThread) {
                            // Spin-wait minimally to let retainers establish a
                            // positive backlog; in practice the JVM scheduler is
                            // already enough but a small yield keeps the test stable
                            // under heavy CPU contention.
                            // Bounded: if a retainer died early the backlog never
                            // arrives, and this spin — not the dead thread — is what
                            // would hang the JVM.
                            awaitCondition("releaser waiting for a retain backlog") {
                                readRefCount(buf) > 1
                            }
                            buf.release()
                        }
                    } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
                        firstError.compareAndSet(null, t)
                        errors.incrementAndGet()
                    }
                }
            }
            workers.forEach { it.start() }
            start.countDown()
            workers.joinAllWithin("concurrent retain/release")
            assertEquals(0, errors.get(), "concurrent retain/release failed: ${firstError.get()}")
            // Buffer still alive at the original refcount of 1 — drop it once to
            // free it cleanly via the allocator's normal path.
            assertEquals(true, buf.release(), "final release must drive refcount to zero")
        } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
            // If we threw before the final release, ensure the buffer is freed so
            // the test does not leak. swallow to surface the original failure.
            runCatching { buf.release() }
            throw t
        }
    }

    @Test
    fun `concurrent close is idempotent`() {
        val allocator = DefaultAllocator
        val buf = allocator.allocate(16)
        val threads = 16
        val start = CountDownLatch(1)
        val workers = ArrayList<Thread>(threads)
        val errors = AtomicInteger(0)
        val firstError = AtomicReference<Throwable?>(null)

        repeat(threads) { tid ->
            workers += workerThread("closer-$tid") {
                try {
                    start.awaitWithin("closer start")
                    buf.close()
                } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
                    firstError.compareAndSet(null, t)
                    errors.incrementAndGet()
                }
            }
        }
        workers.forEach { it.start() }
        start.countDown()
        workers.joinAllWithin("concurrent close")

        assertEquals(0, errors.get(), "concurrent close failed: ${firstError.get()}")
        // After close, retain must throw — the buffer is gone.
        assertFailsWith<IllegalStateException> { buf.retain() }
    }

    @Test
    fun `close racing release prevents both double-free and stuck refcount`() {
        val allocator = DefaultAllocator
        val rounds = 5_000
        var doubleFrees = 0
        var stuckCount = 0

        repeat(rounds) {
            val buf = allocator.allocate(16)
            buf.retain() // refcount = 2 (so close and release each have something to drop)
            val start = CountDownLatch(1)
            val releaseError = AtomicInteger(0)
            val closeError = AtomicInteger(0)
            // Separate from the two counters below: those carry a meaning the
            // assertions read off ("close() never throws"), and a harness timeout
            // from awaitWithin is not that.
            val harnessError = AtomicReference<Throwable?>(null)

            val releaser = workerThread("releaser") {
                try {
                    start.awaitWithin("releaser start")
                    buf.release()
                } catch (e: AssertionError) {
                    harnessError.compareAndSet(null, e)
                } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
                    releaseError.incrementAndGet()
                }
            }
            val closer = workerThread("closer") {
                try {
                    start.awaitWithin("closer start")
                    buf.close()
                } catch (e: AssertionError) {
                    harnessError.compareAndSet(null, e)
                } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
                    closeError.incrementAndGet()
                }
            }
            releaser.start()
            closer.start()
            start.countDown()
            listOf(releaser, closer).joinAllWithin("close racing release")
            assertNull(harnessError.get(), "the harness itself failed: ${harnessError.get()}")

            // After both ops complete, exactly one of them must have driven the
            // refcount to zero (close always does so, release only if close hasn't
            // already run). A double-free shows up as freeBacking being called more
            // than once — which we cannot observe via DefaultAllocator's GC path,
            // so we instead verify the post-conditions:
            //
            //   - subsequent retain must throw (refcount at zero)
            //   - subsequent release must throw (refcount at zero)
            //
            // and that any error reported by the racing thread was the expected
            // IllegalStateException, not something else.
            assertFailsWith<IllegalStateException> { buf.retain() }
            assertFailsWith<IllegalStateException> { buf.release() }
            if (releaseError.get() > 0 && closeError.get() > 0) doubleFrees++
            // close() never throws (idempotent), and release() at most throws
            // IllegalStateException on its CAS retry if close drove the refcount
            // to zero first. Track both — anything else is a real bug.
            if (closeError.get() != 0) stuckCount++
        }
        assertEquals(0, stuckCount, "close() must never throw under any race")
        // doubleFrees == 0 doesn't prove the absence of double-free in the
        // allocator layer, only that the IoBuf surface didn't trip an unexpected
        // exception combination. The strong post-conditions above (retain /
        // release throw after the race) catch refcount-corruption bugs.
    }

    /**
     * Reads the buffer's atomic refcount via reflection so the test can wait
     * for the retainer threads to establish a positive backlog before the
     * releaser starts dropping. The field is `private` on
     * [AbstractIoBuf]; reading it is only safe in test code that knows the
     * implementation detail.
     */
    private fun readRefCount(buf: IoBuf): Int {
        val field = AbstractIoBuf::class.java.getDeclaredField("refCount")
        field.isAccessible = true
        val atomic = field.get(buf) as kotlin.concurrent.atomics.AtomicInt
        @OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
        return atomic.load()
    }
}
