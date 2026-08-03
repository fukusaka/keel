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
    fun `put-remove churn with engine-like keys keeps every live key reachable`() {
        // Mirrors the EpollEventLoop / KqueueEventLoop registration key space
        // (fd | interest.ordinal shl 32) under heavy put/remove churn, where
        // backward-shift deletion of one key must never make another live key
        // unreachable. Deterministic LCG so failures reproduce.
        val m = LongObjectMap<Int>()
        val ref = HashMap<Long, Int>()
        val keys = ArrayList<Long>()
        for (fd in 1..150) {
            keys.add(fd.toLong())
            keys.add(fd.toLong() or (1L shl 32))
        }
        var rng = 0x9E3779B97F4A7C15uL.toLong()
        fun next(): Int {
            rng = rng * 6364136223846793005L + 1442695040888963407L
            return ((rng ushr 33).toInt() and 0x7FFFFFFF)
        }
        repeat(20_000) { iter ->
            val key = keys[next() % keys.size]
            if (ref.containsKey(key)) {
                m.remove(key)
                ref.remove(key)
            } else {
                m[key] = iter
                ref[key] = iter
            }
            // After every op, every key the reference holds must still be
            // reachable in the map (a backshift bug surfaces as a live key
            // returning null) and absent keys must return null.
            for (k in keys) {
                assertEquals(ref[k], m[k], "iter=$iter key=$k diverged (size=${m.size}/${ref.size})")
            }
        }
        assertEquals(ref.size, m.size)
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

    @Test
    fun `multi-seed insert-update-remove churn matches a reference map on size get and containsKey`() {
        // Stronger than the single-seed Red-Green churn above: several LCG
        // seeds (distinct op trajectories / cluster shapes), a 3-way op mix
        // that also exercises put-update (overwrite of a present key), and a
        // full size + get(value) + containsKey reconciliation after EVERY op —
        // so any divergence (lost key, size drift, stale value) is caught at
        // the exact op that introduces it, on whichever engine-shaped key it hits.
        val keys = ArrayList<Long>()
        for (fd in 1..120) {
            keys.add(fd.toLong())
            keys.add(fd.toLong() or (1L shl 32))
        }
        val seeds = longArrayOf(1L, 1442695040888963407L, -0x61c8864680b583ebL, 0xDEADBEEFL)
        for (seed in seeds) {
            val m = LongObjectMap<Int>()
            val ref = HashMap<Long, Int>()
            var rng = seed
            fun next(): Int {
                rng = rng * 6364136223846793005L + 1442695040888963407L
                return (rng ushr 33).toInt() and 0x7FFFFFFF
            }
            repeat(2_000) { iter ->
                val key = keys[next() % keys.size]
                when {
                    !ref.containsKey(key) -> {
                        m[key] = iter
                        ref[key] = iter
                    } // insert
                    next() % 2 == 0 -> {
                        m.remove(key)
                        ref.remove(key)
                    } // remove
                    else -> {
                        m[key] = iter
                        ref[key] = iter
                    } // update (overwrite)
                }
                assertEquals(ref.size, m.size, "seed=$seed iter=$iter size drift")
                for (k in keys) {
                    assertEquals(ref[k], m[k], "seed=$seed iter=$iter key=$k get diverged")
                    assertEquals(
                        ref.containsKey(k),
                        m.containsKey(k),
                        "seed=$seed iter=$iter key=$k containsKey diverged",
                    )
                }
            }
            assertEquals(ref.size, m.size)
        }
    }

    @Test
    fun `page-aligned keys with low bits zero round-trip through put get and remove`() {
        // The Fibonacci hash uses top-bit extraction precisely so page-aligned
        // pointer-like keys (low bits zero) do not all hash to one slot. This
        // pins correctness for that key class (round-trip + backshift on the
        // survivors after remove); the distribution property itself is a design
        // invariant of LongObjectMap.hash and is not asserted here (slot layout
        // is private).
        val m = LongObjectMap<Int>()
        val pageSize = 4096L
        val n = 200
        for (i in 1..n) m[pageSize * i] = i
        assertEquals(n, m.size)
        for (i in 1..n) assertEquals(i, m[pageSize * i], "page-aligned key ${pageSize * i} not retrievable")
        // Remove every other page-aligned key; survivors must stay reachable.
        for (i in 1..n step 2) m.remove(pageSize * i)
        assertEquals(n / 2, m.size)
        for (i in 1..n) {
            if (i % 2 == 1) {
                assertNull(m[pageSize * i])
            } else {
                assertEquals(i, m[pageSize * i], "survivor ${pageSize * i} stranded after remove")
            }
        }
    }

    @Test
    fun `forEachValue visits every value exactly once`() {
        val map = LongObjectMap<String>()
        val expected = (1L..50L).associateWith { "v$it" }
        expected.forEach { (k, v) -> map[k] = v }

        val seen = mutableListOf<String>()
        map.forEachValue { seen.add(it) }

        assertEquals(expected.size, seen.size, "one visit per entry")
        assertEquals(expected.values.toSet(), seen.toSet())
    }

    @Test
    fun `forEachValue visits one value under many keys once per key`() {
        // Once per entry, not once per distinct value. Pinned because the shape
        // is invisible to every other test here: they all use distinct values per
        // key and two compare through toSet(), so a values-view or a dedupe fast
        // path would leave keel-io green while changing what consumers observe.
        // The engine ledgers have that shape -- keyed on (fd, interest), one
        // value can sit under both keys -- so the property must hold whether or
        // not any walk today counts on the double visit.
        val map = LongObjectMap<String>()
        val shared = "one-object"
        map[1L] = shared
        map[1L shl 32] = shared
        map[7L] = "other"

        val seen = mutableListOf<String>()
        map.forEachValue { seen.add(it) }

        assertEquals(3, seen.size, "three entries, three visits")
        assertEquals(2, seen.count { it === shared }, "the shared value is visited once per key holding it")
    }

    @Test
    fun `forEachValue skips removed slots`() {
        // Removal back-shifts within the probe cluster, so a naive scan that
        // trusted stale keys would hand back a value that is no longer there.
        val map = LongObjectMap<String>()
        for (k in 1L..20L) map[k] = "v$k"
        for (k in 1L..20L step 2) map.remove(k)

        val seen = mutableListOf<String>()
        map.forEachValue { seen.add(it) }

        assertEquals(10, seen.size)
        assertEquals((2L..20L step 2).map { "v$it" }.toSet(), seen.toSet())
    }

    @Test
    fun `forEachValue on an empty map does nothing`() {
        var calls = 0
        LongObjectMap<String>().forEachValue { calls++ }
        assertEquals(0, calls)
    }

    @Test
    fun `forEachValue visits entries that survived a resize`() {
        val map = LongObjectMap<String>(initialCapacity = 4)
        for (k in 1L..200L) map[k] = "v$k"

        var count = 0
        map.forEachValue { count++ }
        assertEquals(200, count)
    }
}
