package io.github.fukusaka.keel.buf

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * What happens when more than one thread asks the same allocator for a child.
 *
 * The configuration that hands an allocator to an engine says the parent stays
 * borrowed and can be shared across engines, and every engine derives its
 * working allocators at construction. Two engines built at once on a shared
 * parent therefore ask it for children concurrently, through public API, with
 * nothing telling the caller to serialise.
 *
 * The parent tracks what it handed out so it can close them. A child dropped on
 * the way into that list is one the parent will never close — a leak whose
 * cause is invisible at the point it shows up. On Kotlin/Native the same race
 * has a harsher form, covered by the stress test next to this one.
 */
class PooledAllocatorConcurrentChildTest {

    @Test
    fun `children asked for from several threads at once are all closed by the parent`() {
        repeat(ROUNDS) { round ->
            val parent = defaultAllocator()
            val handedOut = Collections.synchronizedList(mutableListOf<BufferAllocator>())
            val startTogether = CyclicBarrier(FANOUT)
            val askers = List(FANOUT) { i ->
                workerThread("asker-$i-round-$round") {
                    startTogether.await()
                    val child = parent.createChild()
                    // One buffer through it, so the probe below is served from
                    // this child's own freelist. Its Native sibling has to do
                    // this — closing the parent destroys the shared arena's
                    // lock there — and while the JVM arena still serves a
                    // carve after close, a probe that depends on that is one
                    // change away from answering "closed" for every child.
                    child.allocate(PROBE_SIZE).release()
                    handedOut += child
                }
            }

            askers.startAndJoinWithin("asking for children in round $round")
            val cameBack = handedOut.size
            parent.close()

            // Reported in the round it is seen, as its Native sibling is. Kept
            // to the end, it was thrown away by the count check below: the same
            // unguarded append that drops a child also kills an asker inside
            // `add`, and when it did, the run ended without naming a single
            // round that had already leaked — measured, 5 of 12 runs.
            val open = handedOut.toList().count { child -> child.acceptsAllocations() }
            if (open > 0) {
                fail("the parent lost $open of the children it handed out in round $round")
            }

            // After the leak, because a short list is the milder symptom of the
            // same defect and should not speak over it.
            assertEquals(
                FANOUT,
                cameBack,
                "every asking thread has to come back with a child in round $round, or the count " +
                    "above is of a shorter list rather than of what was handed out",
            )
        }
    }

    /**
     * A second property, and its own case so that neither masks the other: the
     * first thing an unguarded list breaks is the index, deterministically in
     * the first round, which would otherwise be all a reader ever sees.
     *
     * This is what the parent's lock is held *across* rather than what it
     * protects. Reading the index outside the lock and appending inside leaves
     * the other case green while handing the same index to more than one child.
     * How many share one moves with the host and the run, and which of two
     * narrowings is tighter reverses between machines — so what this asserts is
     * the property, that every index is its own, and not a count. A count would
     * also be unreadable from here: a run in which nothing shared passes and
     * prints nothing, so the range would be taken from failures only.
     * Children that share an index share an arena shard, and its lock with it.
     */
    @Test
    fun `children asked for from several threads at once each get an index of their own`() {
        repeat(INDEX_ROUNDS) { round ->
            val parent = defaultAllocator()
            val handedOut = Collections.synchronizedList(mutableListOf<BufferAllocator>())
            val startTogether = CyclicBarrier(FANOUT)
            val askers = List(FANOUT) { i ->
                workerThread("asker-$i-round-$round") {
                    startTogether.await()
                    handedOut += parent.createChild()
                }
            }

            askers.startAndJoinWithin("asking for children in round $round")
            val shards = handedOut.toList().map { (it as PooledAllocator).shardIndex }
            parent.close()

            assertEquals(
                (0 until FANOUT).toList(),
                shards.sorted(),
                "each child gets an index of its own; got $shards in round $round",
            )
        }
    }

    /**
     * The third property, and the one nothing here pinned until it was looked
     * for: a child cannot join a list its parent has already taken its snapshot
     * from.
     *
     * `close` sets the flag before it takes the lock, so an asker that wins the
     * lock is still caught by the snapshot and one that loses it reads the flag
     * across the release. Removing the check inside the lock leaves that to the
     * unsynchronised one above it, and the whole module stays green — measured,
     * which is what made this worth its own case.
     *
     * **The closer waits for the first child and no further.** An earlier shape
     * held it back until most of the children existed, which appeared to help
     * and did not: what helped was catching the askers that were dying. It
     * waits for the first because a closer let go at the barrier can win a
     * round outright, and a round won before anyone asks tests nothing.
     *
     * Each asker also lists its child before warming it, which is a smaller
     * thing than it once read as. Measured, warming first detects too; what
     * the ordering avoids is a child handed out and never listed, not a
     * detection.
     *
     * Measured against the mutation on an **idle** 32-core Linux host, 200
     * rounds, 10 runs per shape, with the build cache off: **10 of 10 on all
     * cores, on four pinned ones, and on two**, every one of them reported by
     * the named assertion below. Healthy code failed none of 20 runs at each of
     * the same three shapes, and none of a further 10 on four cores under 32
     * spinners. Idle is the qualifier that matters, though: on four cores
     * against eight times oversubscription the same mutation is caught about
     * half the time — three sittings gave 5 of 10, 10 of 10 and 11 of 20, which
     * is a quantity too loose to write down more precisely than that. A loaded
     * runner is the shape this is weakest on.
     *
     * Reading the *right* number out of that took two goes. An earlier probe
     * rethrew the other refusal a lost child raises, and counting only the
     * named assertion scored that shape well below its true rate — every run
     * it scored as a miss had failed too, on a bare retain failure naming no
     * round and no child. How large a share arrives that way moves with the
     * host and is not worth a number: what holds is that it is a large share
     * once the cores are few, and none of it at 32. The leaked child is the
     * one whose asker also lost the warm-up, so its freelist is empty and the
     * probe reaches the arena the parent destroyed; the probe now reads that
     * as open, which it is, since a child the parent had closed would have
     * been refused by its own closed check first. Detection did not change:
     * the reporting did.
     */
    @Test
    fun `a child cannot join a parent that has already taken its snapshot to close`() {
        var roundsWithChildren = 0
        repeat(CLOSE_RACE_ROUNDS) { round ->
            val parent = defaultAllocator()
            val handedOut = Collections.synchronizedList(mutableListOf<BufferAllocator>())
            val startTogether = CyclicBarrier(FANOUT + 1)
            val asked = CountDownLatch(1)
            val stopped = Collections.synchronizedList(mutableListOf<String>())
            val askers = List(FANOUT) { i ->
                workerThread("asker-$i-round-$round") {
                    startTogether.await()
                    // A refusal is the other correct answer, and the one the
                    // askers that arrive late are supposed to get. Everything
                    // else is recorded rather than thrown: a daemon dying here
                    // leaves only a stderr trace, and the join fails on threads
                    // still running, so a death would otherwise read the same
                    // as a refusal. Caught as `Throwable` because the deaths
                    // this defect produces are not refusals at all — an index
                    // fault out of `ArrayList.insertAtInternal` on Native, a
                    // null slot from a racing growth on the JVM.
                    val child =
                        try {
                            parent.createChild()
                        } catch (stoppedHere: Throwable) {
                            val message = (stoppedHere as? IllegalStateException)?.message
                            if (message?.contains(CLOSED_MESSAGE) != true) {
                                stopped += "createChild: $stoppedHere"
                            }
                            null
                        }
                    if (child != null) {
                        // Listed before it is used, so that a warm-up losing
                        // its race to the close cannot leave a child the parent
                        // handed out unlisted. It does not change what the case
                        // catches — measured, warming first detects too — only
                        // whether the child that leaked is among the ones the
                        // probe below gets to see.
                        handedOut += child
                        asked.countDown()
                        try {
                            child.allocate(PROBE_SIZE).release()
                        } catch (raced: Throwable) {
                            // Two ways of losing to the close are expected: the
                            // child was cascade-closed before the allocate, or
                            // the allocate was already carving when the shared
                            // arena went. Either leaves the probe below reading
                            // a closed child as closed. Anything else is news —
                            // including a death, which is why this is not
                            // narrowed to the refusal type.
                            val message = (raced as? IllegalStateException)?.message
                            val expected = message?.let { it.contains(CLOSED_MESSAGE) || it.contains(RELEASED_MESSAGE) }
                            if (expected != true) stopped += "warm-up: $raced"
                        }
                    }
                }
            }
            // Waits for the first child and no further. Waiting for a quorum
            // removes the askers that could still arrive late, which is what
            // the mutated check needs; not waiting at all lets the closer win
            // a round outright, and a round it wins before anyone asks tests
            // nothing. One run on four cores was seen in which a closer let
            // go at the barrier won every round of the two hundred; a later
            // independent 120 runs did not reproduce it, so no rate is stated.
            val closer = workerThread("closer-round-$round") {
                startTogether.await()
                asked.await(ROUND_BUDGET_MILLIS, TimeUnit.MILLISECONDS)
                // Recorded for the same reason an asker's failure is, and more
                // so: this is the thread the defect kills most easily, and a
                // `close` that dies part-way leaves every child it had not
                // reached open — which the probe below reads as the leak. The
                // report would name the defect under test and the trace would
                // go only to system-err, where the console never shows it.
                try {
                    parent.close()
                } catch (died: Throwable) {
                    stopped += "closer: $died"
                }
            }

            (askers + closer).startAndJoinWithin("asking while the parent closes in round $round")

            // A closer that died is read before anything else: it did not
            // finish closing, so every child it had not reached is open, and
            // the leak below would report the defect under test on the
            // strength of a `close` that never ran.
            val closerDied = stopped.toList().filter { it.startsWith("closer:") }
            assertEquals(emptyList(), closerDied, "the closer stopped in round $round")

            // Then the leak, which is the defect under test, ahead of an asker
            // stopped on the way — the lesser news of the two.
            val open = handedOut.toList().count { child -> child.acceptsAllocations() }
            if (open > 0) {
                fail("$open of the children handed out during the close are still open in round $round")
            }
            assertEquals(
                emptyList(),
                stopped.toList(),
                "an asker stopped for a reason other than the refusal under test in round $round",
            )
            if (handedOut.isNotEmpty()) roundsWithChildren++
        }

        // Counted per round rather than summed over them. Summing the children
        // and asking for one could only fire if every round of the two hundred
        // handed out nothing, so a hundred and ninety-nine vacuous rounds would
        // pass in silence. Counting rounds is the quantity that says the check
        // under test was reachable. Half rather than all, because a vacuous
        // round is possible in principle — the closer's wait for the first
        // child can expire — and demanding all of them would make that a
        // failure. How nearly impossible it is was measured twice and the two
        // sittings do not agree: one vacuous round in about twelve hundred on
        // one host, none in twenty-nine thousand across both. Neither supports
        // a rate, and the threshold does not need one.
        assertTrue(
            roundsWithChildren >= CLOSE_RACE_ROUNDS / 2,
            "the closer won $roundsWithChildren of $CLOSE_RACE_ROUNDS rounds outright, so most rounds " +
                "never reached the check under test",
        )
    }

    /**
     * True while this allocator still hands out buffers — that is, has not been
     * closed.
     *
     * Only the closed check may answer "closed". Reading any refusal as closed
     * is what made the Native case next to this one assert something it could
     * not observe. The other refusal a lost child can raise says the opposite:
     * a child that got past its own closed check and then failed to carve out
     * of the arena the parent tore down is a child the parent never closed. That is how a large share of leaks arrive once the cores
     * are few — the leaked child is the one whose asker also lost the warm-up,
     * so its freelist is empty and the probe reaches the dead arena. How large
     * a share moves with the host, so it is not given a number here; what
     * rethrowing it cost is the point, and that was reporting those leaks as a
     * bare retain failure naming no round and no child. Its Native sibling
     * classifies the same way, on the same reasoning, for the failure that
     * arena raises there — though that case closes after its askers return, so
     * the branch is consistency rather than teeth.
     */
    private fun BufferAllocator.acceptsAllocations(): Boolean =
        try {
            allocate(PROBE_SIZE).release()
            true
        } catch (refused: IllegalStateException) {
            val message = refused.message ?: throw refused
            when {
                message.contains(CLOSED_MESSAGE) -> false
                message.contains(RELEASED_MESSAGE) -> true
                else -> throw refused
            }
        }

    private companion object {
        /**
         * Enough concurrency and repetition to make the failure near-certain:
         * against the unguarded list this case failed on every one of 10 runs.
         * Kept in this envelope so it stays cheap enough for the ordinary
         * suite. Measured on both gate hosts, three runs each with the build
         * cache off, on idle hosts, and again in later sittings: this case
         * costs 0.15-0.17 s on a 32-core Linux host and 0.17-0.20 s on a
         * 10-core macOS one, the close race 0.15-0.18 s and 0.20-0.23 s, and
         * the index case 0.016-0.021 s. Under load they cost orders of
         * magnitude more — tens of seconds at 24 spinners a core — which is
         * what the numbers above are not. The index breaks in the first round
         * rather than in some of them, so it needs almost none of the
         * repetition the other two are sized for.
         *
         * These are the numbers for all three cases here; the commit message
         * and the pull request point at this rather than repeating them,
         * because carrying the same figures on four surfaces is what left
         * three of those surfaces stale.
         */
        const val FANOUT = 16
        const val ROUNDS = 200

        /** The index breaks in the first round, so this needs only enough to be sure. */
        const val INDEX_ROUNDS = 20

        /**
         * Enough rounds for a window only some rounds open — the numbers behind
         * this are in the case's own documentation.
         */
        const val CLOSE_RACE_ROUNDS = 200

        /** How long the closer waits for the first child before closing anyway. */
        const val ROUND_BUDGET_MILLIS = 200L

        /** Smallest thing worth asking for; this is a liveness probe, not a size test. */
        const val PROBE_SIZE = 64

        /** What a closed allocator says, and the only refusal this case reads as closed. */
        const val CLOSED_MESSAGE = "allocator is closed"

        /** What a buffer says when the arena under it went while it was carving. */
        const val RELEASED_MESSAGE = "released buffer"
    }
}
