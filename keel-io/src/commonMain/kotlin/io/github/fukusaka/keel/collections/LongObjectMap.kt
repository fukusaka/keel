package io.github.fukusaka.keel.collections

/**
 * A primitive-keyed hash map from `Long` to a non-nullable value type, using
 * open-addressing with linear probing. Designed for hot-path data structures
 * (engine fd registration tables, cached counter lookups) where the per-call
 * cost of `HashMap<Long, V>` is dominated by `Long` boxing.
 *
 * **Shape vs. `HashMap<Long, V>`:**
 *
 * - Keys are primitive `Long` and are never boxed into `java.lang.Long` /
 *   `kotlin.Long`-backed wrappers. On a Kotlin/Native micro-benchmark
 *   (PR #358), `HashMap<Long, V>.put/remove` averaged ~33 ns/op while an
 *   open-addressing primitive-keyed map ran at ~9.6 ns/op (`ratio ≈ 3.4×`).
 * - No per-entry `HashMap.Node` allocation; all state is held in two parallel
 *   arrays (`keys: LongArray`, `values: Array<Any?>`). `put` and `remove`
 *   perform zero heap allocation on the hot path; only resize triggers a
 *   copy, which amortises to `O(1)` per `put`.
 * - Load factor 0.75 (rehash when `(size + tombstones) * 4 >= capacity * 3`).
 * - Capacity is always a power of two so `index = hash(key) and (capacity - 1)`
 *   avoids an expensive modulo.
 *
 * **Not a `Map<K, V>` implementation:**
 *
 * This class does not implement `kotlin.collections.Map<Long, V>` because
 * doing so would force boxing of `Long` keys at the interface boundary
 * (`Map<K, V>.get(key: K)` boxes). The stdlib-compatible API surface is also
 * larger than this class intends to support (entry views, keys/values
 * collections, iterators) — each of those bring their own allocations that
 * this class deliberately avoids. For engine-internal use the minimal
 * `get` / `put` / `remove` / `containsKey` / `clear` / `size` surface is
 * sufficient.
 *
 * **Thread safety:**
 *
 * [LongObjectMap] is not thread-safe. Engines that share an instance across
 * threads must synchronise externally (e.g. via the same `pthread_mutex_t`
 * that protects the surrounding registration table).
 *
 * **Value nullability:**
 *
 * `V : Any` — null values are not supported. A `null` return from [get] or
 * [remove] unambiguously means "absent". Consumers that need nullable values
 * can wrap in `Optional`-like sentinels, but in practice every keel-internal
 * registration stores a non-null `Registration` / lambda.
 *
 * @param V value type; must be non-nullable (`V : Any`).
 * @param initialCapacity requested initial capacity. Rounded up to the next
 *   power of two and lower-bounded by [MIN_CAPACITY].
 */
public class LongObjectMap<V : Any>(initialCapacity: Int = DEFAULT_CAPACITY) {

    private var keys: LongArray
    private var values: Array<Any?>
    private var sizeInternal: Int = 0
    private var tombstoneCount: Int = 0

    init {
        require(initialCapacity >= 0) { "initialCapacity must be non-negative: $initialCapacity" }
        val cap = nextPowerOfTwo(maxOf(initialCapacity, MIN_CAPACITY))
        keys = LongArray(cap)
        values = arrayOfNulls(cap)
    }

    /** Number of key–value entries currently stored. */
    public val size: Int get() = sizeInternal

    public fun isEmpty(): Boolean = sizeInternal == 0

    /**
     * Returns the value associated with [key], or `null` if absent.
     */
    public operator fun get(key: Long): V? {
        val idx = findIndex(key)
        if (idx < 0) return null
        @Suppress("UNCHECKED_CAST")
        return values[idx] as V
    }

    public fun containsKey(key: Long): Boolean = findIndex(key) >= 0

    /**
     * Operator form of [put] that discards the previous value. Enables the
     * familiar `map[key] = value` syntax without boxing the `Long` key.
     */
    public operator fun set(key: Long, value: V) {
        put(key, value)
    }

    /**
     * Associates [value] with [key]. Returns the previous value or `null`.
     *
     * Resizes when the combined load of live entries and tombstones reaches
     * 75 % of capacity.
     */
    public fun put(key: Long, value: V): V? {
        if ((sizeInternal + tombstoneCount) * 4 >= values.size * 3) {
            resize(values.size * 2)
        }
        val i = probeForPut(key)
        val existing = values[i]
        return when {
            existing == null -> {
                keys[i] = key
                values[i] = value
                sizeInternal++
                null
            }
            existing === TOMBSTONE -> {
                keys[i] = key
                values[i] = value
                sizeInternal++
                tombstoneCount--
                null
            }
            else -> {
                @Suppress("UNCHECKED_CAST")
                val old = existing as V
                values[i] = value
                old
            }
        }
    }

    /**
     * Removes the entry for [key]. Returns the removed value or `null` if
     * absent. Uses a tombstone to preserve probe chains; tombstones are
     * reclaimed at the next resize.
     */
    public fun remove(key: Long): V? {
        val idx = findIndex(key)
        if (idx < 0) return null
        @Suppress("UNCHECKED_CAST")
        val old = values[idx] as V
        values[idx] = TOMBSTONE
        sizeInternal--
        tombstoneCount++
        return old
    }

    /** Removes all entries and releases references without shrinking backing storage. */
    public fun clear() {
        values.fill(null)
        sizeInternal = 0
        tombstoneCount = 0
    }

    // --- Internal ---

    /**
     * Locates the slot for [key] (OCCUPIED with matching key). Returns `-1`
     * when absent. Stops at the first EMPTY slot (null); tombstones do not
     * terminate the probe.
     */
    private fun findIndex(key: Long): Int {
        val arr = values
        val mask = arr.size - 1
        var i = hash(key) and mask
        while (true) {
            val s = arr[i] ?: return -1
            if (s !== TOMBSTONE && keys[i] == key) return i
            i = (i + 1) and mask
        }
    }

    /**
     * Locates the slot where [key] should be written. Returns either:
     * - an OCCUPIED slot whose key matches (update case), or
     * - the first EMPTY / TOMBSTONE slot along the probe chain (insert case).
     *
     * The caller distinguishes update vs. insert by inspecting `values[i]`
     * after this call.
     */
    private fun probeForPut(key: Long): Int {
        val arr = values
        val mask = arr.size - 1
        var i = hash(key) and mask
        var firstTombstone = -1
        while (true) {
            val s = arr[i]
            when {
                s == null -> return if (firstTombstone >= 0) firstTombstone else i
                s === TOMBSTONE -> {
                    if (firstTombstone < 0) firstTombstone = i
                }
                keys[i] == key -> return i
            }
            i = (i + 1) and mask
        }
    }

    /**
     * Fibonacci hashing — mixes the 64-bit key into a well-distributed `Int`
     * even when consecutive keys (e.g., fd numbers) share low-bit patterns.
     * See Knuth "Fibonacci hashing" / "multiplicative hashing".
     */
    private fun hash(key: Long): Int {
        val h = key xor (key ushr 32)
        return (h * FIB_MULTIPLIER).toInt()
    }

    private fun resize(newCapacity: Int) {
        val oldKeys = keys
        val oldValues = values
        keys = LongArray(newCapacity)
        values = arrayOfNulls(newCapacity)
        sizeInternal = 0
        tombstoneCount = 0
        val mask = newCapacity - 1
        for (i in oldValues.indices) {
            val v = oldValues[i]
            if (v != null && v !== TOMBSTONE) {
                val key = oldKeys[i]
                var j = hash(key) and mask
                while (values[j] != null) j = (j + 1) and mask
                keys[j] = key
                values[j] = v
                sizeInternal++
            }
        }
    }

    private fun nextPowerOfTwo(v: Int): Int {
        var x = 1
        while (x < v) x = x shl 1
        return x
    }

    private companion object {
        /** Sentinel stored in `values[i]` for a removed slot. Never exposed. */
        private val TOMBSTONE: Any = Any()

        const val DEFAULT_CAPACITY: Int = 16
        const val MIN_CAPACITY: Int = 8

        /** 2^64 / φ — Fibonacci hashing multiplier. */
        const val FIB_MULTIPLIER: Long = -0x61c8864680b583ebL
    }
}
