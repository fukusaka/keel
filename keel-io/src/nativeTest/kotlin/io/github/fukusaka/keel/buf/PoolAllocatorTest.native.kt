package io.github.fukusaka.keel.buf

actual fun createPoolAllocator(bufferSize: Int, maxPoolSize: Int): BufferAllocator =
    SlabAllocator().also { it.registerPoolSize(bufferSize, maxPoolSize) }

actual fun isPoolAllocator(): Boolean = true
