package io.github.fukusaka.keel.buf

/**
 * A primitive `Long -> Long` open-addressing hash map, used by [PoolChunk] as the
 * `runsAvailMap` (page offset -> free-run handle, for the first and last page of
 * every free run) so coalescing can find an adjacent free run in O(1).
 *
 * Modelled on Netty 4.2.12.Final's `io.netty.buffer.LongLongHashMap`. The key
 * design point — and the reason this is a hash map rather than a flat
 * `LongArray` indexed by page offset — is that the free-run endpoint set is
 * **sparse** over the page-offset key space, and that key space is **not fixed**:
 * the chunk and page sizes are configurable, so a large chunk with a small page
 * can have thousands of pages while only a handful of runs are free at any time.
 * The map's footprint scales with the live entry count, not with the page count.
 *
 * A slot is empty iff its stored value equals [emptyVal]; [get] returns
 * [emptyVal] for an absent key. Callers therefore reserve one value (here `0`,
 * which is never a valid run handle) as the "absent" sentinel. Linear probing
 * with backward-shift deletion keeps probe chains intact on [remove].
 *
 * Not thread-safe; serialised by the same lock as the owning chunk.
 */
internal class LongLongHashMap(private val emptyVal: Long = 0L) {
    private var capacity = INITIAL_CAPACITY
    private var mask = capacity - 1
    private var maxEntries = capacity / 2
    private var keys = LongArray(capacity)
    private var values = LongArray(capacity).also { it.fill(emptyVal) }
    private var size = 0

    /** Returns the value mapped to [key], or [emptyVal] when absent. */
    fun get(key: Long): Long {
        var idx = hashIndex(key)
        while (values[idx] != emptyVal) {
            if (keys[idx] == key) return values[idx]
            idx = (idx + 1) and mask
        }
        return emptyVal
    }

    /** Maps [key] to [value] (which must not equal [emptyVal]). */
    fun put(key: Long, value: Long) {
        require(value != emptyVal) { "value $emptyVal is the absent sentinel" }
        if (size >= maxEntries) grow()
        var idx = hashIndex(key)
        while (values[idx] != emptyVal) {
            if (keys[idx] == key) {
                values[idx] = value
                return
            }
            idx = (idx + 1) and mask
        }
        keys[idx] = key
        values[idx] = value
        size++
    }

    /** Removes [key] if present. */
    fun remove(key: Long) {
        var idx = hashIndex(key)
        while (values[idx] != emptyVal) {
            if (keys[idx] == key) {
                removeAt(idx)
                return
            }
            idx = (idx + 1) and mask
        }
    }

    private fun removeAt(start: Int) {
        size--
        // Backward-shift deletion: walk the probe chain after the hole, pulling
        // back any entry whose ideal slot is at or before the hole so that get()
        // can still find it.
        var hole = start
        var i = start
        while (true) {
            i = (i + 1) and mask
            if (values[i] == emptyVal) break
            val ideal = hashIndex(keys[i])
            if (!inCyclicRange(ideal, hole, i)) {
                keys[hole] = keys[i]
                values[hole] = values[i]
                hole = i
            }
        }
        values[hole] = emptyVal
    }

    private fun grow() {
        val oldKeys = keys
        val oldValues = values
        capacity *= 2
        mask = capacity - 1
        maxEntries = capacity / 2
        keys = LongArray(capacity)
        values = LongArray(capacity).also { it.fill(emptyVal) }
        size = 0
        for (i in oldValues.indices) {
            if (oldValues[i] != emptyVal) put(oldKeys[i], oldValues[i])
        }
    }

    private fun hashIndex(key: Long): Int {
        val h = key * FIB
        return ((h xor (h ushr 32)).toInt()) and mask
    }

    private companion object {
        const val INITIAL_CAPACITY = 16
        const val FIB = -0x61c8864680b583ebL // 2^64 / golden ratio, for bit spreading

        /** Is [h] in the cyclic half-open interval `(low, high]` (modulo capacity)? */
        fun inCyclicRange(h: Int, low: Int, high: Int): Boolean =
            if (low < high) h in (low + 1)..high else h > low || h <= high
    }
}
