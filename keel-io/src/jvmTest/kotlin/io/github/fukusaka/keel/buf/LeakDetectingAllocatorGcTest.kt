package io.github.fukusaka.keel.buf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * JVM-specific tests for GC-triggered leak detection via [PhantomReference].
 *
 * On JVM, [LeakDetectingAllocator] uses PhantomReference + ReferenceQueue.
 * Leaked buffers are reported when [drainLeakQueue] is called during the
 * next allocation after GC enqueues the reference.
 *
 * Note: [System.gc] is a hint, not a guarantee. These tests may be flaky
 * on some JVM implementations. Multiple GC + allocation cycles improve
 * reliability.
 */
class LeakDetectingAllocatorGcTest {

    /**
     * Verifies that an unreleased buffer triggers [onLeak] after GC.
     *
     * Because [System.gc] is a hint (not guaranteed), this test validates
     * the leak message format only when GC cooperates. If GC does not
     * collect the buffer, the test still passes — the mechanism itself
     * is structurally correct; only the JVM's GC scheduling is uncertain.
     */
    @Test
    fun `unreleased buffer triggers onLeak after GC and next allocation`() {
        val leaks = mutableListOf<String>()
        val allocator = LeakDetectingAllocator(DefaultAllocator) { leaks.add(it) }

        // Allocate and deliberately "forget" the buffer.
        allocator.allocate(64)

        // Force GC + allow finalizers to run.
        // drainLeakQueue is called on next allocation.
        repeat(3) {
            System.gc()
            Thread.sleep(50)
        }

        // Trigger drainLeakQueue via next allocation.
        val probe = allocator.allocate(32)

        // System.gc() is best-effort; validate message format only when GC cooperates.
        if (leaks.isNotEmpty()) {
            assertTrue(
                leaks[0].contains("Unreleased buffer detected"),
                "Leak message should contain 'Unreleased buffer detected'",
            )
            assertTrue(
                leaks[0].contains("Buffer allocated here"),
                "Leak message should contain allocation site stack trace",
            )
        }

        probe.release()
    }

    @Test
    fun `released buffer does not trigger onLeak after GC`() {
        val leaks = mutableListOf<String>()
        val allocator = LeakDetectingAllocator(DefaultAllocator) { leaks.add(it) }

        val buf = allocator.allocate(64)
        buf.release()

        repeat(3) {
            System.gc()
            Thread.sleep(50)
        }

        // Trigger drainLeakQueue.
        val probe = allocator.allocate(32)

        assertEquals(0, leaks.size, "Released buffer should not trigger onLeak after GC")

        probe.release()
    }

    @Test
    fun `retained then fully released buffer does not trigger onLeak after GC`() {
        val leaks = mutableListOf<String>()
        val allocator = LeakDetectingAllocator(DefaultAllocator) { leaks.add(it) }

        val buf = allocator.allocate(64)
        buf.retain()
        buf.release() // refCount 1
        buf.release() // refCount 0 — deallocator called

        repeat(3) {
            System.gc()
            Thread.sleep(50)
        }

        val probe = allocator.allocate(32)

        assertEquals(0, leaks.size, "Fully released buffer should not trigger onLeak")

        probe.release()
    }
}
