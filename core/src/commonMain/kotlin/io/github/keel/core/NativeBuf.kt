package io.github.keel.core

/**
 * A fixed-capacity byte buffer backed by native memory.
 *
 * On JVM, backed by a direct [java.nio.ByteBuffer].
 * On Native targets, backed by memory allocated from [kotlinx.cinterop.nativeHeap].
 * Must be closed after use to avoid memory leaks.
 *
 * @param capacity Buffer size in bytes.
 */
expect class NativeBuf(capacity: Int) {
    /** Buffer capacity in bytes. */
    val capacity: Int

    /** Writes [value] at the current write position. */
    fun writeByte(value: Byte)

    /** Reads a byte from the current read position. */
    fun readByte(): Byte

    /** Releases the underlying native memory. */
    fun close()
}
