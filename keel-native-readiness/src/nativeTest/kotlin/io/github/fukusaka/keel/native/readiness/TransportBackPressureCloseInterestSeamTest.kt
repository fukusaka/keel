@file:OptIn(InternalReadinessEngineApi::class)

package io.github.fukusaka.keel.native.readiness

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a connection keeps when its reads are turned off.
 *
 * The arm a channel attach makes is one-shot, so whatever happens on the first
 * wake decides what the connection can still hear. A wake that finds reads
 * disabled used to return, and the loop then withdrew the interest — which is
 * correct for the data (a level-triggered arm over bytes nobody is taking wakes
 * on every turn) and wrong for the close, because the same registration carried
 * both. A client that never enables reads at all would sit in CLOSE-WAIT until
 * the keep-alive timer, hours later.
 *
 * The replacement is a narrower arm rather than none: one the peer's FIN wakes
 * and arriving data does not. These cases pin the two halves that make that
 * work — that the transport asks for it, and that asking is what stops the loop
 * withdrawing the interest underneath it.
 *
 * Written at the seam because that is where the two are separable. Whether the
 * kernel honours the narrowing is each engine's, and is measured against the
 * real one.
 */
@OptIn(ExperimentalForeignApi::class)
internal class TransportBackPressureCloseInterestSeamTest : TransportSeamFixture() {

    @Test
    fun `a wake that finds reads disabled re-arms for the close alone`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            val transport = transport()
            try {
                transport.onChannelAttached()
                // The attach arms in full: reads may yet be enabled, and until
                // something wakes it there is nothing to narrow.
                assertTrue(
                    eventLoop.closeOnlyArms.isEmpty(),
                    "the attach arms for data as well -- narrowing before anything asked would give up " +
                        "reads the caller has not declined",
                )

                // Data arrives while reads are off: the back-pressure path.
                eventLoop.dispatchReadyFor(fd, Interest.READ, eofFlag = false)

                assertEquals(
                    listOf(fd to Interest.READ),
                    eventLoop.closeOnlyArms,
                    "the wake re-arms narrowed, so the close this connection has not heard yet can still " +
                        "reach it",
                )
            } finally {
                transport.close()
            }
        }
    }

    @Test
    fun `the re-arm is what stops the loop withdrawing the interest`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The half that is easy to lose. Registering from inside the
            // dispatch is not decoration: the loop asks whether the ledger
            // holds a listener *after* the callback returns, and withdraws the
            // interest when it does not. Re-arm somewhere later -- a queued
            // task, the next turn -- and the withdrawal has already happened,
            // leaving nothing armed and the narrowing pointless.
            val transport = transport()
            try {
                transport.onChannelAttached()
                eventLoop.dispatchReadyFor(fd, Interest.READ, eofFlag = false)

                assertTrue(
                    eventLoop.holdsCallback(fd, Interest.READ),
                    "the ledger still holds this connection's listener when the dispatch finishes -- that " +
                        "is what the loop reads to decide whether to withdraw",
                )
            } finally {
                transport.close()
            }
        }
    }

    @Test
    fun `a second declined wake does not arm again`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The other way the narrowing can become a spin, and the one the
            // close report cannot catch.
            //
            // A wake arriving *on* the narrowed arm is one the narrowing did
            // not intend: it asked the kernel for the close, and a close
            // reports through onPeerClosed, which withdraws. Anything else that
            // raises it is a condition the narrowing cannot quieten, so arming
            // into it again is the same busy loop by another door.
            //
            // Reachable on one kernel. kqueue has no EOF filter of its own, so
            // the narrowing is a low-water mark, clamped to the receive
            // buffer's high-water mark. TCP stalls its sender short of that; a
            // unix-domain socket has no window to stall it with, so a full
            // buffer meets the mark and wakes with no EOF. Review measured that
            // at 1.09 CPU seconds per wall-clock second.
            //
            // Not arming leaves the ledger empty, so the loop withdraws the
            // interest and that connection stops hearing the close -- which is
            // what every declined wake did before the narrowing existed, so it
            // is where the improvement stops rather than a loss it causes.
            //
            // Asserted on the count of narrowing arms, because that is the
            // thing that must not grow once per wake.
            val transport = transport()
            try {
                transport.onChannelAttached()
                eventLoop.dispatchReadyFor(fd, Interest.READ, eofFlag = false)
                assertEquals(1, eventLoop.closeOnlyArms.size, "precondition: the first decline narrows")

                // A second wake with no close on it -- what a full receive
                // buffer produces where the mark can be met.
                eventLoop.dispatchReadyFor(fd, Interest.READ, eofFlag = false)

                assertEquals(
                    1,
                    eventLoop.closeOnlyArms.size,
                    "the narrowed arm is issued once per back-pressure episode, not once per wake -- " +
                        "re-arming into a condition it cannot quieten is a busy loop",
                )
            } finally {
                transport.close()
            }
        }
    }

    @Test
    fun `a connection that has read before does not narrow when it pauses`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The line between the two connections that both reach the
            // back-pressure branch, and the reason it is drawn.
            //
            // A connection that has never enabled reads has taken no bytes, so
            // nothing is buffered above it and hearing the close early costs
            // nothing -- that is the whole feature. A consumer that read and
            // then paused at its watermark is the other case: the bridge above
            // it may still hold a queue it is going to hand over, and the close
            // report is destructive there. It releases that queue and answers
            // EOF, and a Pipeline-mode channel closes outright, so a peer that
            // sends more than the watermark and then closes would lose whatever
            // had not been consumed.
            //
            // So that connection keeps the behaviour it had before: the
            // interest is withdrawn, and the close is found when reads resume
            // and the drain reaches EOF.
            val transport = transport()
            try {
                transport.onChannelAttached()
                transport.readEnabled = true
                transport.readEnabled = false

                eventLoop.dispatchReadyFor(fd, Interest.READ, eofFlag = false)

                assertTrue(
                    eventLoop.closeOnlyArms.isEmpty(),
                    "a connection that has read before does not narrow -- the close it would hear early is " +
                        "reported as end of stream, and there may be a queue above it that has not been " +
                        "handed over",
                )
            } finally {
                transport.close()
            }
        }
    }

    @Test
    fun `the wake that carries the close does not leave an arm behind`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The half that turns the narrowing into a spin if it is missed.
            //
            // A peer's close is not an event, it is a *condition*: both loops
            // are level-triggered, so once the FIN is in, every wait reports
            // that fd again. Re-arming is right while the close has not
            // arrived, and wrong the moment it has -- the loop hands the
            // listener the wake, the listener puts itself straight back, the
            // loop finds a listener and keeps the interest, and the condition
            // that produced the wake has not changed. That is 100% of a core
            // for as long as the application leaves the channel open, on the
            // worker serving every other connection too. Measured end to end
            // before this case existed: ~1.15 s of CPU per wall-clock second
            // against 0.0015 s on the unchanged tree.
            //
            // So the interest has to go when the close is reported. Asserted
            // on the ledger rather than on CPU, because the ledger is what the
            // loop reads to decide: it keeps the interest exactly when a
            // listener is still registered after the dispatch returns.
            val transport = transport()
            try {
                transport.onChannelAttached()
                eventLoop.dispatchReadyFor(fd, Interest.READ, eofFlag = false)
                assertTrue(
                    eventLoop.holdsCallback(fd, Interest.READ),
                    "precondition: the declined data wake keeps an arm, which is the point of the change",
                )

                eventLoop.dispatchReadyFor(fd, Interest.READ, eofFlag = true)

                assertFalse(
                    eventLoop.holdsCallback(fd, Interest.READ),
                    "the close is reported and the interest goes with it -- an arm surviving a condition " +
                        "that never clears is a busy loop, not a notification",
                )
            } finally {
                transport.close()
            }
        }
    }

    @Test
    fun `re-enabling reads arms for data again rather than for the close alone`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The way back. Narrowing is only safe because it is reversible: if
            // the setter re-armed narrowed too, a connection that turned reads
            // off once would never wake on data again, and the back-pressure
            // release would silently stop working.
            val transport = transport()
            try {
                transport.onChannelAttached()
                eventLoop.dispatchReadyFor(fd, Interest.READ, eofFlag = false)
                assertEquals(
                    1,
                    eventLoop.closeOnlyArms.size,
                    "precondition: the back-pressure wake narrowed the arm exactly once",
                )

                transport.readEnabled = true

                assertEquals(
                    1,
                    eventLoop.closeOnlyArms.size,
                    "turning reads back on arms for data as well -- a second narrowed arm here would leave " +
                        "the connection deaf to everything but the close",
                )
                assertTrue(
                    eventLoop.holdsCallback(fd, Interest.READ),
                    "and it is armed at all",
                )
            } finally {
                transport.close()
            }
        }
    }
}
