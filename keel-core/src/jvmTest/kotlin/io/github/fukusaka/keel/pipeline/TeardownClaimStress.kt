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
 * **Detection power.** As a plain read-then-write the scenario fails on every
 * run observed so far. Two sample sizes appear below and they count different
 * things: the winner count was read off eight runs through the assertion, and
 * every one of those had exactly two — an observation, not a structural
 * guarantee, since eight racers could in principle produce three. The
 * first-failure distribution comes from a larger sweep that recorded only the
 * round. The round it fails on is a draw rather
 * than a requirement, and it is drawn from a different distribution on every
 * host.
 *
 * On an idle 10-core arm64 macOS host, 80 fresh-JVM trials had a mean of
 * ~7,000, a median of ~5,500, and ranged from 138 to 35,488. **Those figures do
 * not carry to the gate.** A deliberately broken claim pushed through the Linux
 * gate runner failed at 24.6s, against a 35.4s full pass on the same rounds and
 * threads. Interpolating puts first failure past round 30,000 of [QUICK_ROUNDS]
 * — derived from two runs on two runner VMs rather than observed, and an
 * over-estimate to the extent that early rounds run slower. Even so it is
 * several times later than the local mean predicts. One observation fixes no
 * rate, and none is quoted here; what it establishes is that the window is open
 * there and that the local numbers are the optimistic ones.
 *
 * So this scenario is a sampler, not a proof: at [QUICK_ROUNDS] a claim quietly
 * downgraded to a plain flag will sometimes reach `main`. The case that must
 * never reach it — the claim not existing at all — is carried with certainty by
 * [TeardownClaimTest], which needs no race. `full` is what raises this half to
 * confidence, and it has to be asked for.
 *
 * **And it reaches one platform.** `AtomicInt` is an `expect` class, so each
 * target has its own `actual`; on the JVM it is `AtomicInteger`, which is what
 * this file exercises. Two of the six transports that ask the claim are JVM
 * (`nio`, `netty`) and are covered by that. The other four are native (`epoll`,
 * `io-uring`, `kqueue`, `nwconnection`), and for those the claim's *atomicity*
 * is unpinned — a downgrade to a plain flag would be caught here or not at all.
 * [TeardownClaimTest], which carries the case that matters most, does run on
 * every target.
 *
 * **Pooling the claimers was tried and dropped.** Reusing eight threads across
 * rounds behind two barriers is much cheaper per round and much less sensitive
 * per round, and at [QUICK_ROUNDS] the second outweighs the first: it detected
 * nothing in the runs made. No ratio is quoted, because the two figures this
 * paragraph carried before did not survive being measured again, and the
 * variant is not in the tree for a reader to check. An earlier note blamed that
 * null result on Gradle serving runs from cache; the round count alone accounts
 * for it, and the caching explanation was never needed.
 */
class TeardownClaimStress {

    /** Exposes the protected claim so the race can ask it directly. */
    private class ClaimingTransport : TestIoTransport() {
        fun claim(): Boolean = markTeardownStarted()
    }

    /**
     * [QUICK_ROUNDS] rounds. Runs on the engine PR gate (`KEEL_STRESS=quick`),
     * measured at ~16s locally and 35.4s on the gate runner.
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
            // whatever moment the scheduler gives them overlap less, and the
            // window being tested is two instructions wide. Fresh threads per
            // round matter too -- pooled claimers are markedly less sensitive
            // per round -- so neither is the single mechanism.
            val start = CyclicBarrier(THREADS)
            val threads = List(THREADS) {
                Thread {
                    // Bounded as defence in depth, with no reachable effect
                    // in this file today: either all eight starts succeed and
                    // the barrier trips, or one fails and the exception leaves
                    // `raceForTheClaim` immediately -- and the worker exits
                    // without waiting for what is parked here, so 30 seconds
                    // never elapse.
                    //
                    // Three earlier attempts at this comment each asserted a
                    // consequence that does not follow: that the joins below
                    // would hang (a failing `start()` throws before they run),
                    // that the parked threads would hold the test JVM open
                    // (measured: the worker exits in ~2s regardless), and that
                    // an unbounded park would be silent where a bounded one is
                    // not (measured: neither says anything, because the JVM is
                    // already gone). The bound is kept because the JVM is not
                    // always this test's alone: Gradle forks one worker per
                    // module by default, so an unfiltered run of this module
                    // shares it and a park left here would outlast this class.
                    // Under the gate's own `--tests` filter it would not.
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

        /** Rounds on the PR gate: ~16s locally, 35.4s on the gate runner. */
        const val QUICK_ROUNDS = 50_000

        /** Rounds for the manually dispatched run. */
        const val FULL_ROUNDS = 500_000

        /**
         * Wall-clock bound on each wait, not on a round: the barrier and each
         * join get their own. Generous, since it only has to exclude a hang --
         * the job's own timeout is what bounds the suite. See the call site for
         * why nothing here reaches it today.
         */
        const val ROUND_BUDGET_SECONDS = 30L

        const val MILLIS_PER_SECOND = 1_000L

        fun stressMode(): String? = System.getenv("KEEL_STRESS")
    }
}
