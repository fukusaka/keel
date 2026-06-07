package io.github.fukusaka.keel.buf

actual fun createPoolAllocator(): BufferAllocator = SlabAllocator()

actual fun isPoolAllocator(): Boolean = true
