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
 * **Thread safety**: designed for single-threaded use (EventLoop model).
 * Concurrent access from multiple threads requires external synchronisation.
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
    fun writeBytes(src: ByteArray, offset: Int, length: Int)

    /**
     * Writes ASCII characters from [src] directly into this buffer without
     * intermediate ByteArray allocation. Each character is truncated to its
     * low 8 bits (`char.code.toByte()`), which is correct for US-ASCII
     * (HTTP headers, status lines).
     *
     * This avoids the `String.encodeToByteArray()` allocation that
     * [writeBytes] would require when the source is a [String].
     *
     * @throws IllegalArgumentException if [length] exceeds [writableBytes].
     */
    fun writeAsciiString(src: String, srcOffset: Int, length: Int)

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
     * Releases the underlying native memory immediately, ignoring the reference count.
     *
     * Safe to call multiple times (idempotent on Native via `freed` flag;
     * no-op on JVM/JS where memory is GC-managed).
     *
     * Prefer [release] for normal lifecycle management.
     */
    fun close()
}

/**
 * Extended [IoBuf] interface for pool-managed buffers.
 *
 * Adds [deallocator] callback, [nextLink] for intrusive freelist,
 * and [resetForReuse] for pool recycling. Used internally by
 * [BufferAllocator] implementations within io-core.
 */
internal interface PoolableIoBuf : IoBuf {

    /**
     * Callback invoked when [release] decrements the reference count to zero.
     *
     * Set by the [BufferAllocator] that created this buffer. Pool-based
     * allocators set this to return the buffer to the pool instead of
     * freeing the underlying memory.
     *
     * When `null`, [release] falls back to [close] (direct memory free).
     */
    var deallocator: ((IoBuf) -> Unit)?

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
     * and [nextLink] to null. [deallocator] is preserved across reuses.
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
