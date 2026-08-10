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
 * scenario). As a plain read-then-write the scenario failed on every run of 31,
 * always with two winners; the round it failed on is a draw rather than a
 * requirement and ranged from 0 to 14,209, mean ≈ 5,000. [QUICK_ROUNDS] is not a
 * margin over the worst draw — draws are unbounded — but a budget chosen against
 * that mean: at 50,000 rounds a broken claim escapes roughly one run in 20,000,
 * for ~16s. `full` takes that to the point of irrelevance. With the
 * compare-and-swap every run is green, and deleting the claim outright is caught
 * by [TeardownClaimTest] immediately, without threads.
 *
 * **Not measured on the host that runs it.** The workflow runs this on the Linux
 * gate runner, whose core count and preemption profile differ from the machine
 * above; whether the window is wider or narrower there is unobserved. Runtime
 * does carry: under heavy oversubscription the quick scenario grew ~1.6x, not
 * an order of magnitude.
 */
class TeardownClaimStress {

    /** Exposes the protected claim so the race can ask it directly. */
    private class ClaimingTransport : TestIoTransport() {
        fun claim(): Boolean = markTeardownStarted()
    }

    /**
     * [QUICK_ROUNDS] rounds. Runs on the engine PR gate (`KEEL_STRESS=quick`),
     * measured at ~16s.
     */
    @Test
    fun quick() {
        if (stressMode() !in setOf("quick", "full")) return
        raceForTheClaim(QUICK_ROUNDS)
    }

    /**
     * [FULL_ROUNDS] rounds. Same race, more chances at it — for a developer
     * exporting `KEEL_STRESS=full` by hand, since no workflow sets that value
     * today (the two dispatched stress workflows drive a different switch).
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
                    // Bounded, because the wait is mutual and these threads
                    // are not daemons: a `start()` that fails -- `quick` asks
                    // for 400,000 of them -- throws out of the loop below
                    // before the joins run, and the threads already parked
                    // here would then wait for a partner that will not arrive,
                    // outliving the failed test and holding the test JVM open.
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

        /** Rounds on the PR gate: ~16s, chosen against a mean first failure of ~5,000. */
        const val QUICK_ROUNDS = 50_000

        /** Rounds for the manually dispatched run. */
        const val FULL_ROUNDS = 500_000

        /**
         * Wall-clock bound on each wait, not on a round: the barrier and each
         * join get their own. Generous, since it only has to exclude a hang --
         * the job's own timeout is what bounds the suite.
         */
        const val ROUND_BUDGET_SECONDS = 30L

        const val MILLIS_PER_SECOND = 1_000L

        fun stressMode(): String? = System.getenv("KEEL_STRESS")
    }
}
