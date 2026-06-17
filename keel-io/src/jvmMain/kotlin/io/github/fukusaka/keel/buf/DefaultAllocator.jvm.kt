package io.github.fukusaka.keel.buf

actual fun defaultAllocator(statsCounter: BufferAllocatorStatsCounter): BufferAllocator =
    PooledDirectAllocator(statsCounter = statsCounter)
