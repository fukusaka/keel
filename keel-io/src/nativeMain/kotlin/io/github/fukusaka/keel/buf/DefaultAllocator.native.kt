package io.github.fukusaka.keel.buf

actual fun defaultAllocator(missProfile: PoolMissProfile?): BufferAllocator =
    SlabAllocator(missProfile = missProfile)
