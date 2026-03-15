package io.github.keel.core

import java.nio.ByteBuffer

actual class NativeBuf actual constructor(actual val capacity: Int) {
    private val buf: ByteBuffer = ByteBuffer.allocateDirect(capacity)

    actual fun writeByte(value: Byte) {
        buf.put(value)
    }

    actual fun readByte(): Byte = buf.get()

    actual fun close() {
        // ByteBuffer is GC-managed; nothing to do here
    }
}
