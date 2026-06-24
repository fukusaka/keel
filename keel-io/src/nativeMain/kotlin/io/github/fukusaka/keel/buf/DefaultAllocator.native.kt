package io.github.fukusaka.keel.buf

actual fun defaultAllocator(statsCounter: BufferAllocatorStatsCounter): BufferAllocator =
    SlabAllocator(statsCounter = statsCounter)

actual fun defaultAllocator(
    statsCounter: BufferAllocatorStatsCounter,
    lifecycleListener: BufferAllocatorLifecycleListener,
): BufferAllocator = SlabAllocator(statsCounter = statsCounter, lifecycleListener = lifecycleListener)
