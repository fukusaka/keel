package io.github.fukusaka.keel.native.posix

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlin.concurrent.AtomicInt
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the fakes promise when two threads touch them at once.
 *
 * The engine issues syscalls from its loop thread while the test thread arms
 * scripts and reads counters back. That is the arrangement every seam test is
 * in, and it is the one the fakes' old contract — "single-threaded only; wrap
 * in `synchronized` at the call site" — described as somebody else's problem.
 * No caller could take it up: the other caller is production code.
 *
 * These drive the same shape deliberately, at a volume no seam test reaches, so
 * that removing the lock is a failure rather than a possibility. Measured: with
 * [FakeSocketLock.withLock] reduced to calling its block, all four fail on each
 * of three runs — none of them is waiting for an unlucky interleaving.
 *
 * They do not check ordering, because the fakes do not provide it — see the
 * [FakeSocketLock] KDoc on the difference between a whole read and a meaningful
 * one.
 */
@OptIn(ExperimentalForeignApi::class, ObsoleteWorkersApi::class)
class FakeSocketConcurrencyTest {

    @Test
    fun `a counter under concurrent calls holds every one of them`() {
        val fake = FakeNativeSocket().apply { defaultRead = ReadResult.Bytes(1) }
        val started = AtomicInt(0)

        val worker = Worker.start()
        val other = worker.execute(TransferMode.SAFE, { fake to started }) { (socket, gate) ->
            gate.incrementAndGet()
            while (gate.value < 2) {
                // Both sides in the loop together, or one finishes before the
                // other starts and there is nothing to race.
            }
            memScoped {
                val byte = alloc<ByteVar>()
                repeat(CALLS_PER_THREAD) { socket.read(FD, byte.ptr, 1) }
            }
        }

        started.incrementAndGet()
        while (started.value < 2) {
            // See above.
        }
        memScoped {
            val byte = alloc<ByteVar>()
            repeat(CALLS_PER_THREAD) { fake.read(FD, byte.ptr, 1) }
        }
        other.result
        worker.requestTermination().result

        assertEquals(
            2 * CALLS_PER_THREAD,
            fake.readCalls,
            "every call must be counted; a lost increment is two threads reading the same value",
        )
    }

    @Test
    fun `a scripted response is handed to one caller only`() {
        val fake = FakeNativeSocket().apply { defaultRead = ReadResult.WouldBlock }
        // One call at a time, because `enqueueRead` takes a vararg and spreading
        // an array of forty thousand copies it first.
        repeat(2 * CALLS_PER_THREAD) { fake.enqueueRead(FD, ReadResult.Bytes(1)) }
        val started = AtomicInt(0)
        val onWorkerBytes = AtomicInt(0)

        val worker = Worker.start()
        val handoff = { Triple(fake, started, onWorkerBytes) }
        val other = worker.execute(TransferMode.SAFE, handoff) { (socket, gate, tally) ->
            gate.incrementAndGet()
            while (gate.value < 2) {
                // Both sides in the loop together.
            }
            memScoped {
                val byte = alloc<ByteVar>()
                repeat(CALLS_PER_THREAD) {
                    if (socket.read(FD, byte.ptr, 1) is ReadResult.Bytes) tally.incrementAndGet()
                }
            }
        }

        started.incrementAndGet()
        while (started.value < 2) {
            // See above.
        }
        var here = 0
        memScoped {
            val byte = alloc<ByteVar>()
            repeat(CALLS_PER_THREAD) {
                if (fake.read(FD, byte.ptr, 1) is ReadResult.Bytes) here++
            }
        }
        other.result
        worker.requestTermination().result

        // Exactly the scripted number came out, and each went to one caller: a
        // deque torn between two threads either hands one entry out twice — more
        // Bytes than were queued — or drops entries and answers WouldBlock.
        assertEquals(
            2 * CALLS_PER_THREAD,
            here + onWorkerBytes.value,
            "each queued response must be delivered exactly once across both threads",
        )
        fake.assertAllConsumed()
    }

    @Test
    fun `a one-shot throw fires for one caller only`() {
        repeat(ONE_SHOT_ROUNDS) {
            val fake = FakeNativeSocket().apply { defaultRead = ReadResult.WouldBlock }
            val started = AtomicInt(0)
            val throws = AtomicInt(0)
            fake.readThrowsOnce = IllegalStateException("scripted")

            val worker = Worker.start()
            val handoff = { Triple(fake, started, throws) }
            val other = worker.execute(TransferMode.SAFE, handoff) { (socket, gate, tally) ->
                gate.incrementAndGet()
                while (gate.value < 2) {
                    // Both sides in the loop together.
                }
                memScoped {
                    val byte = alloc<ByteVar>()
                    runCatching { socket.read(FD, byte.ptr, 1) }
                        .onFailure { tally.incrementAndGet() }
                }
            }

            started.incrementAndGet()
            while (started.value < 2) {
                // See above.
            }
            memScoped {
                val byte = alloc<ByteVar>()
                runCatching { fake.read(FD, byte.ptr, 1) }
                    .onFailure { throws.incrementAndGet() }
            }
            other.result
            worker.requestTermination().result

            assertEquals(1, throws.value, "the one-shot must be taken by exactly one caller")
        }
    }

    @Test
    fun `the ops fake counts every concurrent call`() {
        val fake = FakeNativeSocketOps()
        val started = AtomicInt(0)

        val worker = Worker.start()
        val other = worker.execute(TransferMode.SAFE, { fake to started }) { (ops, gate) ->
            gate.incrementAndGet()
            while (gate.value < 2) {
                // Both sides in the loop together.
            }
            repeat(CALLS_PER_THREAD) { ops.setNonBlocking(FD) }
        }

        started.incrementAndGet()
        while (started.value < 2) {
            // See above.
        }
        repeat(CALLS_PER_THREAD) { fake.setNonBlocking(FD) }
        other.result
        worker.requestTermination().result

        assertEquals(2 * CALLS_PER_THREAD, fake.setNonBlockingCalls, "every call must be counted")
        assertTrue(
            fake.nonBlockingFds.size == 2 * CALLS_PER_THREAD,
            "every call must be recorded; a torn list drops entries, got ${fake.nonBlockingFds.size}",
        )
    }

    private companion object {
        const val FD = 3

        /**
         * High enough that an unguarded counter loses increments every run, low
         * enough that all four tests together take under 100 ms.
         */
        const val CALLS_PER_THREAD = 20_000

        /** The one-shot races only at the instant it is armed, so try repeatedly. */
        const val ONE_SHOT_ROUNDS = 200
    }
}
