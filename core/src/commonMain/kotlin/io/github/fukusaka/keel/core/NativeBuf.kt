package io.github.fukusaka.keel.core

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
expect class NativeBuf(capacity: Int) {
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

    /** Writes [value] at the current write position and advances [writerIndex]. */
    fun writeByte(value: Byte)

    /** Reads a byte from the current read position and advances [readerIndex]. */
    fun readByte(): Byte

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
