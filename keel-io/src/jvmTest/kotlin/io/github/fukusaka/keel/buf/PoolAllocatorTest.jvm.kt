package io.github.fukusaka.keel.buf

actual fun createPoolAllocator(): BufferAllocator = PooledDirectAllocator()

actual fun createPoolAllocatorWithProfile(profile: PoolMissProfile): BufferAllocator =
    PooledDirectAllocator(statsCounter = profile)

actual fun createPoolAllocatorWithListener(listener: BufferAllocatorLifecycleListener): BufferAllocator =
    PooledDirectAllocator(lifecycleListener = listener)

actual fun isPoolAllocator(): Boolean = true
