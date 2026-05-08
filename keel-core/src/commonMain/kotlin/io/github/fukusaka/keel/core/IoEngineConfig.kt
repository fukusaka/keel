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
 * @property resolver DNS resolver used when a [StreamEngine.bind] /
 *                    [StreamEngine.connect] call receives an
 *                    [InetSocketAddress] whose host is a [Host.Name]
 *                    (unresolved hostname). Defaults to
 *                    [DnsResolver.SYSTEM] which wraps the platform's
 *                    blocking system resolver. Substitute with
 *                    [CachingDnsResolver] or a custom implementation
 *                    to share resolution across calls.
 * @property idleReadPolicy Trade-off between peer-close detection and
 *                          TCP back-pressure for the idle-read window
 *                          (`PipelinedChannel.readEnabled = false`).
 *                          Consulted only by engines that face an API
 *                          structural constraint (engine-nio,
 *                          engine-netty's NIO fallback transport,
 *                          engine-nwconnection); other engines achieve
 *                          both simultaneously and silently ignore this
 *                          setting. See [IdleReadPolicy] for the engine
 *                          applicability table and trade-off details.
 *                          Defaults to [IdleReadPolicy.PRESERVE_BACKPRESSURE]
 *                          to keep existing behaviour of the affected
 *                          engines unchanged; opt in to
 *                          [IdleReadPolicy.DETECT_PEER_CLOSE] for
 *                          write-only push clients and accept the
 *                          pre-attach data-loss caveat documented on
 *                          [IdleReadPolicy].
 */
data class IoEngineConfig(
    val allocator: BufferAllocator = defaultAllocator(),
    val threads: Int = 0,
    val loggerFactory: LoggerFactory = NoopLoggerFactory,
    val resolver: DnsResolver = DnsResolver.SYSTEM,
    val idleReadPolicy: IdleReadPolicy = IdleReadPolicy.PRESERVE_BACKPRESSURE,
)
