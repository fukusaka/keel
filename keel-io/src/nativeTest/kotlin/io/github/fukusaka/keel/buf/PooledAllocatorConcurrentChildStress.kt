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
 * The Native form of what the JVM case next to this one covers: an allocator
 * asked for children by several threads at once.
 *
 * Worth its own case because of how the failure arrives here. `ArrayList`
 * raises its length before storing the element, and `get` checks against that
 * length, so a concurrent append leaves a slot the size counts and the store
 * has not reached. The parent's `close` is handed its zero-initialised null,
 * typed as non-null, and dispatches on it.
 *
 * The JVM reaches the same null slot occasionally, but a null dereference
 * there is an exception a case can fail on. Here it can be a segfault that
 * takes the test process with it, and it can equally be the leak report below
 * or an asker saying what stopped it. Which one arrives moves with whatever
 * else the machine is doing — the same binary on the same host has given
 * mostly deaths in one sweep and none at all in another — so the mix is not
 * worth quoting. A case that can report as the whole binary dying does not
 * belong in the ordinary suite, which is why this is gated.
 *
 * **Gating**: returns early unless `KEEL_STRESS` is set (`quick` runs the quick
 * scenario, `full` runs both).
 */
class PooledAllocatorConcurrentChildStress {

    /**
     * One asker's share of a round: wait for the others, take a child, and put
     * one buffer through it.
     *
     * The waiting makes the overlap structural rather than incidental. On idle
     * cores it is not what produces the detections — the shape catches without
     * it, because the rest of a round takes long enough that the askers overlap
     * anyway. That is a property of how much work happens to sit beside the
     * append, which is not a thing to depend on, least of all on a machine
     * whose cores are already busy. With the barrier every asker is at the same
     * point before any of them appends, whatever else the round costs.
     *
     * The buffer matters as much, for a reason that is not about pressure.
     * Closing the parent closes the shared arena, which destroys its lock — so
     * a child the parent *lost* is still open but can no longer carve, and the
     * probe below would get a destroyed-mutex failure rather than a buffer. One
     * allocate-and-release here leaves a buffer in the child's own freelist, so
     * the probe is served from there and never reaches the arena.
     */
    private class Ask(
        val parent: BufferAllocator,
        val arrived: AtomicInt,
        val fanout: Int,
        val warmSize: Int,
        val startedAt: TimeSource.Monotonic.ValueTimeMark,
        val budget: Duration,
    ) {
        /**
         * Wait for the other askers, take a child, put one buffer through it.
         *
         * Everything is caught, because the point of the [Outcome] is that
         * this side of the future is where a description can still be made.
         * A method here rather than beside the caller so the worker's lambda
         * captures nothing but its own argument.
         */
        @Suppress("TooGenericExceptionCaught")
        fun takeChild(): Outcome =
            try {
                arrived.fetchAndAdd(1)
                // Yield rather than spin: on a machine with fewer cores than
                // askers, a busy wait keeps the ones still to arrive off the
                // CPU and the round costs more than it catches.
                while (arrived.load() < fanout) {
                    check(startedAt.elapsedNow() < budget) {
                        "an asker never reached the barrier: ${arrived.load()} of $fanout arrived"
                    }
                    sched_yield()
                }
                val child = parent.createChild()
                child.allocate(warmSize).release()
                Outcome(child, null)
            } catch (stopped: Throwable) {
                Outcome(null, stopped.stackTraceToString())
            }
    }

    /**
     * What an asker brings back: a child, or a description of what stopped it.
     *
     * Carried rather than thrown, because an exception crossing a worker's
     * future arrives on this side as a bare `IllegalStateException` with no
     * message — measured: the branch in `Future.consume` that would name it is
     * behind a consume that throws first. So an asker dying inside the list
     * append, which is the defect, and an asker starved past its budget, which
     * is the machine, reported identically in the file this workflow uploads.
     *
     * The description keeps the stack, since a class name alone does not say
     * where in the append it happened.
     */
    private class Outcome(val child: BufferAllocator?, val failure: String?)

    @Test
    fun quick() {
        if (stressMode() !in setOf("quick", "full")) return
        askConcurrently(rounds = QUICK_ROUNDS)
    }

    @Test
    fun full() {
        if (stressMode() != "full") return
        askConcurrently(rounds = FULL_ROUNDS)
    }

    /**
     * [rounds] rounds of [FANOUT] threads each asking one parent for a child,
     * then one close of that parent. Asserts what the parent promises: every
     * child it handed out is closed when it is.
     */
    private fun askConcurrently(rounds: Int) {
        // Started once rather than per round: starting and terminating threads
        // is most of what a round would otherwise cost, and none of it is the
        // race. Each round gets a fresh parent and a fresh arrival counter, so
        // the workers carry nothing between them.
        val workers = List(FANOUT) { Worker.start() }
        try {
            repeat(rounds) { round ->
                val parent = defaultAllocator()
                val arrived = AtomicInt(0)
                val startedAt = TimeSource.Monotonic.markNow()
                val asking = workers.map { worker ->
                    val ask = Ask(parent, arrived, FANOUT, PROBE_SIZE, startedAt, ROUND_BUDGET)
                    worker.execute(TransferMode.SAFE, { ask }) { job -> job.takeChild() }
                }

                // The bound belongs here rather than only on the barrier. An
                // asker wedged *after* the barrier — in the lock this change
                // adds, say — passes it and then waits here forever, which is
                // the job running out with nothing reported. Looped because
                // the wait returns as soon as *any* one asker is ready, not
                // when they all are.
                val waiting = asking.toMutableSet()
                while (waiting.isNotEmpty()) {
                    if (startedAt.elapsedNow() >= ROUND_BUDGET) {
                        fail(
                            "only ${asking.size - waiting.size} of ${asking.size} askers came back " +
                                "in round $round",
                        )
                    }
                    waiting -= waitForMultipleFutures(waiting.toList(), POLL_MS)
                    // That wait only ever hands back futures that completed. One
                    // that ended by throwing would stay in the set until the
                    // budget ran out and then be reported as starvation, which is
                    // the wrong answer about the right event.
                    waiting.removeAll { it.state != FutureState.SCHEDULED }
                }
                val outcomes = asking.map { it.result }
                outcomes.firstOrNull { it.failure != null }?.let { stopped ->
                    fail("an asker stopped in round $round: ${stopped.failure}")
                }
                val handedOut = outcomes.mapNotNull { it.child }

                parent.close()

                // Reported here rather than collected and asserted at the end:
                // a later round that crashes would otherwise take the process
                // before anything said what this one saw, which is how the
                // crash came to be the only reporter this case ever had.
                val lost = handedOut.count { child -> child.acceptsAllocations() }
                if (lost > 0) {
                    fail("the parent lost $lost of the children it handed out in round $round")
                }
            }
        } finally {
            // Bounded for the same reason: a wedged worker must not turn a
            // reported failure into a job that never ends. Whatever it was
            // has already been said by the time this runs.
            val stopping = workers.map { it.requestTermination() }.toMutableSet()
            val stoppingSince = TimeSource.Monotonic.markNow()
            while (stopping.isNotEmpty() && stoppingSince.elapsedNow() < ROUND_BUDGET) {
                val stopped = waitForMultipleFutures(stopping.toList(), POLL_MS)
                // The documented obligation: a termination future not joined
                // leaks its thread handle. Joining the ones handed back here
                // cannot block, since the wait only hands back completed ones.
                // A worker still wedged at the budget is left unjoined on
                // purpose — the case has already reported why.
                stopped.forEach { it.result }
                stopping -= stopped
            }
        }
    }

    /**
     * True while this allocator still hands out buffers — that is, has not been
     * closed.
     *
     * Only the closed check may answer "closed", and reading anything else as
     * closed is how this case once spent a round asserting something it could
     * not observe. The destroyed arena lock says the opposite, for the reason
     * its JVM sibling reads a released buffer that way: a child the parent had
     * closed would have been turned away by its own closed check first, so a
     * child that got past that check and then could not carve is a child the
     * parent never closed. That is the leak, and rethrowing it would report the
     * leak as an unnamed lock failure instead.
     *
     * This case closes only after every asker has returned, so it does not race
     * a close and the warm-up above keeps the probe off the arena anyway — the
     * branch is not expected to fire here. It is written the same way as its
     * JVM sibling because the two answering the same question differently is
     * the thing worth removing, not because a detection was measured to hang
     * on it.
     */
    private fun BufferAllocator.acceptsAllocations(): Boolean =
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

    private companion object {
        /**
         * Calibrated on two CPUs **that are also busy**, which is the machine
         * this has to catch the defect on: a hosted runner has an agent and a
         * Gradle daemon on the same cores. Two idle cores was the earlier
         * calibration and it flattered every shape — four askers caught 30 of
         * 30 there, and 20 of 30 once the cores had other work, which is a
         * guard that misses one regression in three.
         *
         * On the shapes a runner actually presents this catches everything
         * measured: against the reverted fix, 30 of 30 on two idle cores, on
         * four cores under load, and on the macOS host both idle and loaded.
         *
         * Two cores *and* heavy load is the tight end, and there the rate is
         * not a stable number to quote — sweeps of thirty with two spinners on
         * the same two cores have come back at five, fourteen, twenty-eight and
         * thirty, and four spinners push it lower still. What holds across every
         * contended cell measured is the comparison the constant rests on:
         * sixteen askers detects where four does not, by 30 to 28, 30 to 24 and
         * 15 to 5 when the two are run alternately so that drift cancels. More
         * rounds cannot catch less, a longer run containing a shorter one, so
         * the rounds buy margin on a slower machine rather than a higher rate
         * on this one.
         */
        const val FANOUT = 16
        const val QUICK_ROUNDS = 600
        const val FULL_ROUNDS = 10_000

        /** Smallest thing worth asking for; this is a liveness probe, not a size test. */
        const val PROBE_SIZE = 64

        /** What a closed allocator says, and the only refusal this case reads as closed. */
        const val CLOSED_MESSAGE = "allocator is closed"

        /** What a carve says when the arena lock under it was destroyed with the parent. */
        const val DEAD_ARENA_MESSAGE = "pthread_mutex_lock() failed"

        /**
         * How long a round may take, counted from before its askers start and
         * covering both the barrier and the wait for them to come back. Not a
         * schedule — a round takes single-digit milliseconds — but a bound, so
         * a wedged asker fails this case rather than running out the job it is
         * part of.
         */
        val ROUND_BUDGET = 30.seconds

        /** How long each wait blocks before the budget above is re-checked. */
        const val POLL_MS = 50

        fun stressMode(): String? = getenv("KEEL_STRESS")?.toKString()
    }
}
