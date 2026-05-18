package io.github.fukusaka.keel.buf

/**
 * jemalloc-style buffer size-class scheme used by the pooled allocators
 * ([SlabAllocator] on Native, [PooledDirectAllocator] on JVM).
 *
 * Rather than keying freelists by the exact requested size — which lets any
 * unregistered size bypass the pool and produce GC garbage — every allocation
 * is rounded up to the smallest enclosing size class. As a result every
 * request up to [MAX_SIZE_CLASS] hits a pool, and pooled buffers have a small
 * uniform set of capacities.
 *
 * **4-per-octave rationale (jemalloc `SC_NGROUP = 4`)**: each power-of-2
 * octave `[2^k, 2^(k+1))` is divided into 4 evenly spaced classes
 * `2^k + i * 2^(k-2)` for `i = 0..3`. With 4 classes per octave the worst-case
 * internal fragmentation — the gap between a request and the class it rounds
 * up to — is bounded at one step `2^(k-2)`, i.e. at most `25%` of the class
 * size. Fewer classes per octave would waste more memory; more classes would
 * fragment the pool into rarely-reused freelists.
 *
 * The table spans octaves `k = 6..14` (floor [MIN_SIZE_CLASS] = 64 B, up to
 * 16384) plus the single class [MAX_SIZE_CLASS] = 32768, for `9 * 4 + 1 = 37`
 * classes total:
 *
 * ```
 *   64    80    96   112
 *  128   160   192   224
 *  256   320   384   448
 *  512   640   768   896
 * 1024  1280  1536  1792
 * 2048  2560  3072  3584
 * 4096  5120  6144  7168
 * 8192 10240 12288 14336
 * 16384 20480 24576 28672
 * 32768
 * ```
 *
 * Requests larger than [MAX_SIZE_CLASS] are "huge": they are not pooled and
 * are served at their exact requested capacity by the allocator fallback.
 */

/** Smallest size class, and the floor every request rounds up to. */
internal const val MIN_SIZE_CLASS = 64

/** Largest pooled size class. Requests above this are served un-pooled. */
internal const val MAX_SIZE_CLASS = 32768

/** Lowest octave exponent (`2^6 = 64`). */
private const val MIN_OCTAVE_EXPONENT = 6

/** Highest octave exponent whose 4 classes are below [MAX_SIZE_CLASS] (`2^14 = 16384`). */
private const val MAX_OCTAVE_EXPONENT = 14

/** Number of size classes per power-of-2 octave (jemalloc `SC_NGROUP`). */
private const val CLASSES_PER_OCTAVE = 4

/** Shift to obtain the per-octave step `2^(k-2)` from the octave base `2^k`. */
private const val OCTAVE_STEP_SHIFT = 2

/** Total number of size classes: `9` octaves of `4` classes plus `32768`. */
private const val SIZE_CLASS_COUNT = (MAX_OCTAVE_EXPONENT - MIN_OCTAVE_EXPONENT + 1) * CLASSES_PER_OCTAVE + 1

/**
 * The full ascending list of size-class capacities (37 entries, strictly
 * increasing, from [MIN_SIZE_CLASS] to [MAX_SIZE_CLASS]).
 *
 * Built once at class-load time; the pooled allocators iterate this to create
 * one (initially empty) freelist per class.
 */
private val SIZE_CLASSES: IntArray = buildSizeClasses()

private fun buildSizeClasses(): IntArray {
    val classes = IntArray(SIZE_CLASS_COUNT)
    var index = 0
    for (k in MIN_OCTAVE_EXPONENT..MAX_OCTAVE_EXPONENT) {
        val base = 1 shl k
        val step = base shr OCTAVE_STEP_SHIFT
        for (i in 0 until CLASSES_PER_OCTAVE) {
            classes[index++] = base + i * step
        }
    }
    classes[index] = MAX_SIZE_CLASS
    return classes
}

/**
 * Returns the full ascending list of size-class capacities (37 entries).
 *
 * Returns a fresh copy so callers cannot mutate the shared table.
 */
internal fun sizeClasses(): IntArray = SIZE_CLASSES.copyOf()

/**
 * Rounds [capacity] up to the smallest size class `>= capacity`.
 *
 * Runs in O(1): the octave is found via [Int.countLeadingZeroBits] and the
 * class within the octave by integer division, with no table search.
 *
 * Returns [MIN_SIZE_CLASS] for any request at or below the floor (including
 * `0` and negative values). Returns [capacity] **unchanged** when it exceeds
 * [MAX_SIZE_CLASS] — the caller treats such requests as un-pooled "huge"
 * allocations served at their exact size.
 *
 * Every value in [sizeClasses] is a fixed point of this function:
 * `normalizeToSizeClass(c) == c` for every class `c`.
 */
internal fun normalizeToSizeClass(capacity: Int): Int {
    if (capacity <= MIN_SIZE_CLASS) return MIN_SIZE_CLASS
    if (capacity > MAX_SIZE_CLASS) return capacity
    val k = Int.SIZE_BITS - 1 - capacity.countLeadingZeroBits() // floor(log2(capacity))
    val base = 1 shl k
    if (capacity == base) return capacity
    val delta = base shr OCTAVE_STEP_SHIFT
    val steps = (capacity - base + delta - 1) / delta // 1..4
    return base + steps * delta
}
