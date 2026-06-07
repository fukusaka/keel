package io.github.fukusaka.keel.buf

actual fun createPoolAllocator(): BufferAllocator = PooledDirectAllocator()

actual fun isPoolAllocator(): Boolean = true
