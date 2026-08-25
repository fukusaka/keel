@file:OptIn(InternalReadinessEngineApi::class)

package io.github.fukusaka.keel.native.readiness

import io.github.fukusaka.keel.native.posix.WriteResult
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pins what the transport owes when two callers wait on one flush.
 *
 * Nothing in [io.github.fukusaka.keel.pipeline.IoTransport.awaitPendingFlush]'s
 * contract makes the wait exclusive, and the public entry — `Channel.flush()`
 * from two coroutines — overlaps them naturally. The transport once held its
 * waiter in a single slot, and an overlap lost one of them two ways: the
 * second park's store evicted the first, whose hang no answer path could end
 * because every one of them read only the slot; and the evicted waiter's
 * cancellation ran a shared hook that cleared whichever waiter the slot held
 * by then. Each case here parks two real waiters and drives the drain, the
 * stop, or the close that must answer them **both**.
 *
 * Every test parks real waiters, so every test is bounded by [withTimeout]
 * (wall-clock: `runBlocking` builder, per the project's timeout rule).
 */
@OptIn(ExperimentalForeignApi::class)
internal class TransportOverlappingFlushWaitersSeamTest : TransportSeamFixture() {

    @Test
    fun `two waiters parked over one queue are both answered by the drain`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            fake.enqueueWrite(fd, WriteResult.WouldBlock)
            val transport = transport()
            transport.write(tracker.allocate(16).apply { writerIndex = 8 })
            assertFalse(transport.flush(), "the unwritable socket leaves the queue for a later drain")

            val first = parkFlushWaiter(transport)
            val second = parkFlushWaiter(transport)
            assertTrue(transport.hasFlushWaiter(), "both waiters must be parked before the drain")

            fake.enqueueWrite(fd, WriteResult.Written(8))
            assertTrue(transport.flush(), "the retried drain empties the queue")

            assertTrue(second.await().isSuccess, "the drain answers the waiter that parked last")
            assertTrue(first.await().isSuccess, "and the one that parked first — an overlap must lose neither")
            assertFalse(transport.hasFlushWaiter(), "no waiter is left behind")
            fake.assertAllConsumed()
            transport.close()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a waiter cancelled while parked does not take a later waiter's answer with it`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            fake.enqueueWrite(fd, WriteResult.WouldBlock)
            val transport = transport()
            transport.write(tracker.allocate(16).apply { writerIndex = 8 })
            assertFalse(transport.flush(), "the unwritable socket leaves the queue for a later drain")

            val first = parkFlushWaiter(transport)
            val second = parkFlushWaiter(transport)
            first.cancel()

            fake.enqueueWrite(fd, WriteResult.Written(8))
            assertTrue(transport.flush(), "the retried drain empties the queue")

            assertTrue(
                second.await().isSuccess,
                "the cancelled waiter's exit must not erase the one still waiting",
            )
            fake.assertAllConsumed()
            transport.close()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a cancelled waiter's entry is swept out when the next waiter parks`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The sweep is a memory bound, not a behavioural one — every
            // answer path treats a dead entry harmlessly — so the count seam
            // is the only observation with teeth: without the sweep, a
            // timeout-and-retry flusher on a stalled socket grows one
            // retained continuation per timeout for the connection's life.
            fake.enqueueWrite(fd, WriteResult.WouldBlock)
            val transport = transport()
            transport.write(tracker.allocate(16).apply { writerIndex = 8 })
            assertFalse(transport.flush(), "the unwritable socket leaves the queue for a later drain")

            val first = parkFlushWaiter(transport)
            first.cancel()
            assertEquals(1, transport.flushWaiterCount(), "the dead entry stays until something disposes of it")

            val second = parkFlushWaiter(transport)
            assertEquals(1, transport.flushWaiterCount(), "the next park sweeps the dead entry out")

            fake.enqueueWrite(fd, WriteResult.Written(8))
            assertTrue(transport.flush(), "the retried drain empties the queue")
            assertTrue(second.await().isSuccess, "and answers the live waiter")
            fake.assertAllConsumed()
            transport.close()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a waiter whose dispatcher refuses the answer does not strand the waiter behind it`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The teardown answers an already-taken snapshot, so a refusal
            // that aborted the loop would strand every waiter behind it with
            // nobody left holding their continuations. The refusing waiter is
            // parked from its own scope on a dispatcher that throws for the
            // answer: its coroutine is unreachable from then on -- exactly
            // what the guard's log line says -- so it is deliberately not
            // joined; the scope is dropped and the zombie dies with the test
            // process.
            fake.enqueueWrite(fd, WriteResult.WouldBlock)
            val transport = transport()
            transport.write(tracker.allocate(16).apply { writerIndex = 8 })
            assertFalse(transport.flush(), "the unwritable socket leaves the queue for a later drain")

            val refusing = object : CoroutineDispatcher() {
                override fun dispatch(context: CoroutineContext, block: Runnable) {
                    throw IllegalStateException("this waiter's dispatcher refuses every answer")
                }
            }
            val orphans = CoroutineScope(Job())
            orphans.async(refusing, start = CoroutineStart.UNDISPATCHED) {
                runCatching { transport.awaitPendingFlush() }
            }
            val behind = parkFlushWaiter(transport)

            val closeOutcome = runCatching { transport.close() }

            assertIs<Throwable>(
                behind.await().exceptionOrNull(),
                "the refusal ahead of it must not cost this waiter the teardown's answer",
            )
            assertIs<Throwable>(
                closeOutcome.exceptionOrNull(),
                "and the close still carries the refusal it could not deliver, rather than reporting clean",
            )
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a close over two parked waiters tells both the transport closed`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            fake.enqueueWrite(fd, WriteResult.WouldBlock)
            val transport = transport()
            transport.write(tracker.allocate(16).apply { writerIndex = 8 })
            assertFalse(transport.flush(), "the unwritable socket leaves the queue for a later drain")

            val first = parkFlushWaiter(transport)
            val second = parkFlushWaiter(transport)

            transport.close()

            val firstTold = first.await().exceptionOrNull()
            val secondTold = second.await().exceptionOrNull()
            assertIs<Throwable>(secondTold, "the close must answer the waiter the old slot held")
            assertIs<Throwable>(firstTold, "and the one it evicted — the teardown exists to end these waits")
            tracker.assertNoLeaks()
        }
    }
}
