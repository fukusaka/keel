package io.github.keel.core

import java.nio.ByteBuffer

actual class NativeBuf actual constructor(actual val capacity: Int) {
    private val buf: ByteBuffer = ByteBuffer.allocateDirect(capacity)
    private var writePos = 0
    private var readPos = 0

    actual fun writeByte(value: Byte) {
        buf.put(writePos++, value)
    }

    actual fun readByte(): Byte = buf.get(readPos++)

    actual fun close() {
        // ByteBuffer is GC-managed; nothing to do here
    }
}
