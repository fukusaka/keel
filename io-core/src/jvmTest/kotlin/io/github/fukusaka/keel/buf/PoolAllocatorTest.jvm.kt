package io.github.fukusaka.keel.buf

actual fun createPoolAllocator(bufferSize: Int, maxPoolSize: Int): BufferAllocator =
    PooledDirectAllocator(bufferSize, maxPoolSize)

actual fun isPoolAllocator(): Boolean = true
