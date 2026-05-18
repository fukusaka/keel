package io.github.fukusaka.keel.buf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the jemalloc-style size-class table ([sizeClasses],
 * [normalizeToSizeClass]) shared by the pooled allocators.
 */
class SizeClassesTest {

    @Test
    fun `table has 37 strictly ascending entries`() {
        val classes = sizeClasses()
        assertEquals(37, classes.size)
        assertEquals(MIN_SIZE_CLASS, classes.first())
        assertEquals(MAX_SIZE_CLASS, classes.last())
        for (i in 1 until classes.size) {
            assertTrue(classes[i] > classes[i - 1], "classes must be strictly ascending at index $i")
        }
    }

    @Test
    fun `every size class is a fixed point of normalizeToSizeClass`() {
        for (cls in sizeClasses()) {
            assertEquals(cls, normalizeToSizeClass(cls), "size class $cls must normalize to itself")
        }
    }

    @Test
    fun `requests at or below the floor round up to the minimum class`() {
        assertEquals(64, normalizeToSizeClass(0))
        assertEquals(64, normalizeToSizeClass(1))
        assertEquals(64, normalizeToSizeClass(63))
        assertEquals(64, normalizeToSizeClass(64))
    }

    @Test
    fun `off-size requests round up to the smallest enclosing class`() {
        assertEquals(80, normalizeToSizeClass(65))
        assertEquals(8192, normalizeToSizeClass(8192))
        assertEquals(10240, normalizeToSizeClass(8193))
        assertEquals(20480, normalizeToSizeClass(16385))
        assertEquals(32768, normalizeToSizeClass(32768))
    }

    @Test
    fun `huge requests above the ceiling are returned unchanged`() {
        assertEquals(32769, normalizeToSizeClass(32769))
        assertEquals(1 shl 20, normalizeToSizeClass(1 shl 20))
    }
}
