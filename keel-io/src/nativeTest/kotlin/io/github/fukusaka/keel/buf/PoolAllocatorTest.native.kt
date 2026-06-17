package io.github.fukusaka.keel.buf

actual fun createPoolAllocator(): BufferAllocator = SlabAllocator()

actual fun createPoolAllocatorWithProfile(profile: PoolMissProfile): BufferAllocator =
    SlabAllocator(statsCounter = profile)

actual fun createPoolAllocatorWithListener(listener: BufferAllocatorLifecycleListener): BufferAllocator =
    SlabAllocator(lifecycleListener = listener)

actual fun isPoolAllocator(): Boolean = true
