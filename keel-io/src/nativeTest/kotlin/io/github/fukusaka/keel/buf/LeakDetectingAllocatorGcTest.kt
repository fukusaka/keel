@file:OptIn(kotlin.native.runtime.NativeRuntimeApi::class)

package io.github.fukusaka.keel.buf

import kotlin.native.runtime.GC
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Native-specific tests for GC-triggered leak detection via [createCleaner].
 *
 * These tests verify that unreleased buffers are detected when the garbage
 * collector reclaims them. Because each cleaner is anchored to its buffer
 * (an unanchored Native Cleaner fires on the next GC regardless of the
 * buffer's liveness — the false-leak flake fixed alongside these tests),
 * detection takes two [kotlin.native.runtime.GC.collect] calls: one to
 * reclaim the buffer and its cleaner, one to process the cleaner's block.
 *
 * Note: GC-based detection is inherently non-deterministic in production.
 * These tests use explicit GC.collect() to make the behaviour testable.
 */
class LeakDetectingAllocatorGcTest {

    /**
     * Drives GC cycles until [expected] leak reports arrive or the budget
     * runs out. Cleaner blocks run asynchronously on the dedicated cleaner
     * worker after the (buffer-anchored) cleaner is reclaimed, so a single
     * synchronous [GC.collect] is not a completion barrier — poll with a
     * bounded wall-clock budget instead (deterministic on success, a clear
     * failure on regression).
     */
    private fun awaitLeaks(leaks: List<String>, expected: Int) {
        val deadline = kotlin.time.TimeSource.Monotonic.markNow() + AWAIT_BUDGET
        while (leaks.size < expected && deadline.hasNotPassedNow()) {
            GC.collect()
            platform.posix.usleep(10_000u) // 10 ms: let the cleaner worker drain
        }
    }

    /**
     * Allocates and "forgets" [count] buffers in a separate (frame-popping)
     * function: a debug binary may keep a discarded local rooted in the
     * calling frame until the function returns, so leaking inside the test
     * body would keep the buffers reachable and GC-based detection
     * untestable. (The old in-frame shape only looked like it worked
     * because the unanchored cleaner fired early — the bug fixed here.)
     */
    private fun leakBuffers(allocator: LeakDetectingAllocator, count: Int) {
        repeat(count) { i ->
            allocator.allocate(64 shl i)
        }
    }

    @Test
    fun `unreleased buffer triggers onLeak after GC`() {
        val leaks = mutableListOf<String>()
        val allocator = LeakDetectingAllocator(DefaultAllocator) { leaks.add(it) }

        // Allocate and deliberately "forget" the buffer — in a separate
        // frame so it is genuinely unreachable here.
        leakBuffers(allocator, count = 1)

        awaitLeaks(leaks, expected = 1)

        assertTrue(leaks.isNotEmpty(), "Unreleased buffer should trigger onLeak after GC")
        assertTrue(
            leaks[0].contains("Unreleased buffer detected"),
            "Leak message should contain 'Unreleased buffer detected'",
        )
        assertTrue(
            leaks[0].contains("Buffer allocated here"),
            "Leak message should contain allocation site stack trace",
        )
    }

    @Test
    fun `a GC between allocate and release does not falsely report a leak`() {
        // Regression: the cleanup block of a Native Cleaner runs when the
        // CLEANER object becomes unreachable, so an unretained cleaner is
        // garbage immediately and fires on the next GC cycle regardless of
        // the buffer's liveness. With the cleaner unanchored, the GC.collect
        // below fired the leak callback for a buffer that was still alive
        // and about to be released — the rare full-suite flake where
        // unrelated allocation pressure put a GC inside this window, made
        // deterministic here.
        val leaks = mutableListOf<String>()
        val allocator = LeakDetectingAllocator(DefaultAllocator) { leaks.add(it) }

        val buf = allocator.allocate(64)
        GC.collect() // lands inside the allocate-to-release window
        buf.release()
        GC.collect()

        assertEquals(0, leaks.size, "a live, later-released buffer must never be reported as a leak")
    }

    @Test
    fun `released buffer does not trigger onLeak after GC`() {
        val leaks = mutableListOf<String>()
        val allocator = LeakDetectingAllocator(DefaultAllocator) { leaks.add(it) }

        val buf = allocator.allocate(64)
        buf.release()

        GC.collect()

        assertEquals(0, leaks.size, "Released buffer should not trigger onLeak after GC")
    }

    @Test
    fun `multiple unreleased buffers each trigger onLeak after GC`() {
        val leaks = mutableListOf<String>()
        val allocator = LeakDetectingAllocator(DefaultAllocator) { leaks.add(it) }

        leakBuffers(allocator, count = 3)

        awaitLeaks(leaks, expected = 3)

        assertEquals(3, leaks.size, "Each unreleased buffer should trigger its own onLeak")
    }

    @Test
    fun `partially released buffers - only unreleased trigger onLeak`() {
        val leaks = mutableListOf<String>()
        val allocator = LeakDetectingAllocator(DefaultAllocator) { leaks.add(it) }

        val buf1 = allocator.allocate(64)
        leakBuffers(allocator, count = 1) // deliberately leaked, out of frame
        val buf3 = allocator.allocate(256)

        buf1.release()
        buf3.release()

        awaitLeaks(leaks, expected = 1)

        assertEquals(1, leaks.size, "Only the unreleased buffer should trigger onLeak")
    }

    @Test
    fun `retained then fully released buffer does not trigger onLeak after GC`() {
        val leaks = mutableListOf<String>()
        val allocator = LeakDetectingAllocator(DefaultAllocator) { leaks.add(it) }

        val buf = allocator.allocate(64)
        buf.retain()
        buf.release() // refCount 1 — deallocator not called
        buf.release() // refCount 0 — deallocator called, marked released

        GC.collect()

        assertEquals(0, leaks.size, "Fully released buffer should not trigger onLeak")
    }

    private companion object {
        /**
         * Wall-clock budget for the asynchronous cleaner worker to run the
         * leak blocks after the anchored cleaner is reclaimed. Generous —
         * the worker typically drains within one 10 ms poll.
         */
        private val AWAIT_BUDGET = kotlin.time.Duration.parse("5s")
    }
}
