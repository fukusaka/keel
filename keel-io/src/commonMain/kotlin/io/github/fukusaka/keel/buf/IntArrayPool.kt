package io.github.fukusaka.keel.buf

/**
 * A simple per-EventLoop pool of fixed-size [IntArray]s for hot-path
 * slot-table reuse (HTTP headers map, route lookup table, etc.).
 *
 * Each [borrow] returns an [IntArray] of [arraySize] ints initialised
 * to [emptySentinel]; each [recycle] returns the array to the
 * freelist for reuse. Arrays of the wrong size are silently dropped to
 * avoid pool corruption (defensive against caller mistakes).
 *
 * Modelled on Ktor http-cio's `IntArrayPool` (the slot-table backing
 * for `HttpHeadersMap`). Differences from Ktor's `DefaultPool`:
 *
 * - **No synchronization**: per-EventLoop instance, single-thread
 *   confined. Use one [IntArrayPool] per EventLoop (mirrors keel's
 *   existing `PooledDirectAllocator.createChild()` pattern).
 * - **Reset on borrow, not on recycle**: borrowing a fresh array gives
 *   a sentinel-filled state, callers can immediately use it without an
 *   explicit reset call.
 * - **`ArrayDeque` freelist** rather than treiber stack: this is a
 *   single-thread pool, FIFO/LIFO does not matter for correctness, and
 *   `ArrayDeque` has the simplest implementation.
 *
 * **Cost profile** (per [borrow] / [recycle] cycle on a warm pool):
 *
 * - [borrow] from non-empty freelist: 1 [ArrayDeque] poll + 1
 *   `IntArray.fill(emptySentinel)` (linear in [arraySize])
 * - [borrow] from empty freelist: allocates a fresh [IntArray] of
 *   [arraySize] ints (~`arraySize * 4` bytes + object header)
 * - [recycle]: 1 [ArrayDeque] addLast, or no-op if the pool is full
 *
 * **Sentinel**: [emptySentinel] (default `-1`) is written into every
 * slot on [borrow] so callers can detect "unfilled" slots in
 * open-addressing hash tables. Pick a value that cannot collide with
 * any legitimate slot value used by the caller.
 *
 * @param arraySize     Size of each pooled [IntArray]. Must be > 0.
 * @param maxPooled     Maximum number of arrays held in the freelist.
 *                      Recycle past this is a no-op (the array is
 *                      garbage-collected normally). Default 128.
 * @param emptySentinel Value written into every slot on [borrow] so
 *                      callers can detect empty slots. Default `-1`.
 */
class IntArrayPool(
    private val arraySize: Int,
    private val maxPooled: Int = DEFAULT_MAX_POOLED,
    private val emptySentinel: Int = DEFAULT_EMPTY_SENTINEL,
) {
    init {
        require(arraySize > 0) { "arraySize ($arraySize) must be > 0" }
        require(maxPooled >= 0) { "maxPooled ($maxPooled) must be >= 0" }
    }

    private val freelist = ArrayDeque<IntArray>()

    /**
     * Returns an [IntArray] of [arraySize] ints, every slot set to
     * [emptySentinel]. Pulls from the freelist if non-empty; otherwise
     * allocates fresh.
     */
    fun borrow(): IntArray {
        val pooled = freelist.removeFirstOrNull()
        if (pooled != null) {
            pooled.fill(emptySentinel)
            return pooled
        }
        return IntArray(arraySize) { emptySentinel }
    }

    /**
     * Returns [array] to the freelist for reuse on a future [borrow].
     *
     * - If [array]'s size differs from [arraySize], it is silently
     *   dropped (defensive against caller mistakes that would corrupt
     *   the pool).
     * - If the freelist already holds [maxPooled] arrays, [array] is
     *   dropped and garbage-collected normally (bounded memory).
     */
    fun recycle(array: IntArray) {
        if (array.size != arraySize) return
        if (freelist.size < maxPooled) {
            freelist.addLast(array)
        }
    }

    /** Number of arrays currently held in the freelist. For testing / metrics. */
    val pooledCount: Int get() = freelist.size

    companion object {
        const val DEFAULT_MAX_POOLED: Int = 128
        const val DEFAULT_EMPTY_SENTINEL: Int = -1
    }
}
