package io.github.fukusaka.keel.io

/**
 * A fixed-capacity byte buffer backed by platform-native memory.
 *
 * On JVM, backed by a direct [java.nio.ByteBuffer].
 * On Native targets, backed by memory allocated from [kotlinx.cinterop.nativeHeap].
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
 * Thread safety: single-threaded (EventLoop model). AtomicInt deferred
 * to Phase (b) if needed.
 *
 * **Engine-layer zero-copy access**: platform-specific actual classes
 * expose `unsafePointer` (Native: `CPointer<ByteVar>`) or
 * `unsafeBuffer` (JVM: `ByteBuffer`) for passing directly to OS syscalls.
 * These are not in the expect declaration because the types are
 * platform-specific.
 *
 * @param capacity Buffer size in bytes.
 */
expect class NativeBuf internal constructor(capacity: Int) {
    /** Buffer capacity in bytes. */
    val capacity: Int

    /**
     * Callback invoked when [release] decrements the reference count to zero.
     *
     * Set by the [BufferAllocator] that created this buffer. Pool-based
     * allocators set this to return the buffer to the pool instead of
     * freeing the underlying memory.
     *
     * When `null`, [release] falls back to [close] (direct memory free).
     */
    internal var deallocator: ((NativeBuf) -> Unit)?

    /** Current read position. */
    var readerIndex: Int

    /** Current write position. */
    var writerIndex: Int

    /** Number of readable bytes (`writerIndex - readerIndex`). */
    val readableBytes: Int

    /** Number of writable bytes (`capacity - writerIndex`). */
    val writableBytes: Int

    /** Writes [value] at the current write position and advances [writerIndex]. */
    fun writeByte(value: Byte)

    /**
     * Bulk write: copies [length] bytes from [src] starting at [offset]
     * into this buffer at the current [writerIndex]. Uses platform-optimized
     * copy (memcpy on Native, ByteBuffer.put on JVM) instead of per-byte loop.
     *
     * @throws IllegalArgumentException if [length] exceeds [writableBytes].
     */
    fun writeBytes(src: ByteArray, offset: Int, length: Int)

    /** Reads a byte from the current read position and advances [readerIndex]. */
    fun readByte(): Byte

    /**
     * Reads a byte at the given absolute [index] without modifying [readerIndex].
     *
     * Used by [BufSlice] for random access within a buffer region.
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
     * Resets the buffer for reuse from a pool.
     * Restores [readerIndex], [writerIndex] to 0 and reference count to 1.
     * Called by pool-based allocators when recycling a released buffer.
     */
    internal fun resetForReuse()

    /** Increments the reference count and returns this buffer for chaining. */
    fun retain(): NativeBuf

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
     * Prefer [release] for normal lifecycle management.
     */
    fun close()
}
