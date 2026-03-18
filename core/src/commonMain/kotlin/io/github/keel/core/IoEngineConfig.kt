package io.github.keel.core

/**
 * Configuration shared across all [IoEngine] implementations.
 *
 * ```
 * Config scope:
 *   IoEngineConfig   -- engine-wide (allocator, threads)
 *   bind() params    -- per-server  (backlog: deferred)
 *   Channel props    -- per-channel (readTimeout, tcpNoDelay: deferred)
 * ```
 *
 * Will evolve into a DSL builder as more options are added.
 * The migration from data class to DSL is non-breaking.
 *
 * @property allocator Buffer allocator for all channels created by this engine.
 *                     Defaults to [HeapAllocator] (suitable for tests).
 *                     Production engines should use a pooled allocator.
 * @property threads   Number of EventLoop threads. Defaults to 1 (single-threaded).
 *                     Ignored by engines that manage their own threads
 *                     (Netty: NioEventLoopGroup, Node.js: V8 runtime).
 */
data class IoEngineConfig(
    val allocator: BufferAllocator = HeapAllocator,
    val threads: Int = 1,
)
