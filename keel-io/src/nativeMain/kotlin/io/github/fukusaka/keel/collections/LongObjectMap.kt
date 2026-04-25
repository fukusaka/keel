package io.github.fukusaka.keel.collections

/**
 * A primitive-keyed hash map from `Long` to a non-nullable value type, using
 * open-addressing with linear probing and **backshift delete**. Designed for
 * hot-path data structures in keel's **Native engines** (kqueue / epoll fd
 * registration tables, io_uring buffer index lookups) where the per-call cost
 * of `HashMap<Long, V>` is dominated by `Long` boxing.
 *
 * **Scope** — this class is in the `nativeMain` source set because (1) every
 * known consumer is a Native engine, (2) the design choices (Fibonacci hash
 * tuning, backshift delete) were validated by the `--bench=longmap-variants`
 * Kotlin/Native micro-bench only — JVM and JS targets were not measured, so
 * we do not claim multiplatform readiness, and (3) Native lets us throw
 * [OutOfMemoryError] on the capacity-overflow path, matching the K/N stdlib
 * `HashMap.ensureCapacity` precedent. If a JVM / JS consumer ever appears,
 * promote with explicit cross-target benchmarks.
 *
 * ## Design choices (validated by `--bench=longmap-variants`)
 *
 * - **Hash**: Fibonacci multiplicative hashing
 *   `((key xor (key ushr 32)) * GOLDEN_RATIO_LONG) ushr (64 - log2(cap))`.
 *   Uses **top bits** of the multiplication result (matches K/N stdlib
 *   `HashMap.hash` idiom). Low-bit extraction (e.g. `.toInt() and mask`)
 *   leaks input pattern: page-aligned pointer keys all hash to slot 0
 *   because the low N bits of `(page-aligned * GOLDEN)` are zero.
 * - **Delete**: **backshift** (Robin-Hood-style) — on remove, scan forward
 *   and shift entries whose home slot ≤ removed index back, then `null`
 *   out the trailing slot. No tombstones. The hot read path becomes
 *   branch-free (`null` terminates probe).
 * - **Collision resolution**: linear probing — cache-friendly forward scan.
 * - **Load factor**: 0.75, 2× growth on resize.
 * - **Capacity**: always a power of two so `index = hash and (cap - 1)`
 *   avoids modulo.
 *
 * ## Performance (Kotlin/Native, `--bench=longmap-variants`)
 *
 * Micro-bench numbers (ns/op, read-dominant 9 get + 1 (rm+put), 64 entries):
 *
 * - macOS arm64: HashMap 41.5 → LongObjectMap **21.3** (1.95×)
 * - linuxX64 (luna): HashMap 23.8 → LongObjectMap **8.3** (2.87×)
 *
 * Page-aligned pointer keys (lookup-only, 64 entries):
 * - macOS arm64: HashMap 26.2 → LongObjectMap **18.5** (1.42×)
 * - linuxX64 (luna): HashMap 13.2 → LongObjectMap **7.7** (1.71×)
 *
 * ## Not a `Map<K, V>` implementation
 *
 * This class deliberately does not implement `kotlin.collections.Map<Long, V>`:
 * `Map<K, V>.get(key: K)` accepts a generic `K`, so calling it with `Long`
 * boxes the primitive — defeating the point. The full `Map` API surface
 * (entry views, keys/values collections, iterators) also brings allocations
 * that this class avoids. The minimal `get` / `put` / `remove` /
 * `containsKey` / `clear` / `size` surface is sufficient for engine-internal
 * use.
 *
 * ## Thread safety
 *
 * Not thread-safe. Engines that share an instance across threads must
 * synchronise externally (e.g. via the same `pthread_mutex_t` that protects
 * the surrounding registration table).
 *
 * ## Value nullability
 *
 * `V : Any` — null values are not supported. A `null` return from [get] or
 * [remove] unambiguously means "absent". Engines storing non-null
 * `Registration` / lambda / index values get this constraint for free.
 *
 * @param V value type; must be non-nullable (`V : Any`).
 * @param initialCapacity requested initial capacity. Rounded up to the next
 *   power of two and lower-bounded by [MIN_CAPACITY].
 */
public class LongObjectMap<V : Any>(initialCapacity: Int = DEFAULT_CAPACITY) {

    private var keys: LongArray
    private var values: Array<Any?>
    private var sizeInternal: Int = 0

    /** `64 - log2(cap)` — pre-computed shift for top-bit extraction in [hash]. */
    private var shift: Int

    init {
        require(initialCapacity in 0..MAX_CAPACITY) {
            "initialCapacity must be in [0, $MAX_CAPACITY]: $initialCapacity"
        }
        val cap = nextPowerOfTwo(maxOf(initialCapacity, MIN_CAPACITY))
        keys = LongArray(cap)
        values = arrayOfNulls(cap)
        shift = 64 - cap.countTrailingZeroBits()
    }

    /** Number of key–value entries currently stored. */
    public val size: Int get() = sizeInternal

    public fun isEmpty(): Boolean = sizeInternal == 0

    /** Returns the value associated with [key], or `null` if absent. */
    public operator fun get(key: Long): V? {
        val arr = values
        val mask = arr.size - 1
        var i = hash(key) and mask
        while (true) {
            val s = arr[i] ?: return null
            if (keys[i] == key) {
                @Suppress("UNCHECKED_CAST")
                return s as V
            }
            i = (i + 1) and mask
        }
    }

    public fun containsKey(key: Long): Boolean {
        val arr = values
        val mask = arr.size - 1
        var i = hash(key) and mask
        while (true) {
            if (arr[i] == null) return false
            if (keys[i] == key) return true
            i = (i + 1) and mask
        }
    }

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
     * Resizes when the live entry count reaches 75 % of capacity.
     */
    public fun put(key: Long, value: V): V? {
        if (sizeInternal * 4 >= values.size * 3) {
            if (values.size > MAX_CAPACITY / 2) {
                // Match K/N stdlib HashMap.ensureCapacity precedent — capacity
                // overflow surfaces as OutOfMemoryError, not ISE.
                throw OutOfMemoryError(
                    "LongObjectMap exceeded MAX_CAPACITY ($MAX_CAPACITY) at size=$sizeInternal",
                )
            }
            resize(values.size * 2)
        }
        val arr = values
        val mask = arr.size - 1
        var i = hash(key) and mask
        while (true) {
            val s = arr[i]
            if (s == null) {
                keys[i] = key
                arr[i] = value
                sizeInternal++
                return null
            }
            if (keys[i] == key) {
                @Suppress("UNCHECKED_CAST")
                val old = s as V
                arr[i] = value
                return old
            }
            i = (i + 1) and mask
        }
    }

    /**
     * Removes the entry for [key] using **backshift delete**. Returns the
     * removed value or `null` if absent.
     *
     * Backshift preserves the no-gap invariant of linear probing without
     * tombstones: after removing slot `i`, scan forward and shift any
     * entry `j` whose home slot is `≤ i` (mod cap) into slot `i`.
     */
    public fun remove(key: Long): V? {
        val arr = values
        val mask = arr.size - 1
        var i = hash(key) and mask
        while (true) {
            val s = arr[i] ?: return null
            if (keys[i] == key) {
                @Suppress("UNCHECKED_CAST")
                val old = s as V
                backshift(i, mask)
                sizeInternal--
                return old
            }
            i = (i + 1) and mask
        }
    }

    /** Removes all entries and releases references without shrinking backing storage. */
    public fun clear() {
        values.fill(null)
        sizeInternal = 0
    }

    // --- Internal ---

    /**
     * Fibonacci multiplicative hashing with **top-bit extraction**.
     *
     * `(mix * 2^64 / φ) >>> (64 - log2(cap))` — takes the top `log2(cap)`
     * bits of the product, which are well-mixed even when the input keeps
     * structure in its low bits (e.g. page-aligned pointers).
     */
    private fun hash(key: Long): Int {
        val h = key xor (key ushr 32)
        return ((h * FIB_MULTIPLIER) ushr shift).toInt()
    }

    /**
     * Backshift after removing slot [removed]. Walks forward from `removed`
     * shifting any entry whose home slot ≤ `removed` (mod) back into the
     * gap, then null-terminates the chain.
     */
    private fun backshift(removed: Int, mask: Int) {
        var i = removed
        val arr = values
        while (true) {
            val j = (i + 1) and mask
            val s = arr[j]
            if (s == null) {
                arr[i] = null
                return
            }
            val home = hash(keys[j]) and mask
            // Move entry from j to i if entry at j was displaced past i.
            if (((j - home) and mask) >= ((j - i) and mask)) {
                keys[i] = keys[j]
                arr[i] = s
                i = j
            } else {
                arr[i] = null
                return
            }
        }
    }

    private fun resize(newCapacity: Int) {
        val oldKeys = keys
        val oldValues = values
        keys = LongArray(newCapacity)
        values = arrayOfNulls(newCapacity)
        shift = 64 - newCapacity.countTrailingZeroBits()
        sizeInternal = 0
        val mask = newCapacity - 1
        for (i in oldValues.indices) {
            val v = oldValues[i] ?: continue
            val key = oldKeys[i]
            var j = hash(key) and mask
            while (values[j] != null) j = (j + 1) and mask
            keys[j] = key
            values[j] = v
            sizeInternal++
        }
    }

    private fun nextPowerOfTwo(v: Int): Int {
        var x = 1
        while (x < v) x = x shl 1
        return x
    }

    private companion object {
        const val DEFAULT_CAPACITY: Int = 16
        const val MIN_CAPACITY: Int = 8

        /** Largest power-of-two capacity that fits in a non-negative `Int`. */
        const val MAX_CAPACITY: Int = 1 shl 30

        /** 2^64 / φ — Fibonacci multiplicative hashing constant (golden ratio). */
        const val FIB_MULTIPLIER: Long = -0x61c8864680b583ebL
    }
}
