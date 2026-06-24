package io.github.fukusaka.keel.buf

actual fun defaultAllocator(statsCounter: BufferAllocatorStatsCounter): BufferAllocator =
    PooledDirectAllocator(statsCounter = statsCounter)

actual fun defaultAllocator(
    statsCounter: BufferAllocatorStatsCounter,
    lifecycleListener: BufferAllocatorLifecycleListener,
): BufferAllocator = PooledDirectAllocator(statsCounter = statsCounter, lifecycleListener = lifecycleListener)
