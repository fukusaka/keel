package io.github.fukusaka.keel.buf

import kotlin.test.Test
import kotlin.test.assertEquals

class LongLongHashMapTest {
    @Test
    fun `get returns the absent sentinel for a missing key`() {
        val m = LongLongHashMap()
        assertEquals(0L, m.get(42L))
    }

    @Test
    fun `put then get round-trips and update overwrites`() {
        val m = LongLongHashMap()
        m.put(1L, 100L)
        m.put(2L, 200L)
        assertEquals(100L, m.get(1L))
        assertEquals(200L, m.get(2L))
        m.put(1L, 111L)
        assertEquals(111L, m.get(1L))
    }

    @Test
    fun `remove deletes and leaves other entries findable`() {
        val m = LongLongHashMap()
        for (k in 0L until 20L) m.put(k, k + 1)
        m.remove(7L)
        assertEquals(0L, m.get(7L))
        for (k in 0L until 20L) if (k != 7L) assertEquals(k + 1, m.get(k))
    }

    @Test
    fun `colliding keys probe correctly and survive deletion of a chain member`() {
        // Keys that collide into the same initial bucket (multiples that fold to
        // the same index) exercise the linear-probe chain and backward shift.
        val m = LongLongHashMap()
        val keys = (0L until 40L).toList()
        for (k in keys) m.put(k, k * 10 + 1)
        // Remove every third key, then verify the rest are still found.
        val removed = keys.filter { it % 3L == 0L }
        for (k in removed) m.remove(k)
        for (k in keys) {
            val expected = if (k in removed) 0L else k * 10 + 1
            assertEquals(expected, m.get(k), "key $k")
        }
    }

    @Test
    fun `behaves like a reference map under a long pseudo-random workload`() {
        // Deterministic LCG (no Random/Date — those are unavailable in this harness).
        var state = 0x12345678L
        fun next(): Long {
            state = (state * 6364136223846793005L + 1442695040888963407L)
            return (state ushr 16) and 0xFFFFL // small-ish keys to force collisions
        }
        val m = LongLongHashMap()
        val ref = HashMap<Long, Long>()
        repeat(5000) {
            val key = next()
            when ((next() % 3L).toInt()) {
                0, 1 -> {
                    val value = (next() or 1L) // never 0 (the sentinel)
                    m.put(key, value)
                    ref[key] = value
                }
                else -> {
                    m.remove(key)
                    ref.remove(key)
                }
            }
            assertEquals(ref[key] ?: 0L, m.get(key), "key $key")
        }
        // Full cross-check of every key ever touched.
        for (key in ref.keys) assertEquals(ref[key], m.get(key))
    }
}
