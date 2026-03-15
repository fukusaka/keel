package io.github.keel.core

expect class NativeBuf(capacity: Int) {
    val capacity: Int
    fun writeByte(value: Byte)
    fun readByte(): Byte
    fun close()
}
