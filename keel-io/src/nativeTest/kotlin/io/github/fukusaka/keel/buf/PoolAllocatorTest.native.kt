package io.github.fukusaka.keel.buf

actual fun createPoolAllocator(): BufferAllocator = SlabAllocator()

actual fun createPoolAllocatorWithProfile(profile: PoolMissProfile): BufferAllocator =
    SlabAllocator(missProfile = profile)

actual fun isPoolAllocator(): Boolean = true
