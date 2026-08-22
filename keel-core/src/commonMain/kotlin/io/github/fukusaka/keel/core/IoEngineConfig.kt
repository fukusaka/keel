package io.github.fukusaka.keel.core

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.defaultAllocator
import io.github.fukusaka.keel.logging.LoggerFactory
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import io.github.fukusaka.keel.pipeline.IoTransport

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
 * @property allocator Root buffer allocator for the engine's I/O buffers.
 *                     Engines never allocate from it directly — each derives its
 *                     working allocator(s) as children via
 *                     [createChild][BufferAllocator.createChild] (one **per
 *                     EventLoop** for the thread-pinned engines — epoll / kqueue /
 *                     nio / io_uring) and allocates from those, so this parent
 *                     stays **borrowed** and can be shared across engines: closing
 *                     an engine drains its own children, not this allocator. The
 *                     epoll and kqueue engines ask it once, while being built,
 *                     whether the buffers they would read into carry a native
 *                     pointer, and refuse to start when they do not. That is one
 *                     allocation and one release against this allocator — nothing
 *                     derived and nothing held open, though on a pooled allocator
 *                     it warms a chunk that stays; see
 *                     `requireNativePointerAccess` for what it measures. The
 *                     Netty engine is the exception — it allocates from each
 *                     channel's own `ByteBufAllocator` (`ch.alloc()`) and consumes
 *                     only this allocator's
 *                     [lifecycleListener][BufferAllocator.lifecycleListener].
 *                     Defaults to the platform's pooled allocator via
 *                     [defaultAllocator] (Native: `SlabAllocator`, JVM:
 *                     `PooledDirectAllocator` — both `PooledAllocator`
 *                     subclasses; JS: `DefaultAllocator`).
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
 *                          Defaults to [IdleReadPolicy.DETECT_PEER_CLOSE]
 *                          so peer FIN is surfaced to user code on every
 *                          engine without explicit configuration —
 *                          matches the contract of pull-model engines
 *                          (kqueue / epoll / netty native / nodejs)
 *                          where peer-close detection is structurally
 *                          free. Workloads that require kernel-level
 *                          TCP back-pressure on the idle window must
 *                          opt in to [IdleReadPolicy.PRESERVE_BACKPRESSURE]
 *                          explicitly.
 * @property readBufferSize Engine-wide **default** size, in bytes, of the
 *                          buffer each pull-model engine (epoll / kqueue /
 *                          nio / io_uring) allocates per socket read.
 *                          [BindConfig.readBufferSize] (per-server) and
 *                          [ConnectConfig.readBufferSize] (per-client)
 *                          override it for the connections they create, so
 *                          this is the value inherited when neither sets one.
 *                          Captured per connection at accept / connect and
 *                          fixed for that connection's lifetime — it never
 *                          changes once communication has started.
 *
 *                          **io_uring exception**: its receive path uses a
 *                          per-EventLoop provided buffer ring shared by all
 *                          connections on the loop, so io_uring honours only
 *                          this engine-wide value and ignores the per-bind /
 *                          per-connect overrides.
 *
 *                          Push-model engines (netty / nodejs / nwconnection)
 *                          size their receive buffer from runtime-delivered
 *                          data and ignore this value entirely. Defaults to
 *                          [IoTransport.DEFAULT_READ_BUFFER_SIZE] (8 KiB).
 *
 *                          Larger values drain the socket in fewer reads
 *                          (fewer syscalls per large transfer); smaller
 *                          values reduce transient per-read memory on
 *                          high-connection-count, low-throughput servers.
 *
 *                          **Must be a power of two** within
 *                          [MIN_READ_BUFFER_SIZE]..[MAX_READ_BUFFER_SIZE].
 *                          The power-of-two constraint keeps every read
 *                          buffer a uniform, shift/mask-addressable segment,
 *                          which the codec layer relies on to address bytes
 *                          across a chain of equal-sized receive segments
 *                          without per-segment bookkeeping.
 * @property idleTimeoutMillis Per-connection idle (no-progress) timeout in
 *                          milliseconds: if no bytes are read from a connection
 *                          for this long, the connection is closed. This is the
 *                          transport-level, protocol-agnostic time-axis defence
 *                          against slowloris / stalled peers — a peer that
 *                          connects then sends nothing (or trickles bytes below
 *                          any size cap) is otherwise held indefinitely. The
 *                          deadline is refreshed on every successful read, so an
 *                          actively progressing connection never trips it. `0`
 *                          (default) disables it. [BindConfig.idleTimeoutMillis]
 *                          (per-server) and [ConnectConfig.idleTimeoutMillis]
 *                          (per-client) override it; captured per connection at
 *                          accept / connect and fixed for that connection's life.
 *
 *                          **Currently honoured by the epoll and kqueue engines.
 *                          The other engines (io_uring / nio / netty / nodejs /
 *                          nwconnection) ignore it for now; it is wired into them
 *                          in follow-up changes.**
 * @property flushCoalescing When `true` (default), the write path coalesces
 *                          per-frame `requestFlush` calls that land in the same
 *                          EventLoop tick into a single gathered send (one
 *                          `writev(2)` on the POSIX engines, one
 *                          `nw_connection_send`/`Socket._writev`/`Channel.flush`
 *                          on the push engines). This is the ~4-7x SSE /
 *                          chunked-streaming speedup delivered by PRs
 *                          #894 / #895 / #896 / #897 — see the release notes
 *                          for the per-engine mechanism.
 *
 *                          When `false`, every `flush()` issues its send
 *                          immediately. Correctness is identical; the trade-off
 *                          is one EL tick of added per-frame latency (μs on
 *                          loopback) for the streaming throughput gain. Choose
 *                          `false` when strict per-frame delivery matters more
 *                          than throughput (real-time protocols, financial
 *                          tickers, latency-sensitive HTTP-long-polling).
 *
 *                          Honoured by the nwconnection, nodejs, netty, nio,
 *                          kqueue, and epoll engines. The io_uring engine
 *                          treats this field as a no-op **by design**: its
 *                          ring-based SQE submission already batches at the
 *                          syscall boundary (one `io_uring_submit_and_wait`
 *                          per EventLoop iteration submits all pending SQEs),
 *                          so there is no per-frame `writev(2)` cost to
 *                          coalesce away. A spike confirmed that layering
 *                          the same defer-to-next-tick shape on top breaks
 *                          the async CQE completion chain (pipeline SSE
 *                          iterations stopped completing) without any
 *                          headroom to justify the required rewrite. This
 *                          is a closed decision, not deferred work.
 */
data class IoEngineConfig(
    val allocator: BufferAllocator = defaultAllocator(),
    val threads: Int = 0,
    val loggerFactory: LoggerFactory = NoopLoggerFactory,
    val resolver: DnsResolver = DnsResolver.SYSTEM,
    val idleReadPolicy: IdleReadPolicy = IdleReadPolicy.DETECT_PEER_CLOSE,
    val readBufferSize: Int = IoTransport.DEFAULT_READ_BUFFER_SIZE,
    val idleTimeoutMillis: Long = 0,
    val flushCoalescing: Boolean = true,
) {
    init {
        requireValidReadBufferSize(readBufferSize)
        requireValidIdleTimeout(idleTimeoutMillis)
    }

    companion object {
        /** Smallest permitted read buffer size (512 B) — holds a typical request line plus a few headers. */
        const val MIN_READ_BUFFER_SIZE: Int = 512

        /** Largest permitted read buffer size (1 MiB) — guards against accidental over-allocation. */
        const val MAX_READ_BUFFER_SIZE: Int = 1 shl 20

        /**
         * Validates a read buffer size: a power of two within
         * [MIN_READ_BUFFER_SIZE]..[MAX_READ_BUFFER_SIZE]. Shared by
         * [IoEngineConfig], [BindConfig], and [ConnectConfig] so every scope
         * that accepts a read buffer size enforces the same invariant (the
         * power-of-two requirement underpins the codec layer's segment
         * addressing — see [readBufferSize]).
         */
        internal fun requireValidReadBufferSize(size: Int) {
            require(size in MIN_READ_BUFFER_SIZE..MAX_READ_BUFFER_SIZE) {
                "readBufferSize must be in $MIN_READ_BUFFER_SIZE..$MAX_READ_BUFFER_SIZE, was $size"
            }
            require(size and (size - 1) == 0) {
                "readBufferSize must be a power of two, was $size"
            }
        }

        /**
         * Validates an idle timeout in milliseconds: non-negative, where `0` means
         * disabled. Shared by [IoEngineConfig], [BindConfig], and [ConnectConfig].
         */
        internal fun requireValidIdleTimeout(millis: Long) {
            require(millis >= 0) { "idleTimeoutMillis must be >= 0 (0 disables), was $millis" }
        }
    }
}
