package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * What this transport owes two callers waiting on one flush.
 *
 * The waiter list and the answering that walks it live in
 * `AbstractIoTransport`, shared by this engine, NIO and the two readiness
 * ones — so a break there is one break in four engines. The two walks over
 * that list are separate code (the drain resumes its waiters, the teardown
 * cancels them), and this engine held neither: cutting either walk to its
 * first waiter passed the whole suite. There is a case for each below, and
 * each fails its own mutation — measured. NIO, the fourth user, still holds
 * the drain's walk with nothing: proving a waiter parked there before the
 * peer reads needs the list's size, which its transport does not expose to
 * tests.
 *
 * **What they do not hold**, so the next reader does not assume otherwise:
 * the `clear()` inside the take. Dropping it leaves entries listed, and what
 * that costs shows only when something answers the same list a second time —
 * the resume then reaches kotlinx's already-resumed error (the branch that
 * returns quietly is a cancelled continuation's *first* answer, and a stale
 * entry answered twice is past it), `resumeFlushWaiters` catches that, and
 * this engine logs it at error. Neither case here answers twice: the
 * drain case runs one completion, the close case one teardown. Holding that
 * would take a third case driving two answers and asserting on the log.
 *
 * Written from outside the list because that is what this engine offers a
 * test: the seam ring and the transport's public API.
 *
 * Every case parks real waiters, so every case is bounded ([withTimeout] under
 * `runBlocking`, wall-clock).
 */
@OptIn(ExperimentalForeignApi::class)
class IoUringTransportOverlappingFlushWaitersSeamTest {

    private val logger = NoopLoggerFactory.logger("IoUringTransportOverlappingFlushWaitersSeamTest")

    private suspend fun withTransport(
        block: suspend (FakeIoUringRing, IoUringEventLoop, IoUringIoTransport) -> Unit,
    ) {
        // The `try` opens as early as the declarations allow, per the fake's
        // own contract: a throw from `initOnEventLoop` or a constructor above
        // it would otherwise leak the fake's arena and skip the rest.
        val fake = FakeIoUringRing()
        var el: IoUringEventLoop? = null
        var bufRing: ProvidedBufferRing? = null
        var outcome: Result<Unit> = Result.success(Unit)
        var teardownFailure: Throwable? = null
        try {
            val loop = IoUringEventLoop(logger, syscallOps = FakeIoUringSyscallOps(), ioUringRing = fake)
            el = loop
            val ring = ProvidedBufferRing(
                loop,
                logger,
                bufferCount = 4,
                bufferSize = 64,
                bgid = 0,
                FakeIoUringBufferRingOps(),
            )
            bufRing = ring
            ring.initOnEventLoop()
            val transport = IoUringIoTransport(
                fd = 999,
                eventLoop = loop,
                capabilities = IoUringCapabilities(),
                writeModeSelector = IoModeSelectors.CQE,
                allocator = DefaultAllocator,
                bufferRing = ring,
                fixedFileRegistry = null,
                registeredBufferTable = DisabledRegisteredBufferRegistry,
                preAllocatedIndex = -1,
            )
            outcome = runCatching { block(fake, loop, transport) }
        } finally {
            // Each step guarded so a throw from one cannot skip the rest.
            // The first failure is kept rather than swallowed: a teardown
            // that started failing (a double free in the ring, a join that
            // stopped returning) would otherwise stay green forever while
            // leaking on every run.
            teardownFailure = listOf(
                runCatching { bufRing?.close() },
                runCatching { el?.close() },
                runCatching { fake.dispose() },
            ).firstNotNullOfOrNull { it.exceptionOrNull() }
        }
        // Both held until the teardown ran, then raised in that order: the
        // case's own failure is the one worth reporting, and neither may skip
        // the cleanup above. Raised here rather than inside the `finally`,
        // which would discard whichever arrived first.
        outcome.getOrThrow()
        teardownFailure?.let { throw it }
    }

    private fun filledBuf(size: Int = 16): IoBuf {
        val buf = DefaultAllocator.allocate(size)
        for (i in 0 until size) buf.writeByte(i.toByte())
        return buf
    }

    /**
     * Parks a waiter and hands back its outcome, with the result caught so a
     * failed await cannot cancel the test's own scope before its assertions
     * run.
     *
     * Started undispatched, and that matters: the seam never starts the loop,
     * so `inEventLoop()` is false and `awaitPendingFlush` dispatches its
     * register as a task. Undispatched start is what gets that task *enqueued*
     * before the case drives `runIteration`, which drains tasks ahead of
     * completions — so the register runs while the flush is still in flight
     * and parks. Start it dispatched and both waiters are answered by the
     * already-drained branch instead, and the cases pass having held nothing.
     */
    private fun CoroutineScope.parkWaiter(transport: IoUringIoTransport): Deferred<Result<Unit>> =
        async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { transport.awaitPendingFlush() }
        }

    @Test
    fun `two waiters parked over one flush are both answered by its completion`() = runBlocking {
        withTimeout(WAITER_TIMEOUT_MS) {
            withTransport { fake, el, transport ->
                transport.write(filledBuf())
                transport.flush()

                val first = parkWaiter(transport)
                val second = parkWaiter(transport)

                val userData = fake.lastSqeUserData()
                fake.enqueueCqe(userData = userData, res = 16, flags = 0u, hasMore = false)
                assertTrue(el.runIteration(Cqe()), "the loop iteration must not fail")

                assertTrue(second.await().isSuccess, "the completion answers the waiter that parked last")
                assertTrue(first.await().isSuccess, "and the one that parked first — an overlap must lose neither")
            }
        }
    }

    @Test
    fun `a close over two parked waiters ends both of their waits`() = runBlocking {
        withTimeout(WAITER_TIMEOUT_MS) {
            // The teardown's own walk, which is not the one the drain uses:
            // it cancels each waiter where the drain resumes it. Nothing held
            // it for this engine either, so a teardown that stopped at the
            // first waiter left the rest suspended for good with their
            // transport already gone.
            withTransport { fake, el, transport ->
                // Kept and released in a `finally`, because nothing else in
                // this case will. The write's buffer is freed by the SEND
                // completion, which this case deliberately never queues --
                // the flush has to stay in flight for the waiters to park --
                // and `flush()` empties the queue as it submits, so the
                // teardown finds nothing to release either. On Native that is
                // a heap leak per run, and a failing assertion is exactly
                // when this case runs repeatedly.
                val queued = filledBuf()
                transport.write(queued)
                transport.flush()

                try {
                    val first = parkWaiter(transport)
                    val second = parkWaiter(transport)

                    // Turns the loop once, with no completion queued, so it
                    // drains the two registers and they park -- the flush is
                    // still in flight. Without it they would run after the close
                    // and be answered by their own `!opened` check, and the
                    // teardown's walk, which is what this case is for, would
                    // never be reached.
                    //
                    // Nothing below detects that substitution: a waiter cancelled
                    // by the entry check comes back as a Throwable exactly like
                    // one the teardown cancels. Proving a waiter parked needs the
                    // list's size, which this transport does not expose. So the
                    // iteration is a precondition this case establishes by
                    // construction and cannot assert -- what the mutation of the
                    // teardown's walk showed is that with it in place, the walk
                    // is the path under test.
                    assertTrue(el.runIteration(Cqe()), "the loop iteration must not fail")

                    // Dispatched too, for the same reason: off the loop thread,
                    // `close()` hands its teardown to the loop rather than
                    // running it here.
                    transport.close()
                    assertTrue(el.runIteration(Cqe()), "nor the one that runs the teardown")

                    // In park order: the list is appended to, and the teardown
                    // walks it from the front, so `first` is the one it reaches
                    // first and `second` is the one a truncated walk drops.
                    assertIs<Throwable>(
                        first.await().exceptionOrNull(),
                        "the close must end the wait it reaches first",
                    )
                    assertIs<Throwable>(
                        second.await().exceptionOrNull(),
                        "and the one behind it — a teardown that stops at one leaves the rest suspended",
                    )
                } finally {
                    queued.release()
                }
            }
        }
    }

    private companion object {
        /** Wall-clock bound for the parked waiters; a flush that never answers fails here. */
        const val WAITER_TIMEOUT_MS = 5_000L
    }
}
