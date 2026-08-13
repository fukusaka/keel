package io.github.fukusaka.keel.native.readiness

import kotlin.test.Test

/**
 * What a loop owes back for its gather scratch, and how many times it owes it.
 *
 * The base allocates two native arrays in its constructor, so every loop holds
 * scratch — including the doubles here, which own no thread and were closing to
 * nothing. Two ways to get this wrong, and they pull in opposite directions:
 * free twice and the process aborts, guard too eagerly and the arrays a later
 * grow allocated are never returned. Both paths free, so both must agree on who
 * still owns them.
 *
 * One test, not four: a double free aborts the whole binary, so a second test
 * would not report anyway — and the ~19 other tests in this module would go
 * with it. Any of the sequences below failing is the same signal.
 */
@OptIn(InternalReadinessEngineApi::class)
internal class ReadinessLoopScratchTest : AbstractReadinessEventLoopFixture() {

    @Test
    fun `scratch is returned exactly once however close and grow interleave`() {
        // Closing twice: the second close owes nothing.
        FakeLoop().apply {
            close()
            close()
        }

        // Grown, then closed: the close owes back what the grow allocated.
        FakeLoop().apply {
            ensureWritevCapacity(GROWN_CAPACITY)
            close()
            close()
        }

        // Closed, then grown: the grow must not free what the close already
        // did, and the close after it owes back what the grow allocated.
        FakeLoop().apply {
            close()
            ensureWritevCapacity(GROWN_CAPACITY)
            close()
        }
    }

    private companion object {
        /** Past the base's initial capacity, so the grow path reallocates. */
        const val GROWN_CAPACITY = 64
    }
}
