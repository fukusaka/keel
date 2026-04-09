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
 * collector reclaims them. [kotlin.native.internal.GC.collect] forces a GC
 * cycle to trigger the Cleaner callback synchronously.
 *
 * Note: GC-based detection is inherently non-deterministic in production.
 * These tests use explicit GC.collect() to make the behaviour testable.
 */
class LeakDetectingAllocatorGcTest {

    @Test
    fun `unreleased buffer triggers onLeak after GC`() {
        val leaks = mutableListOf<String>()
        val allocator = LeakDetectingAllocator(DefaultAllocator) { leaks.add(it) }

        // Allocate and deliberately "forget" the buffer.
        allocator.allocate(64)

        // Force GC to trigger the Cleaner callback.
        GC.collect()

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

        allocator.allocate(64)
        allocator.allocate(128)
        allocator.allocate(256)

        GC.collect()

        assertEquals(3, leaks.size, "Each unreleased buffer should trigger its own onLeak")
    }

    @Test
    fun `partially released buffers - only unreleased trigger onLeak`() {
        val leaks = mutableListOf<String>()
        val allocator = LeakDetectingAllocator(DefaultAllocator) { leaks.add(it) }

        val buf1 = allocator.allocate(64)
        allocator.allocate(128) // deliberately leaked
        val buf3 = allocator.allocate(256)

        buf1.release()
        buf3.release()

        GC.collect()

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
}
