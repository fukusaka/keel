package io.github.fukusaka.keel.buf

/**
 * Singleton zero-capacity [IoBuf] where all read/write operations throw
 * and [retain]/[release] are no-ops.
 *
 * Used as a placeholder where an [IoBuf] reference is structurally
 * required but no data is present — for example, in
 * `HttpBodyEnd.EMPTY` which terminates a streaming request with zero
 * trailing bytes.
 */
object EmptyIoBuf : IoBuf {
    override val capacity: Int get() = 0

    override var readerIndex: Int
        get() = 0
        set(_) {} // no-op for sentinel

    override var writerIndex: Int
        get() = 0
        set(_) {} // no-op for sentinel

    override val readableBytes: Int get() = 0
    override val writableBytes: Int get() = 0

    override fun writeByte(value: Byte) {
        throw UnsupportedOperationException("EmptyIoBuf is immutable")
    }

    override fun writeByteArray(src: ByteArray, offset: Int, length: Int) {
        throw UnsupportedOperationException("EmptyIoBuf is immutable")
    }

    override fun writeAscii(src: String, srcOffset: Int, length: Int) {
        throw UnsupportedOperationException("EmptyIoBuf is immutable")
    }

    override fun copyTo(dest: IoBuf, length: Int) {
        if (length > 0) throw IllegalArgumentException("EmptyIoBuf has no readable bytes")
    }

    override fun readByteArray(dest: ByteArray, offset: Int, length: Int) {
        if (length > 0) throw IllegalArgumentException("EmptyIoBuf has no readable bytes")
    }

    override fun readByte(): Byte {
        throw UnsupportedOperationException("EmptyIoBuf has no readable bytes")
    }

    override fun getByte(index: Int): Byte {
        throw IndexOutOfBoundsException("EmptyIoBuf has no bytes (index=$index)")
    }

    override fun compact() {} // no-op
    override fun clear() {} // no-op

    override fun retain(): IoBuf = this // singleton — no ref count

    override fun release(): Boolean = false // never freed

    override fun close() {} // no-op
}
