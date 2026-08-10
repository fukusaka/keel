package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.testing.transport.TestIoTransport
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * That [AbstractIoTransport.markTeardownStarted] answers exactly one caller
 * **when they ask at the same time**.
 *
 * [TeardownClaimTest] pins that the claim is answered once and stays taken;
 * neither needs two threads, and neither can tell a compare-and-swap from a
 * plain read-then-write. Only a race can, because the two differ across two
 * instructions: read zero, read zero, write one, write one — and both callers
 * leave believing they own the cleanup pass.
 *
 * Reached rather than assumed: `markClosing` is deliberately not a
 * compare-and-swap, and the loop hand-off builds its claim per call, so two
 * threads closing one transport at once genuinely arrive at the teardown body
 * together. This exercises the arrival directly instead of through a close,
 * because a close would first have to win `markClosing`'s own two-instruction
 * race for the second caller to get this far — which would make the run mostly
 * about that window rather than this one.
 *
 * **Gating**: each `@Test` returns early unless `KEEL_STRESS` is set — `quick`
 * runs the quick scenario, `full` runs both. A race that needs an interleaving
 * inside two instructions is not something the main gate should be asked to
 * reproduce on every run, so it rides the engine stress workflow instead.
 *
 * **Detection power, measured 2026-08-11** (a 10-core arm64 macOS host, quick
 * scenario). As a plain read-then-write the scenario failed on every run; the
 * round it failed on is a draw, not a requirement, and varied widely — seven
 * runs landed on rounds 0, 416, 530, 827, 1,413, 4,211 and 10,064. [QUICK_ROUNDS]
 * is set well above the worst of those rather than at a computed margin. With
 * the compare-and-swap every run is green. Deleting the claim outright is caught
 * by [TeardownClaimTest] instead, on the first assertion and without threads.
 *
 * **Not measured on the host that runs it.** The workflow runs this on the Linux
 * gate runner, whose core count and preemption profile differ from the machine
 * above; whether the window is wider or narrower there is unobserved.
 */
class TeardownClaimStress {

    /** Exposes the protected claim so the race can ask it directly. */
    private class ClaimingTransport : TestIoTransport() {
        fun claim(): Boolean = markTeardownStarted()
    }

    /**
     * [QUICK_ROUNDS] rounds. Runs on the engine PR gate (`KEEL_STRESS=quick`),
     * measured at ~7s.
     */
    @Test
    fun quick() {
        if (stressMode() !in setOf("quick", "full")) return
        raceForTheClaim(QUICK_ROUNDS)
    }

    /**
     * [FULL_ROUNDS] rounds, for the manually dispatched run
     * (`KEEL_STRESS=full`). Same race, more chances at it.
     */
    @Test
    fun full() {
        if (stressMode() != "full") return
        raceForTheClaim(FULL_ROUNDS)
    }

    /**
     * Runs [rounds] fresh transports, each claimed by [THREADS] threads
     * released from one barrier, and fails on the first round that hands the
     * cleanup pass to more than one of them.
     */
    private fun raceForTheClaim(rounds: Int) {
        repeat(rounds) { round ->
            val transport = ClaimingTransport()
            val winners = AtomicInteger(0)
            // A barrier rather than plain starts: threads that begin at
            // whatever moment the scheduler gives them mostly do not overlap,
            // and the window being tested is two instructions wide.
            val start = CyclicBarrier(THREADS)
            val threads = List(THREADS) {
                Thread {
                    // Bounded, because the wait is mutual: a thread that never
                    // starts -- `quick` asks for 160,000 of them -- leaves the
                    // ones already parked here waiting for a partner that will
                    // not arrive, and the join below waiting for them.
                    start.await(ROUND_BUDGET_SECONDS, TimeUnit.SECONDS)
                    if (transport.claim()) winners.incrementAndGet()
                }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join(ROUND_BUDGET_SECONDS * MILLIS_PER_SECOND) }

            // Split, because `winners == 0` and `winners > 1` are opposite
            // failures and only the second is about the claim: nobody claiming
            // means the round never ran, and reporting that as a claim defect
            // would send the reader to the wrong file.
            assertTrue(
                winners.get() >= 1,
                "round $round claimed nothing: the threads did not reach the claim, so it was not tested",
            )
            assertEquals(
                1,
                winners.get(),
                "round $round handed the cleanup pass to ${winners.get()} teardowns; " +
                    "the body closes the descriptor, so every extra one is a second close(2)",
            )
        }
    }

    private companion object {
        /**
         * Claimers per round. Enough to keep several runnable at once on both
         * the machine this was measured on (10 cores) and the gate runner
         * (fewer), without the barrier release itself becoming the cost.
         */
        const val THREADS = 8

        /** Rounds on the PR gate: ~7s, and enough to fail a plain read-then-write with margin. */
        const val QUICK_ROUNDS = 20_000

        /** Rounds for the manually dispatched run. */
        const val FULL_ROUNDS = 500_000

        /** Wall-clock bound per round; generous, since it only has to exclude a hang. */
        const val ROUND_BUDGET_SECONDS = 30L

        const val MILLIS_PER_SECOND = 1_000L

        fun stressMode(): String? = System.getenv("KEEL_STRESS")
    }
}
