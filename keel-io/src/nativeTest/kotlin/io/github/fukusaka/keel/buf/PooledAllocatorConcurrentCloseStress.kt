@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package io.github.fukusaka.keel.buf

import kotlinx.cinterop.toKString
import platform.posix.getenv
import platform.posix.sched_yield
import kotlin.concurrent.atomics.AtomicInt
import kotlin.native.concurrent.FutureState
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.native.concurrent.waitForMultipleFutures
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * One allocator closed by several threads at once.
 *
 * The contract asks for a single-threaded teardown, and every engine in the
 * tree obeys it. What a caller outside it used to get was not a failure: the
 * closed check and the flag it sets are two steps, so two closers could both
 * read the flag as false, and the second walked into the arena lock the first
 * was destroying, and what `pthread_mutex_lock` does on a destroyed mutex is
 * the platform's to decide.
 *
 * Native-only because that is where the destroyed lock is real. The JVM
 * reaches the same race and nothing happens — its arena lock is reentrant and
 * its close is a no-op — so a JVM-only check reports this clean, which is why
 * the case that catches it has to live here.
 *
 * What the second closer gets differs by platform, and this case is written to
 * catch either. On macOS the locking call refuses and the closer comes back
 * carrying `pthread_mutex_lock() failed` — measured against the reverted fix,
 * within a single quick run. On Linux it has been seen to wait instead, with
 * every thread parked in `futex_wait_queue` and still there minutes later.
 * A wait is the whole process stopping rather than a case failing, so every
 * wait below is bounded and reports.
 *
 * **Gating**: returns early unless `KEEL_STRESS` is set (`quick` runs the quick
 * scenario, `full` runs both).
 */
class PooledAllocatorConcurrentCloseStress {

    /**
     * One closer's share of a round: wait for the others, then close.
     *
     * The barrier is what makes the overlap structural. The window this
     * catches is the few instructions between reading the closed flag and
     * setting it, so closers that merely start together rarely land in it —
     * with the barrier they leave the same point on the same cycle, whatever
     * else the machine is doing.
     */
    private class Closer(
        val allocator: BufferAllocator,
        val arrived: AtomicInt,
        val fanout: Int,
        val startedAt: TimeSource.Monotonic.ValueTimeMark,
        val budget: Duration,
    ) {
        /**
         * Wait for the other closers, then close.
         *
         * Everything is caught for the reason the sibling case states: an
         * exception crossing a worker's future arrives on this side unnamed,
         * so a closer that died in the teardown and one starved at the
         * barrier would read identically.
         */
        @Suppress("TooGenericExceptionCaught")
        fun close(): String? =
            try {
                arrived.fetchAndAdd(1)
                while (arrived.load() < fanout) {
                    check(startedAt.elapsedNow() < budget) {
                        "a closer never reached the barrier: ${arrived.load()} of $fanout arrived"
                    }
                    sched_yield()
                }
                allocator.close()
                // Asked on this thread, right where the loser returns. On
                // the shipped code it can only answer "closed", since every
                // closer sets the flag before claiming -- so this is a guard
                // against one shape of change rather than a property of the
                // code as it stands: move that write below the claim and a
                // loser returns with the flag down, its caller can carve from
                // an arena the winner is destroying, and no later assertion
                // in the round could see it -- by then the winner has set it.
                if (allocator.stillAllocates()) "close() returned with the allocator still open" else null
            } catch (stopped: Throwable) {
                stopped.stackTraceToString()
            }
    }

    @Test
    fun quick() {
        if (stressMode() !in setOf("quick", "full")) return
        closeConcurrently(rounds = QUICK_ROUNDS)
    }

    @Test
    fun full() {
        if (stressMode() != "full") return
        closeConcurrently(rounds = FULL_ROUNDS)
    }

    /**
     * [rounds] rounds of [FANOUT] threads closing one allocator together.
     *
     * Four things are asserted, because the wedge is only one of the ways
     * this can go wrong. That every closer comes back is the wedge itself.
     * That each one found the allocator refusing **when its own close
     * returned** guards the flag's ordering. It cannot fail against the code
     * as written — the flag is set before the claim, so the answer is fixed —
     * but a change that moved the write below the claim would hand a losing
     * caller an allocator carving from an arena the winner is destroying, and
     * only that thread is in a position to notice. That the allocator refuses
     * again once every closer has returned asks the same question of the round
     * rather than of one thread. That no chunk is resident afterwards is the
     * teardown body:
     * every closer sets the flag before claiming, so refusing to allocate
     * says merely that someone reached `close()`, while a claim that never
     * succeeds leaks every chunk and would otherwise pass.
     */
    private fun closeConcurrently(rounds: Int) {
        val workers = List(FANOUT) { Worker.start() }
        try {
            repeat(rounds) { round ->
                val allocator = defaultAllocator()
                // One buffer through it first, so the round has a chunk to
                // account for: an allocator that never allocated closes with
                // nothing resident, and the third guard below would pass on a
                // teardown that did nothing.
                allocator.allocate(PROBE_SIZE).release()
                val arrived = AtomicInt(0)
                val startedAt = TimeSource.Monotonic.markNow()
                val closing = workers.map { worker ->
                    val closer = Closer(allocator, arrived, FANOUT, startedAt, ROUND_BUDGET)
                    worker.execute(TransferMode.SAFE, { closer }) { job -> job.close() }
                }

                val waiting = closing.toMutableSet()
                while (waiting.isNotEmpty()) {
                    if (startedAt.elapsedNow() >= ROUND_BUDGET) {
                        fail(
                            "only ${closing.size - waiting.size} of ${closing.size} closers came back " +
                                "in round $round",
                        )
                    }
                    waiting -= waitForMultipleFutures(waiting.toList(), POLL_MS)
                    // A future that ended by throwing stays in the set
                    // otherwise, and would be reported as the wedge it is not.
                    waiting.removeAll { it.state != FutureState.SCHEDULED }
                }
                closing.map { it.result }.firstOrNull { it != null }?.let { stopped ->
                    fail("a closer stopped in round $round: $stopped")
                }

                if (allocator.stillAllocates()) {
                    fail("the allocator still hands out buffers after $FANOUT closes in round $round")
                }
                // The flag alone would pass with a teardown nobody ran: every
                // closer sets it before claiming, so refusing to allocate says
                // only that someone reached `close()`. The chunks say the body
                // ran -- the arena releases them, and a claim that never
                // succeeds leaks every one of them while the round above still
                // looks clean.
                //
                // Read after every closer has returned, so the winner's close
                // is done. Safe to read at all because this allocator's
                // freelists are the spin-lock kind, whose close is the
                // interface no-op; the snapshot also counts each freelist's
                // size, and a freelist that destroyed a mutex there would be
                // read after the fact.
                val resident = allocator.stats().snapshot().residentChunks
                if (resident != 0) {
                    fail("$resident chunks are still resident after the close in round $round")
                }
            }
        } finally {
            val stopping = workers.map { it.requestTermination() }.toMutableSet()
            val stoppingSince = TimeSource.Monotonic.markNow()
            while (stopping.isNotEmpty() && stoppingSince.elapsedNow() < ROUND_BUDGET) {
                val stopped = waitForMultipleFutures(stopping.toList(), POLL_MS)
                stopped.forEach { it.result }
                stopping -= stopped
            }
        }
    }

    private companion object {
        /**
         * Sixteen, matching the sibling concurrent-child case: the window is
         * a handful of instructions, and the detections there moved with
         * fanout well past four on machines that are also busy. Rounds buy
         * margin on a slower host rather than a higher rate on this one — a
         * longer run contains a shorter one.
         */
        const val FANOUT = 16
        const val QUICK_ROUNDS = 600
        const val FULL_ROUNDS = 10_000

        /**
         * How long a round may take, counted from before its closers start.
         * Not a schedule — a round takes single-digit milliseconds — but the
         * bound that turns a wedge into a report.
         */
        val ROUND_BUDGET = 30.seconds

        /** How long each wait blocks before the budget above is re-checked. */
        const val POLL_MS = 50

        fun stressMode(): String? = getenv("KEEL_STRESS")?.toKString()
    }
}

/** Smallest thing worth asking for; this is a liveness probe, not a size test. */
private const val PROBE_SIZE = 64

/** What a closed allocator says, and the only refusal this case reads as closed. */
private const val CLOSED_MESSAGE = "allocator is closed"

/** What a carve says when the arena lock under it was destroyed. */
private const val DEAD_ARENA_MESSAGE = "pthread_mutex_lock() failed"

/**
 * True while this allocator still hands out buffers.
 *
 * Only the closed check may answer "closed", the same rule the sibling
 * concurrent-child case states: a carve refused by a destroyed arena lock
 * is not a closed allocator, and reading it as one would let this case
 * pass on a destroyed arena rather than a closed allocator. That branch is
 * not expected to fire
 * here — every caller asks after a `close()` returned on its own thread, so
 * the closed check answers first — but it is written the same way as the
 * sibling, because the two answering the same question differently is the
 * thing worth removing.
 */
private fun BufferAllocator.stillAllocates(): Boolean =
    try {
        allocate(PROBE_SIZE).release()
        true
    } catch (refused: IllegalStateException) {
        val message = refused.message ?: throw refused
        when {
            message.contains(CLOSED_MESSAGE) -> false
            message.contains(DEAD_ARENA_MESSAGE) -> true
            else -> throw refused
        }
    }
