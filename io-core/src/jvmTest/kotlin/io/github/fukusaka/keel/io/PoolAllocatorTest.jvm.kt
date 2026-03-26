package io.github.fukusaka.keel.io

actual fun createPoolAllocator(bufferSize: Int, maxPoolSize: Int): BufferAllocator =
    PooledDirectAllocator(bufferSize, maxPoolSize)
