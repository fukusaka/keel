package io.github.fukusaka.keel.buf

actual fun createPoolAllocator(): BufferAllocator = PooledDirectAllocator()

actual fun createPoolAllocatorWithProfile(profile: PoolMissProfile): BufferAllocator =
    PooledDirectAllocator(statsCounter = profile)

actual fun isPoolAllocator(): Boolean = true
