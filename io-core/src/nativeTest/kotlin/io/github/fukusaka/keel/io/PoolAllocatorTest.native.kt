package io.github.fukusaka.keel.io

actual fun createPoolAllocator(bufferSize: Int, maxPoolSize: Int): BufferAllocator =
    SlabAllocator(bufferSize, maxPoolSize)
