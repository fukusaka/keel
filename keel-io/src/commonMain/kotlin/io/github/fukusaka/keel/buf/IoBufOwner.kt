package io.github.fukusaka.keel.buf

/**
 * Strategy object that owns the backing memory of an [IoBuf].
 *
 * Every [IoBuf] carries an [PoolableIoBuf.owner] that decides what
 * happens when the reference count reaches zero: freeing native memory,
 * returning the recycled buffer to an allocator pool, unpinning an
 * externally-wrapped resource, or releasing a parent slice. The
 * [release] method encodes that strategy; all other state (pool
 * reference, parent ref, …) is held on the concrete implementation.
 *
 * keel-io provides the common set of owners:
 *
 * - [HeapOwner] — frees the buffer's backing directly.
 * - [PoolOwner] — returns the buffer to its allocator pool.
 * - [SliceOwner] — releases the parent buffer a slice borrows from.
 * - [ExternalWrapOwner] — unpins an externally-wrapped resource.
 *
 * Owners are installed on the buffer at construction and never change
 * for a given buffer (pool reuse keeps the same instance, hence the
 * same owner).
 *
 * **Thread safety**: owner instances themselves need not be thread-safe
 * because [release] is always invoked from the EventLoop that owns the
 * buffer (see the thread-safety contract in [IoBuf]).
 */
interface IoBufOwner {
    /**
     * Called exactly once when [buf]'s reference count reaches zero.
     * Implementations free backing memory, return the recycled buffer
     * to a pool, unpin an externally-wrapped array, or delegate to a
     * parent buffer, depending on the strategy.
     *
     * Not called from [IoBuf.close]; close is an escape hatch that
     * bypasses the owner (see the close() KDoc for the contract).
     */
    fun release(buf: IoBuf)
}

/**
 * Backing-free strategy for buffers that are not pooled and not
 * wrapping an external resource — the platform-native free routine is
 * invoked once at refcount zero. Used by [DefaultAllocator] and as the
 * default [PoolableIoBuf.owner] for unpooled allocations.
 *
 * Implementation is a no-op on JVM / JS where the backing is
 * GC-managed; on Native it dispatches to the buffer's
 * `freeBacking()` which calls `nativeHeap.free`. Routing through the
 * buffer keeps the free path provenance-aware (externally-wrapped
 * Native buffers and slices set `ownsMemory = false` so the same call
 * is correctly a no-op for them).
 *
 * Singleton; zero allocation cost per buffer.
 */
internal object HeapOwner : IoBufOwner {
    override fun release(buf: IoBuf) {
        (buf as PoolableIoBuf).freeBacking()
    }
}

/**
 * Strategy that returns a pooled [IoBuf] to its originating pool when
 * the refcount reaches zero. The [returnToPool] lambda closes over the
 * specific pool instance (keyed by size class, per-EventLoop local
 * stack, …) without needing further state on the owner itself.
 *
 * Instance count: typically one per pool (per size class × per
 * EventLoop). Zero per-allocation closure cost: the owner is reused
 * across every allocation of the same pool.
 */
internal class PoolOwner(
    private val returnToPool: (IoBuf) -> Unit,
) : IoBufOwner {
    override fun release(buf: IoBuf) {
        returnToPool(buf)
    }
}

/**
 * Strategy for a slice — a child buffer whose backing is a byte range
 * inside a parent buffer. When the slice's refcount reaches zero, the
 * parent buffer's refcount is decremented so the parent itself can be
 * released (or returned to its pool) at its own refcount-zero time.
 *
 * @param parent The parent buffer whose backing this slice borrows.
 *   Must already be retained by the caller when constructing the slice
 *   so the parent survives at least until the slice is released.
 */
internal class SliceOwner(
    private val parent: IoBuf,
) : IoBufOwner {
    override fun release(buf: IoBuf) {
        parent.release()
    }
}

/**
 * Strategy for buffers that wrap an externally-owned resource (for
 * example, a pinned [ByteArray] on Native, a pre-allocated
 * [java.nio.ByteBuffer] on JVM). When the refcount reaches zero,
 * [unpin] is invoked to release the hold on the underlying resource
 * back to its real owner.
 *
 * Typical usage: `allocator.wrapBytes(bytes, offset, length)` on
 * platforms that can pin the caller's array.
 */
internal class ExternalWrapOwner(
    private val unpin: () -> Unit,
) : IoBufOwner {
    override fun release(buf: IoBuf) {
        unpin()
    }
}
