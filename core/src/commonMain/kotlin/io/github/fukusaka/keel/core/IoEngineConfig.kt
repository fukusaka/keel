package io.github.fukusaka.keel.core

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.defaultAllocator
import io.github.fukusaka.keel.logging.LoggerFactory
import io.github.fukusaka.keel.logging.NoopLoggerFactory

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
 *                     Defaults to the platform's pooled allocator via
 *                     [defaultAllocator] (Native: SlabAllocator, JVM:
 *                     PooledDirectAllocator, JS: DefaultAllocator).
 * @property threads   Number of worker EventLoop threads. 0 (default) means
 *                     auto-detect based on available CPU cores. Each engine
 *                     resolves 0 to `availableProcessors()` at construction.
 *                     Netty passes 0 directly to `NioEventLoopGroup(0)` which
 *                     uses its own default (`cpu * 2`). Node.js ignores this
 *                     (V8 runtime manages its own threads).
 * @property loggerFactory Factory for creating [io.github.fukusaka.keel.logging.Logger]
 *                         instances. Defaults to [NoopLoggerFactory] which discards
 *                         all log output (zero overhead).
 */
data class IoEngineConfig(
    val allocator: BufferAllocator = defaultAllocator(),
    val threads: Int = 0,
    val loggerFactory: LoggerFactory = NoopLoggerFactory,
)
