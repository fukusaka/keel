package io.github.fukusaka.keel.collections

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LongObjectMapTest {

    @Test
    fun `empty map returns null and reports empty`() {
        val m = LongObjectMap<String>()
        assertEquals(0, m.size)
        assertTrue(m.isEmpty())
        assertNull(m[1L])
        assertFalse(m.containsKey(1L))
    }

    @Test
    fun `put returns null for first insert`() {
        val m = LongObjectMap<String>()
        assertNull(m.put(1L, "a"))
        assertEquals(1, m.size)
        assertEquals("a", m[1L])
        assertTrue(m.containsKey(1L))
    }

    @Test
    fun `put returns previous value on update`() {
        val m = LongObjectMap<String>()
        m.put(1L, "a")
        assertEquals("a", m.put(1L, "b"))
        assertEquals("b", m[1L])
        assertEquals(1, m.size)
    }

    @Test
    fun `remove returns previous value and decrements size`() {
        val m = LongObjectMap<String>()
        m.put(1L, "a")
        m.put(2L, "b")
        assertEquals("a", m.remove(1L))
        assertEquals(1, m.size)
        assertNull(m[1L])
        assertFalse(m.containsKey(1L))
        assertEquals("b", m[2L])
    }

    @Test
    fun `remove absent key returns null`() {
        val m = LongObjectMap<String>()
        m.put(1L, "a")
        assertNull(m.remove(2L))
        assertEquals(1, m.size)
    }

    @Test
    fun `put-then-remove-then-put reinserts into same slot without size drift`() {
        val m = LongObjectMap<String>()
        m.put(1L, "a")
        m.remove(1L)
        m.put(1L, "b")
        assertEquals(1, m.size)
        assertEquals("b", m[1L])
    }

    @Test
    fun `insert many keys exceeds load factor and resizes correctly`() {
        val m = LongObjectMap<String>(initialCapacity = 8)
        // 8 -> resize at 6 entries (0.75 * 8 = 6).
        for (i in 0 until 100) {
            m.put(i.toLong(), "v$i")
        }
        assertEquals(100, m.size)
        for (i in 0 until 100) {
            assertEquals("v$i", m[i.toLong()])
        }
    }

    @Test
    fun `tombstones are reclaimed across resize`() {
        val m = LongObjectMap<String>(initialCapacity = 8)
        // Fill, remove half, then insert to force a resize.
        for (i in 0L until 20L) m.put(i, "v$i")
        for (i in 0L until 10L) m.remove(i)
        assertEquals(10, m.size)
        // Inserting 20 more triggers at least one resize that should
        // drop the tombstones.
        for (i in 20L until 40L) m.put(i, "v$i")
        assertEquals(30, m.size)
        for (i in 0L until 10L) assertNull(m[i])
        for (i in 10L until 40L) assertEquals("v$i", m[i])
    }

    @Test
    fun `colliding keys probe linearly and remain retrievable`() {
        // Two keys whose fibonacci hashes mod 16 collide on the same slot
        // (choosing hash collisions on a concrete map is brittle; we simulate
        // by filling a single probe chain via adjacent keys).
        val m = LongObjectMap<Int>(initialCapacity = 16)
        // The low bits of adjacent Long keys are likely to collide less
        // often after Fibonacci hashing, so fall back to explicit insertion
        // of both large and small keys and ensure they still all round-trip.
        val keys = longArrayOf(1L, 17L, 33L, 49L, 65L, 81L, 97L, 113L, 129L)
        for ((i, k) in keys.withIndex()) m.put(k, i)
        assertEquals(keys.size, m.size)
        for ((i, k) in keys.withIndex()) assertEquals(i, m[k])
    }

    @Test
    fun `clear removes all entries and preserves backing storage`() {
        val m = LongObjectMap<String>(initialCapacity = 16)
        for (i in 0L until 10L) m.put(i, "v$i")
        m.clear()
        assertEquals(0, m.size)
        assertTrue(m.isEmpty())
        for (i in 0L until 10L) assertNull(m[i])
    }

    @Test
    fun `put after clear works normally`() {
        val m = LongObjectMap<String>()
        m.put(1L, "a")
        m.clear()
        m.put(1L, "b")
        assertEquals(1, m.size)
        assertEquals("b", m[1L])
    }

    @Test
    fun `initial capacity is rounded up to next power of two`() {
        // 10 -> 16, 17 -> 32. Can't observe capacity directly, but verify
        // no behavioural difference at the boundary.
        val m10 = LongObjectMap<Int>(initialCapacity = 10)
        val m17 = LongObjectMap<Int>(initialCapacity = 17)
        for (i in 0 until 100) {
            m10.put(i.toLong(), i)
            m17.put(i.toLong(), i)
        }
        assertEquals(100, m10.size)
        assertEquals(100, m17.size)
        for (i in 0 until 100) {
            assertEquals(i, m10[i.toLong()])
            assertEquals(i, m17[i.toLong()])
        }
    }

    @Test
    fun `negative initial capacity throws`() {
        assertFailsWith<IllegalArgumentException> { LongObjectMap<Int>(initialCapacity = -1) }
    }

    @Test
    fun `initial capacity beyond MAX throws`() {
        // MAX_CAPACITY is 2^30; (1 shl 30) + 1 should reject without allocating.
        assertFailsWith<IllegalArgumentException> {
            LongObjectMap<Int>(initialCapacity = (1 shl 30) + 1)
        }
    }

    @Test
    fun `containsKey distinguishes present and absent across remove`() {
        val m = LongObjectMap<String>()
        m.put(7L, "seven")
        m.put(42L, "forty-two")
        assertTrue(m.containsKey(7L))
        assertTrue(m.containsKey(42L))
        assertFalse(m.containsKey(8L))
        assertFalse(m.containsKey(0L))
        // After remove the slot is null-terminated by backshift, so containsKey
        // must walk the probe chain correctly even after deletes.
        m.remove(7L)
        assertFalse(m.containsKey(7L))
        assertTrue(m.containsKey(42L))
    }

    @Test
    fun `zero initial capacity is lower-bounded to minimum`() {
        // zero is allowed but internally rounded up to MIN_CAPACITY (8).
        val m = LongObjectMap<Int>(initialCapacity = 0)
        for (i in 0 until 50) m.put(i.toLong(), i)
        assertEquals(50, m.size)
    }

    @Test
    fun `boundary key values round-trip`() {
        val m = LongObjectMap<String>()
        m.put(0L, "zero")
        m.put(Long.MAX_VALUE, "max")
        m.put(Long.MIN_VALUE, "min")
        m.put(-1L, "neg-one")
        assertEquals("zero", m[0L])
        assertEquals("max", m[Long.MAX_VALUE])
        assertEquals("min", m[Long.MIN_VALUE])
        assertEquals("neg-one", m[-1L])
        assertEquals(4, m.size)
    }
}
