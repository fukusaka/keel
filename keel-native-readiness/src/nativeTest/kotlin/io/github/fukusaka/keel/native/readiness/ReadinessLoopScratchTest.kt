package io.github.fukusaka.keel.native.readiness

import kotlin.test.Test

/**
 * What a loop owes back when it is closed, and how many times it may owe it.
 *
 * The base allocates the gather scratch in its constructor, so every loop holds
 * two native arrays — including the doubles here, which own no thread and were
 * closing to nothing. Their `close()` now returns the scratch.
 *
 * That makes the second close the hazard: `nativeHeap.free` of an already-freed
 * pointer is not a no-op, and `close()` is a public obligation anyone may
 * discharge twice. The two production teardown paths are mutually exclusive, so
 * nothing there would have caught it.
 */
@OptIn(InternalReadinessEngineApi::class)
internal class ReadinessLoopScratchTest : AbstractReadinessEventLoopFixture() {

    @Test
    fun `closing a loop twice returns the gather scratch once`() {
        val loop = FakeLoop()
        loop.close()
        loop.close()
    }

    @Test
    fun `a loop that grew its scratch still returns it exactly once`() {
        val loop = FakeLoop()
        loop.ensureWritevCapacity(GROWN_CAPACITY)
        loop.close()
        loop.close()
    }

    private companion object {
        /** Past the base's initial capacity, so the grow path reallocates first. */
        const val GROWN_CAPACITY = 64
    }
}
