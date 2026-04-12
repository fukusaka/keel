package io.github.fukusaka.keel.buf

actual fun createPoolAllocator(bufferSize: Int, maxPoolSize: Int): BufferAllocator =
    PooledDirectAllocator().also { it.registerPoolSize(bufferSize, maxPoolSize) }

actual fun isPoolAllocator(): Boolean = true
