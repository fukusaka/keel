package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What [AbstractIoTransport.markTeardownStarted] promises: it answers `true`
 * to exactly one caller.
 *
 * The claim is the only thing collapsing concurrent teardowns. `markClosing`
 * is deliberately not a compare-and-swap — its own KDoc says two callers may
 * both see `opened = true` and both return `true` — and the loop hand-off
 * builds its claim per call, so two threads closing at once reach the teardown
 * body twice. What they must not do is run it twice: the body closes the
 * descriptor, and a second close is either a logged `EBADF` or, once the
 * number has been handed on, somebody else's socket.
 *
 * Nothing pinned that. Measured on 2026-08-11 against the whole of
 * `keel-core` and the kqueue engine: replacing the compare-and-swap with a
 * plain read-then-write left both suites green, and so did deleting the claim
 * altogether (`= true`). This file closes the second of those; the first needs
 * a real race and lives in `TeardownClaimStress`.
 *
 * Deterministic, because "answers `true` to exactly one caller" does not need
 * two threads to be violated — a claim that has forgotten it was taken fails
 * on the second call from the same one.
 */
class TeardownClaimTest {

    /** Exposes the protected claim so a test can ask it directly. */
    private class ClaimingTransport : TestIoTransport() {
        fun claim(): Boolean = markTeardownStarted()
    }

    @Test
    fun `the teardown claim is answered once`() {
        val transport = ClaimingTransport()

        assertTrue(transport.claim(), "the first teardown owns the cleanup pass")
        assertFalse(transport.claim(), "a second teardown must find the pass already claimed")
    }

    @Test
    fun `the teardown claim stays taken however many times it is asked`() {
        // A claim that resets, or that is re-armed by anything else on the
        // transport, would let a late teardown run the body over resources the
        // first pass already released.
        val transport = ClaimingTransport()
        transport.claim()

        repeat(TEARDOWN_ATTEMPTS) {
            assertFalse(transport.claim(), "attempt ${it + 2} must still find the pass claimed")
        }
    }

    @Test
    fun `closing the transport takes the claim`() {
        // The claim is not decoration on the side: a close is what takes it, so
        // a teardown arriving afterwards finds it gone.
        //
        // The close here is [TestIoTransport]'s, a double -- no engine's close
        // path is pinned by this file. Nor by the stress suite beside it, which
        // races the claim on the same double: each engine's own
        // `if (!markTeardownStarted()) return` is reached only by two closers
        // racing, or by one racing the drain a stopping loop still runs, and
        // nothing here produces either. That line is unpinned on every
        // platform.
        val transport = ClaimingTransport()
        transport.close()

        assertFalse(transport.claim(), "close() runs the teardown, so the claim is spent")
    }

    private companion object {
        /** Enough repeats to catch a claim that resets on a cycle rather than once. */
        const val TEARDOWN_ATTEMPTS = 8
    }
}
