package io.github.fukusaka.keel.buf

/**
 * Strategy object that owns the backing memory of an [IoBuf].
 *
 * Every [IoBuf] carries an immutable [IoBuf.memoryOwner] that decides what
 * happens when the buffer's reference count reaches zero: freeing native
 * memory, returning to an allocator pool, unpinning an externally-wrapped
 * array, releasing a parent slice, returning a kernel-registered slot,
 * etc. The [release] method encodes that strategy; all other state (pool
 * reference, parent ref, kernel slot index, …) is held on the concrete
 * implementation.
 *
 * ```
 *                         IoBuf
 *                           |
 *                           |  val memoryOwner
 *                           v
 *                    IoBufMemoryOwner
 *                    /  |   |   |   \
 *             HeapOwner  PoolOwner  SliceOwner  FixedBufferOwner  ...
 * ```
 *
 * Concrete owners live alongside the component that understands the
 * strategy: keel-io provides the common set ([HeapOwner], [PoolOwner],
 * [SliceOwner], [ExternalWrapOwner]); engine modules add their own
 * (e.g. `RingSlotOwner` and `FixedBufferOwner` in engine-io-uring,
 * `NettyByteBufOwner` in engine-netty).
 *
 * **Thread safety**: owner instances themselves need not be thread-safe
 * because [release] is always invoked from the EventLoop that owns the
 * buffer (see the thread-safety contract in [IoBuf]).
 *
 * **Identifying owners**: engines that need to know the strategy on the
 * hot path (for example, io_uring dispatching to `WRITE_FIXED` when the
 * buffer is backed by kernel-registered memory) use a direct type check
 * such as `owner is FixedBufferOwner` and then read strategy-specific
 * state (`bufIndex`, pool reference, …) off the concrete type. There is
 * no capability tagging on the interface — "which strategy" is the same
 * as "which subtype holds which state".
 */
interface IoBufMemoryOwner {
    /**
     * Called exactly once when [buf]'s reference count reaches zero.
     * Implementations free backing memory, return to a pool, unpin an
     * externally-wrapped array, delegate to a parent buffer, return a
     * kernel-managed slot, or whatever the strategy dictates.
     *
     * Not called from [IoBuf.close]; close is an escape hatch that
     * bypasses the owner (the backing may be leaked — see the close()
     * KDoc for the contract).
     */
    fun release(buf: IoBuf)
}

/**
 * Marker interface implemented by [IoBuf] subtypes whose backing memory
 * is freed directly (as opposed to returned to a pool or handed back to
 * some external owner). Used by [HeapOwner] to delegate the actual
 * platform-specific free.
 *
 * Implementations:
 * - JVM `DirectIoBuf`: no-op (the direct [java.nio.ByteBuffer] is
 *   reclaimed by the JVM's Cleaner).
 * - JS `TypedArrayIoBuf`: no-op (V8 GC).
 * - Native `NativeIoBuf`: calls `nativeHeap.free` on the backing pointer
 *   when the buffer owns its memory; guarded by the `freed` flag for
 *   idempotency.
 *
 * Internal: only meaningful for the common-code [HeapOwner] dispatcher.
 */
internal interface HeapManagedBacking {
    /** Frees the backing memory. Must be idempotent. */
    fun freeHeapBacking()
}

/**
 * Backing-free strategy for buffers that are not pooled and not wrapping
 * an external resource — the platform-native free routine is invoked
 * once at refcount zero. Used by [DefaultAllocator] and as the default
 * for unpooled allocations.
 *
 * Singleton; zero allocation cost per buffer.
 */
object HeapOwner : IoBufMemoryOwner {
    override fun release(buf: IoBuf) {
        (buf as? HeapManagedBacking)?.freeHeapBacking()
    }
}

/**
 * Strategy that returns a [PoolableIoBuf] to its originating pool when
 * the refcount reaches zero. The [returnToPool] lambda closes over the
 * specific pool instance (keyed by size class, per-EventLoop local
 * stack, …) without needing further state on the owner itself.
 *
 * Instance count: typically one per pool (per size class × per
 * EventLoop). Zero per-allocation closure cost: the owner is reused
 * across every allocation of the same pool.
 */
class PoolOwner(
    private val returnToPool: (IoBuf) -> Unit,
) : IoBufMemoryOwner {
    override fun release(buf: IoBuf) {
        returnToPool(buf)
    }
}

/**
 * Strategy for a slice — a child buffer whose backing is a byte range
 * inside a parent buffer. When the slice's refcount reaches zero, the
 * parent's refcount is decremented so the parent itself can be released
 * (or returned to its pool) at its own refcount-zero time.
 *
 * Slices are never valid targets for kernel-registered I/O paths
 * (io_uring Fixed Buffers): a slice points into a sub-range of the
 * parent's backing memory, so the bufIndex that `WRITE_FIXED` requires
 * is not meaningful. Engines detect this by not finding a
 * `FixedBufferOwner` on the slice; its owner is a [SliceOwner] and
 * falls through to the non-fixed I/O path automatically.
 *
 * @param parent The parent buffer whose backing this slice borrows.
 *   Must already be retained by the caller when constructing the slice
 *   so the parent survives at least until the slice is released.
 */
class SliceOwner(
    private val parent: IoBuf,
) : IoBufMemoryOwner {
    override fun release(buf: IoBuf) {
        parent.release()
    }
}

/**
 * Strategy for buffers that wrap an externally-owned resource (for
 * example, a pinned [ByteArray] on Native, a pre-allocated
 * [java.nio.ByteBuffer] on JVM). When the wrapper's refcount reaches
 * zero, [unpin] is invoked to release the hold on the underlying
 * resource back to its real owner.
 *
 * Typical usage: `allocator.wrapBytes(bytes, offset, length)` on
 * platforms that can pin the caller's array.
 */
class ExternalWrapOwner(
    private val unpin: () -> Unit,
) : IoBufMemoryOwner {
    override fun release(buf: IoBuf) {
        unpin()
    }
}
