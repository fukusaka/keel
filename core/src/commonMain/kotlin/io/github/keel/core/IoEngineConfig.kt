package io.github.keel.core

/**
 * Configuration shared across all [IoEngine] implementations.
 *
 * Will evolve into a DSL builder as more options are added.
 */
data class IoEngineConfig(
    val allocator: BufferAllocator = HeapAllocator,
    val threads: Int = 1,
)
