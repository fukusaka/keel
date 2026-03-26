package io.github.fukusaka.keel.io

import java.nio.ByteBuffer

/**
 * JVM [NativeBuf] implementation backed by a direct [ByteBuffer].
 *
 * Uses `ByteBuffer.allocateDirect(capacity)` for off-heap memory that
 * can be passed directly to NIO `SocketChannel.read/write` without
 * copying. The [unsafeBuffer] property exposes the underlying [ByteBuffer].
 *
 * **Reference counting**: non-atomic (single-threaded EventLoop model).
 * [close] is a no-op since the [ByteBuffer] is GC-managed.
 *
 * **position/limit management**: [clear] resets both `position` and `limit`
 * on the underlying [ByteBuffer] because NIO `SocketChannel.write` may
 * set `limit` to a smaller value, causing subsequent `put(index, value)`
 * to throw [IndexOutOfBoundsException] if index >= limit.
 */
actual class NativeBuf internal actual constructor(actual val capacity: Int) {
    private val buf: ByteBuffer = ByteBuffer.allocateDirect(capacity)

    /** Direct ByteBuffer for engine-layer zero-copy I/O. */
    val unsafeBuffer: ByteBuffer get() = buf
    private var refCount = 1
    internal actual var deallocator: ((NativeBuf) -> Unit)? = null

    actual var readerIndex: Int = 0
    actual var writerIndex: Int = 0

    actual val readableBytes: Int get() = writerIndex - readerIndex
    actual val writableBytes: Int get() = capacity - writerIndex

    actual fun writeByte(value: Byte) {
        buf.put(writerIndex++, value)
    }

    actual fun writeBytes(src: ByteArray, offset: Int, length: Int) {
        require(length <= writableBytes) { "length $length exceeds writableBytes $writableBytes" }
        // ByteBuffer.put(src, offset, length) uses optimized bulk copy.
        // Must set position first since put(byte[], off, len) writes at position.
        buf.position(writerIndex)
        buf.put(src, offset, length)
        writerIndex += length
    }

    actual fun readByte(): Byte = buf.get(readerIndex++)

    actual fun compact() {
        if (readerIndex > 0) {
            val readable = readableBytes
            if (readable > 0) {
                // Use ByteBuffer.compact(): copies bytes between position and
                // limit to the beginning, then sets position = remaining.
                buf.position(readerIndex)
                buf.limit(writerIndex)
                buf.compact()
                // Reset limit to capacity (compact sets it to capacity already)
                buf.limit(capacity)
            }
            readerIndex = 0
            writerIndex = readable
        }
    }

    actual fun clear() {
        readerIndex = 0
        writerIndex = 0
        // Reset DirectByteBuffer position/limit to match. Without this,
        // limit may be left at a smaller value from a previous
        // SocketChannel.write (via flushSingle), causing absolute put()
        // to throw IndexOutOfBoundsException (index >= limit).
        buf.position(0)
        buf.limit(capacity)
    }

    actual fun retain(): NativeBuf {
        check(refCount > 0) { "Cannot retain a released buffer" }
        refCount++
        return this
    }

    actual fun release(): Boolean {
        check(refCount > 0) { "Buffer already released" }
        if (--refCount == 0) {
            val d = deallocator
            if (d != null) {
                d(this)
            } else {
                close()
            }
            return true
        }
        return false
    }

    actual fun close() {
        refCount = 0
        // ByteBuffer is GC-managed; nothing to do here
    }
}
