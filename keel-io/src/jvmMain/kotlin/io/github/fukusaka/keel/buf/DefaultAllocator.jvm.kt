package io.github.fukusaka.keel.buf

actual fun defaultAllocator(missProfile: PoolMissProfile?): BufferAllocator =
    PooledDirectAllocator(missProfile = missProfile)
