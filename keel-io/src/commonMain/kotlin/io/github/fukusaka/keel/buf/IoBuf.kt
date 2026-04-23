package io.github.fukusaka.keel.buf

/**
 * A fixed-capacity byte buffer backed by platform-native memory.
 *
 * [IoBuf] is the fundamental data carrier in keel's I/O pipeline.
 * Data flows from the kernel through [IoBuf] to the codec layer
 * (HTTP parser, WebSocket framing) and back:
 *
 * ```
 * kernel recv → IoBuf → BufferedSuspendSource → codec (scanLine/readByte)
 * codec (writeAscii/writeByte) → BufferedSuspendSink → IoBuf → kernel send
 * ```
 *
 * On JVM, backed by a direct [java.nio.ByteBuffer].
 * On Native targets, backed by memory allocated from [kotlinx.cinterop.nativeHeap].
 * On JS, backed by [org.khronos.webgl.Int8Array].
 *
 * ```
 * +-------------------+------------------+------------------+
 * | discardable bytes | readable bytes   | writable bytes   |
 * +-------------------+------------------+------------------+
 * |                   |                  |                  |
 * 0      <=      readerIndex   <=   writerIndex    <=    capacity
 * ```
 *
 * **Reference counting**: newly created buffers start with `refCount = 1`.
 * Call [retain] to increment and [release] to decrement.
 * When the count reaches zero, the underlying memory is freed.
 *
 * **Ownership model**: ownership transfer everywhere. Passing a buffer to
 * a pipeline handler (`ctx.propagateRead` / `ctx.propagateWrite`), a
 * transport (`Channel.write` / `IoTransport.write`), or a sink
 * (`SuspendSink.write`) hands the reference over — the caller must not
 * touch it afterwards (no read/write, no [release], no index inspection).
 * To keep a reference alive (fan-out, delayed processing), call [retain]
 * **before** the transfer. Read APIs (`Channel.read(buf)`) are the
 * inverse: the caller supplies an empty buffer for the engine to fill, and
 * retains ownership throughout. See `website/docs/architecture/buffer.md`
 * for details.
 *
 * **Thread safety**: the reference count and indices are non-atomic. All
 * operations on a given buffer must happen on the single EventLoop thread
 * that owns it.
 * Cross-thread access is a contract violation (not guarded by atomics);
 * ownership transfer across threads must go through a dispatch mechanism
 * (e.g., EventLoop `dispatch`) that provides a happens-before relation.
 *
 * **Engine-layer zero-copy access**: platform-specific implementations
 * expose `unsafePointer` (Native: `CPointer<ByteVar>`) or
 * `unsafeBuffer` (JVM: `ByteBuffer`) via extension properties. These
 * are not on this interface because the types are platform-specific.
 *
 * **Custom implementations**: engines can implement this interface
 * directly (e.g., wrapping kernel-managed buffers) instead of using
 * the default platform allocations. See `RingBufferIoBuf` in engine-io-uring
 * for an example.
 *
 * @see BufferAllocator for creating IoBuf instances
 * @see BufSlice for zero-copy read-only views into IoBuf regions
 */
interface IoBuf {
    /** Buffer capacity in bytes. */
    val capacity: Int

    /** Current read position. */
    var readerIndex: Int

    /** Current write position. */
    var writerIndex: Int

    /** Number of readable bytes (`writerIndex - readerIndex`). */
    val readableBytes: Int

    /** Number of writable bytes (`capacity - writerIndex`). */
    val writableBytes: Int

    /**
     * Strategy object that decides what happens to the backing memory
     * when this buffer's refcount reaches zero.
     *
     * Set once at construction; never changes for a given [IoBuf]
     * instance (pool reuse keeps the same owner — the same instance is
     * handed out again, so its owner naturally matches the pool).
     *
     * Engines that need to dispatch based on memory provenance (e.g.
     * io_uring picking `WRITE_FIXED` vs `WRITE`) use a direct type check
     * such as `owner is FixedBufferOwner` and read the strategy-specific
     * state off the concrete owner type.
     *
     * See [IoBufMemoryOwner] for the taxonomy of concrete owners.
     */
    val memoryOwner: IoBufMemoryOwner

    /**
     * Writes [value] at the current write position and advances [writerIndex].
     *
     * **Precondition**: caller must ensure `writableBytes > 0`. Behaviour when
     * the buffer is full is platform-dependent: JVM throws
     * [IndexOutOfBoundsException], Native silently writes out of bounds
     * (undefined behaviour).
     */
    fun writeByte(value: Byte)

    /**
     * Bulk write: copies [length] bytes from [src] starting at [offset]
     * into this buffer at the current [writerIndex]. Uses platform-optimized
     * copy (memcpy on Native, ByteBuffer.put on JVM) instead of per-byte loop.
     *
     * @throws IllegalArgumentException if [length] exceeds [writableBytes].
     */
    fun writeByteArray(src: ByteArray, offset: Int, length: Int)

    /**
     * Writes the low 8 bits of each character from [src] directly into this
     * buffer without intermediate ByteArray allocation.
     *
     * Suitable for text-based protocols (HTTP/1.1, SMTP, Redis, Memcached)
     * where commands and headers are ASCII (0x00–0x7F). Also correct for
     * Latin-1 / ISO 8859-1 (0x80–0xFF). NOT correct for multi-byte
     * encodings (UTF-8 with codepoints > 0xFF).
     *
     * For UTF-8 encoded strings, use `[writeByteArray]` with
     * `text.encodeToByteArray()`.
     *
     * @throws IllegalArgumentException if [length] exceeds [writableBytes].
     */
    fun writeAscii(src: String, srcOffset: Int, length: Int)

    /**
     * Bulk copy: copies [length] bytes from this buffer's current [readerIndex]
     * into [dest] at its current [writerIndex] using platform-optimized copy
     * (memcpy on Native, ByteBuffer.put on JVM, Int8Array.set on JS).
     *
     * After this call, this buffer's [readerIndex] and [dest]'s [writerIndex]
     * both advance by [length].
     *
     * **Platform constraint**: both buffers must be the same platform type
     * (e.g., both [NativePointerAccess][io.github.fukusaka.keel.buf.NativePointerAccess]
     * on Native). Mixing types throws [ClassCastException].
     *
     * @throws IllegalArgumentException if [length] exceeds [readableBytes] or [dest]'s [writableBytes].
     */
    fun copyTo(dest: IoBuf, length: Int)

    /**
     * Bulk read: copies [length] bytes from this buffer's current [readerIndex]
     * into [dest] starting at [offset]. Uses platform-optimized copy
     * (memcpy on Native, ByteBuffer.get on JVM) instead of per-byte loop.
     *
     * After this call, [readerIndex] advances by [length].
     *
     * @throws IllegalArgumentException if [length] exceeds [readableBytes].
     */
    fun readByteArray(dest: ByteArray, offset: Int, length: Int)

    /**
     * Reads a byte from the current read position and advances [readerIndex].
     *
     * **Precondition**: caller must ensure `readableBytes > 0`. Behaviour when
     * no data is available is platform-dependent: JVM throws
     * [IndexOutOfBoundsException], Native reads uninitialised memory
     * (undefined behaviour).
     */
    fun readByte(): Byte

    /**
     * Reads a byte at the given absolute [index] without modifying [readerIndex].
     *
     * Used by [BufSlice] for random access within a buffer region.
     *
     * **Precondition**: caller must ensure `0 <= index < capacity`. Behaviour
     * for out-of-bounds access is platform-dependent: JVM throws
     * [IndexOutOfBoundsException], Native is undefined behaviour.
     */
    fun getByte(index: Int): Byte

    /**
     * Discards already-read bytes by moving readable data to the beginning
     * of the buffer, maximizing writable space.
     *
     * After compact: `readerIndex = 0`, `writerIndex = readableBytes`.
     * No-op if `readerIndex` is already 0.
     */
    fun compact()

    /**
     * Resets both [readerIndex] and [writerIndex] to 0, making the entire
     * buffer writable. Does not zero the memory.
     */
    fun clear()

    /**
     * Increments the reference count and returns this buffer for chaining.
     *
     * @throws IllegalStateException if the buffer has already been fully released.
     */
    fun retain(): IoBuf

    /**
     * Decrements the reference count.
     * If it reaches zero, releases the underlying memory and returns `true`.
     * Otherwise returns `false`.
     *
     * @throws IllegalStateException if the buffer has already been fully released.
     */
    fun release(): Boolean

    /**
     * Teardown escape hatch: forces the reference count to zero without
     * invoking [memoryOwner]'s normal release path.
     *
     * **Prefer [release] for normal lifecycle management.** [close] is
     * an escape for engine shutdown / emergency teardown scenarios where
     * holding a pool slot or kernel-registered index is acceptable to
     * leak (the whole allocator or engine is going away anyway). It
     * intentionally bypasses [memoryOwner] so pool returns and kernel
     * slot returns do not happen; for heap-backed buffers, backing
     * memory is freed directly via the concrete IoBuf type.
     *
     * Safe to call multiple times (idempotent).
     */
    fun close()
}

/**
 * Extended [IoBuf] interface for pool-managed buffers.
 *
 * Adds [nextLink] for intrusive freelist, [resetForReuse] for pool
 * recycling, and a widened [memoryOwner] (var) that internal
 * decorators such as [TrackingAllocator] / [LeakDetectingAllocator]
 * can wrap. Public [IoBuf.memoryOwner] stays `val` so external
 * callers cannot mutate the owner mid-life; only engine-internal
 * tooling in keel-io reaches here. Used internally by
 * [BufferAllocator] implementations within io-core.
 */
internal interface PoolableIoBuf : IoBuf {

    /**
     * Internal-mutable owner slot. Default construction assigns an
     * immutable owner (matches the [IoBuf.memoryOwner] contract from
     * the caller's perspective); decorators that need to intercept the
     * release path replace it in-place.
     */
    override var memoryOwner: IoBufMemoryOwner

    /**
     * Intrusive link for lock-free pool freelists (Treiber stack).
     *
     * Non-null only while this buffer resides in a pool's freelist.
     * Used by pool-based allocators to build freelists without wrapper
     * node allocations.
     */
    var nextLink: IoBuf?

    /**
     * Resets the buffer for reuse without freeing the underlying memory.
     *
     * Restores [readerIndex], [writerIndex] to 0, reference count to 1,
     * and [nextLink] to null. [memoryOwner] is preserved across reuses.
     */
    fun resetForReuse()
}

/**
 * Creates a platform-default [IoBuf] instance.
 *
 * Platform implementations:
 * - **Native**: [NativeIoBuf] — `nativeHeap.allocArray<ByteVar>(capacity)`
 * - **JVM**: [DirectIoBuf] — `ByteBuffer.allocateDirect(capacity)`
 * - **JS**: [TypedArrayIoBuf] — `Int8Array(capacity)`
 *
 * Used by [DefaultAllocator]. Engines and tests should prefer
 * [BufferAllocator.allocate] over calling this directly.
 */
internal expect fun createDefaultIoBuf(capacity: Int): IoBuf
