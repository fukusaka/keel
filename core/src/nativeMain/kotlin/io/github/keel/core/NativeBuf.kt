package io.github.keel.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import kotlinx.cinterop.nativeHeap

@OptIn(ExperimentalForeignApi::class)
actual class NativeBuf actual constructor(actual val capacity: Int) {
    private val ptr = nativeHeap.allocArray<ByteVar>(capacity)
    private var writePos = 0
    private var readPos = 0

    actual fun writeByte(value: Byte) {
        ptr[writePos++] = value
    }

    actual fun readByte(): Byte = ptr[readPos++]

    actual fun close() {
        nativeHeap.free(ptr.rawValue)
    }
}
