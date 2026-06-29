package io.github.fukusaka.keel.buf

/**
 * Bitmap allocator for *small* elements that share one page-run of a [PoolChunk].
 *
 * Modelled on Netty 4.2.12.Final's `io.netty.buffer.PoolSubpage`. A run of
 * [runSize] bytes is divided into `runSize / elemSize` fixed-size elements; a
 * `Long[]` bitmap tracks which are in use (1 = used). [allocate] returns a packed
 * 64-bit handle whose low 32 bits carry the element's `bitmapIdx` (and whose high
 * bits carry the run's position — see [PoolChunk] for the layout); [free] clears
 * a bit and reports whether the subpage should stay in the pool.
 *
 * **Pool membership.** Subpages with at least one free element are linked into a
 * per-size-class doubly-linked list whose sentinel `head` is supplied by the
 * caller (the arena in the wired allocator; a test sentinel here). The list lets
 * the allocator find a partially-free subpage without rescanning chunks. This
 * class manages its own [prev] / [next] pointers via [addToPool] / [removeFromPool].
 *
 * **Thread safety.** None of its own — callers serialise access (the arena holds
 * the head's lock in the wired allocator). Phase 3 is pure single-threaded logic.
 *
 * @property runOffset the run's first page index within the owning chunk.
 * @property runSize the run length in bytes.
 * @property elemSize the element size in bytes (a [SizeClasses] small class size).
 * @property sizeIdx the [SizeClasses] index of [elemSize] (carried for the pool head).
 */
internal class PoolSubpage private constructor(
    val pageShifts: Int,
    val runOffset: Int,
    val runSize: Int,
    val elemSize: Int,
    val sizeIdx: Int,
    private val isHead: Boolean,
) {
    /** Total element slots in the run. */
    val maxNumElems: Int = if (isHead) 0 else runSize / elemSize

    private val bitmapLength: Int = (maxNumElems + BITS_PER_LONG - 1) ushr BITS_PER_LONG_SHIFT
    private val bitmap: LongArray = LongArray(if (isHead) 0 else bitmapLength)

    /** Free element slots. */
    var numAvail: Int = maxNumElems
        private set

    private var nextAvail: Int = 0
    private var doNotDestroy: Boolean = true

    // Pool doubly-linked list pointers (valid for non-head subpages once pooled).
    var prev: PoolSubpage? = null
    var next: PoolSubpage? = null

    /**
     * The wired chunk this subpage was carved from, set by the arena after creation.
     * A cross-chunk pool (the per-arena `smallSubpagePools`) links subpages from
     * different chunks under one head, so a pool hit must resolve the subpage back to
     * its backing through this reference. `null` for the list-head sentinel.
     */
    var ownerChunk: PooledChunk? = null

    /** Allocates one element; returns its packed handle, or [NO_HANDLE] when full / destroyed. */
    fun allocate(): Long {
        if (numAvail == 0 || !doNotDestroy) return NO_HANDLE
        val bitmapIdx = nextAvailBit()
        val q = bitmapIdx ushr BITS_PER_LONG_SHIFT
        val r = bitmapIdx and (BITS_PER_LONG - 1)
        bitmap[q] = bitmap[q] or (1L shl r)
        // A full subpage leaves the "has free elements" pool; free() re-adds it.
        if (--numAvail == 0) {
            removeFromPool()
        }
        return toHandle(bitmapIdx)
    }

    /**
     * Frees the element at [bitmapIdx]. Returns `true` if the subpage should stay
     * usable, `false` if it became fully free and may be released (its run
     * returned to the chunk). [head] is the size-class list sentinel used for
     * re-adding a previously-full subpage.
     */
    fun free(head: PoolSubpage, bitmapIdx: Int): Boolean {
        val q = bitmapIdx ushr BITS_PER_LONG_SHIFT
        val r = bitmapIdx and (BITS_PER_LONG - 1)
        bitmap[q] = bitmap[q] and (1L shl r).inv()
        nextAvail = bitmapIdx
        // Transition from full -> has-free: rejoin the pool so it can be found again.
        if (numAvail++ == 0) {
            addToPool(head)
            // A 1-element subpage is both full and empty; keep it pooled.
            if (maxNumElems > 1) return true
        }
        if (numAvail != maxNumElems) {
            return true
        }
        // Fully free. Preserve it if it is the only subpage in the pool, else
        // detach so the caller can return the run to the chunk.
        return if (prev === next) {
            true
        } else {
            doNotDestroy = false
            removeFromPool()
            false
        }
    }

    /** Links this subpage right after [head]. */
    fun addToPool(head: PoolSubpage) {
        prev = head
        next = head.next
        next!!.prev = this
        head.next = this
    }

    private fun removeFromPool() {
        prev!!.next = next
        next!!.prev = prev
        next = null
        prev = null
    }

    /**
     * Unlinks this subpage from its pool if it is currently linked (a preserved
     * fully-free subpage that stayed pooled). Called when its owning chunk is being
     * reclaimed, so the per-arena pool head does not dangle into freed backing.
     */
    fun unlinkIfPooled() {
        if (prev != null) removeFromPool()
    }

    private fun nextAvailBit(): Int {
        val cached = nextAvail
        if (cached >= 0) {
            nextAvail = -1
            return cached
        }
        return findNextAvail()
    }

    private fun findNextAvail(): Int {
        for (i in 0 until bitmapLength) {
            val bits = bitmap[i]
            if (bits.inv() != 0L) {
                return findNextAvail0(i, bits)
            }
        }
        return -1
    }

    private fun findNextAvail0(i: Int, bits: Long): Int {
        val baseVal = i shl BITS_PER_LONG_SHIFT
        var b = bits
        for (j in 0 until BITS_PER_LONG) {
            if (b and 1L == 0L) {
                val value = baseVal or j
                if (value < maxNumElems) return value else break
            }
            b = b ushr 1
        }
        return -1
    }

    private fun toHandle(bitmapIdx: Int): Long {
        val pages = runSize ushr pageShifts
        return (runOffset.toLong() shl PoolChunk.RUN_OFFSET_SHIFT) or
            (pages.toLong() shl PoolChunk.SIZE_SHIFT) or
            (1L shl PoolChunk.IS_USED_SHIFT) or
            (1L shl PoolChunk.IS_SUBPAGE_SHIFT) or
            bitmapIdx.toLong()
    }

    companion object {
        /** Sentinel returned by [allocate] when no element is available. */
        const val NO_HANDLE: Long = -1L

        private const val BITS_PER_LONG = 64
        private const val BITS_PER_LONG_SHIFT = 6

        /** Creates a list-head sentinel for one size class (carries no elements). */
        fun newHead(pageShifts: Int): PoolSubpage =
            PoolSubpage(pageShifts, runOffset = 0, runSize = 0, elemSize = 0, sizeIdx = -1, isHead = true).also {
                it.prev = it
                it.next = it
            }

        /** Carves a subpage from a run and links it into [head]'s pool. */
        fun create(
            head: PoolSubpage,
            pageShifts: Int,
            runOffset: Int,
            runSize: Int,
            elemSize: Int,
            sizeIdx: Int,
        ): PoolSubpage {
            val sub = PoolSubpage(pageShifts, runOffset, runSize, elemSize, sizeIdx, isHead = false)
            sub.addToPool(head)
            return sub
        }
    }
}
