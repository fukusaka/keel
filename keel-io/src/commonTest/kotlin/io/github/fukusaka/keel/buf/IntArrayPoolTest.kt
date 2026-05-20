package io.github.fukusaka.keel.buf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class IntArrayPoolTest {

    @Test
    fun `borrow returns an IntArray of arraySize filled with the sentinel`() {
        val pool = IntArrayPool(arraySize = 8)
        val arr = pool.borrow()
        assertEquals(8, arr.size)
        for (i in 0 until 8) assertEquals(-1, arr[i], "slot $i must be -1")
    }

    @Test
    fun `recycle returns array to the freelist and the next borrow reuses it`() {
        val pool = IntArrayPool(arraySize = 4)
        val first = pool.borrow()
        first[0] = 42
        pool.recycle(first)
        assertEquals(1, pool.pooledCount)
        val second = pool.borrow()
        assertSame(first, second, "same instance must be reused")
        // and the sentinel must have been re-applied
        assertEquals(-1, second[0])
        assertEquals(0, pool.pooledCount)
    }

    @Test
    fun `borrow allocates fresh when the freelist is empty`() {
        val pool = IntArrayPool(arraySize = 4)
        val a = pool.borrow()
        val b = pool.borrow()
        assertNotSame(a, b)
        assertEquals(4, a.size)
        assertEquals(4, b.size)
    }

    @Test
    fun `recycle drops arrays of the wrong size`() {
        val pool = IntArrayPool(arraySize = 4)
        pool.recycle(IntArray(8))
        assertEquals(0, pool.pooledCount, "mis-sized arrays must not corrupt the pool")
    }

    @Test
    fun `recycle past maxPooled drops the array`() {
        val pool = IntArrayPool(arraySize = 4, maxPooled = 2)
        pool.recycle(IntArray(4))
        pool.recycle(IntArray(4))
        pool.recycle(IntArray(4)) // would push to 3, exceeds maxPooled
        assertEquals(2, pool.pooledCount)
    }

    @Test
    fun `custom sentinel is honoured`() {
        val pool = IntArrayPool(arraySize = 4, emptySentinel = 0)
        val arr = pool.borrow()
        for (i in 0 until 4) assertEquals(0, arr[i])
    }

    @Test
    fun `arraySize must be positive`() {
        assertFailsWith<IllegalArgumentException> { IntArrayPool(arraySize = 0) }
        assertFailsWith<IllegalArgumentException> { IntArrayPool(arraySize = -1) }
    }

    @Test
    fun `maxPooled cannot be negative`() {
        assertFailsWith<IllegalArgumentException> { IntArrayPool(arraySize = 4, maxPooled = -1) }
    }

    @Test
    fun `maxPooled zero disables pooling entirely`() {
        val pool = IntArrayPool(arraySize = 4, maxPooled = 0)
        pool.recycle(IntArray(4))
        assertEquals(0, pool.pooledCount)
        // borrow always allocates fresh
        val a = pool.borrow()
        val b = pool.borrow()
        assertNotSame(a, b)
    }
}
