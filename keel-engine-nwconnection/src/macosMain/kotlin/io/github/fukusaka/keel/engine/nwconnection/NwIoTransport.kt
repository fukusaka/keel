@file:OptIn(UnsafeIoBufApi::class)

package io.github.fukusaka.keel.engine.nwconnection

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.UnsafeIoBufApi
import io.github.fukusaka.keel.buf.unsafePointer
import io.github.fukusaka.keel.codec.http.installScopedHeadersPool
import io.github.fukusaka.keel.core.IdleReadPolicy
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.warn
import io.github.fukusaka.keel.pipeline.AbstractIoTransport
import io.github.fukusaka.keel.pipeline.AbstractIoTransport.PendingWrite
import io.github.fukusaka.keel.pipeline.EventLoopTimer
import io.github.fukusaka.keel.pipeline.IoTransport
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.staticCFunction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import nwconnection.keel_nw_dispatch_data_release
import nwconnection.keel_nw_read_async
import nwconnection.keel_nw_shutdown_output
import nwconnection.keel_nw_write_async
import nwconnection.keel_nw_writev_async
import platform.Network.nw_connection_cancel
import platform.Network.nw_connection_t
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_t

/**
 * Non-suspend [IoTransport] for NWConnection pipeline channels.
 *
 * Handles both read and write paths for NWConnection. Unlike kqueue/epoll
 * transports which use POSIX `read()` directly into [IoBuf], NWConnection
 * delivers data as `dispatch_data_t`. The C wrapper [keel_nw_read_async]
 * implements a **dual-path receive**: single-region `dispatch_data_t`
 * (the empirical 100% case for loopback TCP, verified 2026-05-20) is
 * wrapped zero-copy as a [DispatchDataIoBuf] (engine-direct), while multi-region
 * data falls back to per-region memcpy into the pre-allocated buffer.
 *
 * **Read path**: [readEnabled] arms the async read loop via [keel_nw_read_async].
 * The dispatch callback delivers either (a) a retained `dispatch_data_t`
 * handle + region pointer for the zero-copy branch, or (b) the
 * pre-allocated buffer filled by memcpy for the multi-region branch.
 * EOF/error invokes [onReadClosed].
 *
 * **Idle-read trade-off** ([idleReadPolicy]): NWConnection has no
 * event-readiness API analogous to kqueue's `EV_EOF` flag delivered
 * separately from data — peer-FIN is observable only through an active
 * `nw_connection_receive` completion (`is_complete = true` with `len =
 * 0`). The chosen [IdleReadPolicy] picks which side of the trade-off
 * is preserved while [readEnabled] is `false`:
 *
 * - [IdleReadPolicy.DETECT_PEER_CLOSE]: arm a receive at construction
 *   so peer FIN surfaces through [onReadClosed] within milliseconds
 *   regardless of [readEnabled]. The cost is twofold and specific to
 *   NWConnection's push-model API:
 *     1. `nw_connection_receive` *consumes* bytes from the framework
 *        receive buffer — there is no "ready notification without
 *        consume" — so kernel-level TCP back-pressure is not preserved
 *        while [readEnabled] is `false`.
 *     2. Bytes delivered before the channel's pipeline acquires its
 *        first user inbound handler land at the pipeline's
 *        `TailHandler` and are released with a `WARN` log; this
 *        breaks pull-mode `read(buf)` flows that race the peer write
 *        against the first ensureBridge call. A planned follow-up
 *        adds a pre-attach event journal + dispatcher-tick drain to
 *        `DefaultPipeline` that closes this caveat.
 *
 * - [IdleReadPolicy.PRESERVE_BACKPRESSURE]: keep the receive disarmed
 *   while [readEnabled] is `false`. The framework receive buffer
 *   retains bytes and TCP back-pressure stalls the peer; peer FIN is
 *   not surfaced until [readEnabled] flips back to `true` or
 *   `SO_KEEPALIVE` declares the peer dead.
 *
 * See [IdleReadPolicy] for the engine applicability table and the
 * recommended policy per workload.
 *
 * **Write path**: Sends outbound [IoBuf] writes via `nw_connection_send`.
 * NWConnection handles EAGAIN internally — `nw_connection_send` accepts data
 * immediately and delivers completion asynchronously via a dispatch queue callback.
 *
 * **Buffer lifecycle**: `write()` retains the buffer. The write callback
 * releases all pending buffers after the send completes.
 *
 * **StableRef ownership**: [armRead] creates a StableRef pointing to a
 * [ReadContext]. The read callback disposes and re-arms on each invocation.
 * On close, the closed flag prevents re-arming.
 *
 * **I/O ownership invariant**: every callback (read completion, write
 * completion, the teardown block dispatched from [close]) and every
 * coroutine resumption that uses [ioDispatcher] runs on [connQueue],
 * the per-connection serial dispatch queue, in FIFO order.
 * `pendingWrites` / `pendingBytes` / `pendingReadBuf` and other
 * single-thread-invariant state are read and written only from that
 * queue. This is the upstream-delegated counterpart of the explicit
 * `if (inEventLoop()) apply else dispatch(Runnable)` funnel that
 * `EpollEventLoop` / `KqueueEventLoop` / `NioEventLoop` install on the
 * POSIX engines (see `IoEngine` KDoc for the cross-engine contract).
 *
 * Pipeline handlers execute synchronously on the queue thread.
 * [close] is safe to invoke from any thread — it fires
 * `dispatch_async` onto [connQueue] and returns immediately; the
 * actual teardown runs serialised with in-flight read / write
 * callbacks, so the single-thread-invariant state mutations never
 * race. Callback entry points fail fast via [assertOnConnQueue] if
 * they are ever invoked off-queue, mirroring `assertInEventLoop` on
 * the POSIX engines.
 *
 * **Per-connection allocator confinement**: each transport owns a
 * private `BufferAllocator.createChild()` instance off the engine's
 * shared allocator, rather than sharing the engine's child directly.
 * `PooledAllocator`'s per-child cache bookkeeping is EL-pinned-for-writes
 * by contract — the hot path (`allocate` / `returnToPool`) mutates plain
 * `cachedCount[idx]++` and plain `@Volatile Long` `++` cumulative counters
 * assuming a single writer. NWConnection serial dispatch queues are confined
 * per connection but run on a *shared* GCD worker thread pool, so two
 * connections that happen to land on different workers would mutate one
 * shared child's per-class counters concurrently under TLS / large-payload
 * workloads. Carrying a per-transport child means every allocate/release for
 * one connection lands on one connQueue (and therefore one underlying GCD
 * worker at a time), recovering the single-writer invariant. This mirrors the
 * existing `HttpHeadersPool` per-connection-queue scoping installed in [init]
 * for the same family of cross-worker aliasing bugs.
 *
 * The chunk back-end (`ChunkArena` carve / run-return / reclaim) is now
 * thread-safe in its own right (guarded by an `ArenaLock`), so the earlier
 * `IllegalStateException: no subpage at run offset N` subpage-corruption mode
 * no longer depends on this confinement. The per-transport child is retained
 * for the per-child *cache counter* invariant above, which is not yet
 * thread-safe; once that bookkeeping is hardened (or moved behind a per-thread
 * cache front), this confinement can be revisited.
 */
@OptIn(ExperimentalForeignApi::class)
internal class NwIoTransport(
    private val conn: nw_connection_t,
    private val connQueue: dispatch_queue_t,
    parentAllocator: BufferAllocator,
    private val idleReadPolicy: IdleReadPolicy,
    private val logger: Logger,
    idleTimeoutMillis: Long = 0,
    private val flushCoalescing: Boolean = true,
    // Per-connection allocator child — see KDoc paragraph "Per-connection
    // allocator confinement" below. Uses createUntrackedChild() so the engine
    // (parent) allocator does NOT retain and cascade-close it: this connection
    // owns its allocator's close (via teardownOnConnQueue), and the engine joins
    // that teardown at close() rather than fanning out to it. Registering it
    // (createChild) would let the engine's children fan-out close it a second
    // time, racing this connection's own async GCD teardown — the SIGSEGV this
    // engine hit under CPU-constrained teardown. The call must run before the
    // AbstractIoTransport super constructor so allocator is set up before any
    // callback can fire.
) : AbstractIoTransport(
    parentAllocator.createUntrackedChild().also { it.installConfinement(NwQueueConfinement(connQueue)) },
) {

    /** Read/write idle (no-progress) timeout for this connection; see [AbstractIoTransport]. */
    override val idleTimeoutMillis: Long = idleTimeoutMillis

    /**
     * Backed by [connQueue] via [NwEventLoopTimer]. The timer fires in FIFO order
     * with this connection's read / write completion callbacks on the same serial
     * queue, so no cross-thread hand-off is needed to close an idle connection.
     */
    override val eventLoopTimer: EventLoopTimer = NwEventLoopTimer(connQueue)

    /**
     * [IdleReadPolicy.DETECT_PEER_CLOSE]: arm an NWConnection receive
     * here so peer FIN surfaces through [onReadClosed] even when
     * `readEnabled = false` for the entire connection lifetime. Arming
     * runs *after* `AbstractPipelinedChannel.init` has wired up
     * [onRead] / [onReadClosed], so the first receive completion
     * always observes non-null callbacks; arming earlier in `init { }`
     * races with the channel-construction sequence and can leak bytes
     * through a still-null [onRead] when [connQueue] dispatches the
     * receive completion before `AbstractPipelinedChannel.init` finishes.
     */
    override fun onChannelAttached() {
        if (idleReadPolicy == IdleReadPolicy.DETECT_PEER_CLOSE) {
            armRead()
        }
    }

    /**
     * Coroutine dispatcher for this transport. Points at [connQueue]
     * (the per-connection serial dispatch queue) rather than
     * `Dispatchers.Default` so that coroutine-side `withContext` hops
     * (e.g. `PipelinedChannel.read`) land on the same thread that
     * NWConnection uses for its read / write completion callbacks.
     *
     * This aligns the engine with the I/O ownership invariant — every
     * callback + coroutine resumption for this connection runs on a
     * single per-connection serial dispatch queue in FIFO order, the
     * upstream-delegated counterpart of the explicit
     * `if (inEventLoop()) apply else dispatch(Runnable)` funnel that
     * the POSIX engines (epoll / kqueue / nio) use. See
     * [NwConnectionQueueDispatcher] KDoc for the original race that
     * motivated wiring `ioDispatcher` at [connQueue] in the first
     * place, and `IoEngine` interface KDoc for the cross-engine
     * contract.
     */
    private val connQueueDispatcher = NwConnectionQueueDispatcher(connQueue)
    override val ioDispatcher: CoroutineDispatcher = connQueueDispatcher

    override val inOwningContext: Boolean get() = connQueueDispatcher.inConnectionQueue

    init {
        // Per-connection-queue header-pool fix: install a per-connection-queue scoped
        // `HttpHeadersPool` stack on [connQueue]. Without this, the
        // default `@ThreadLocal nativeStack` is shared by every
        // connection whose blocks happen to land on the same GCD worker
        // pthread, and a borrow on one connection can return an
        // `HttpHeaders` previously released by another. The aliasing
        // crashes when one connection's `HttpHeaders.resetForReuse`
        // races another's `HttpHeaders.contains` lookup (the
        // `bufFor(i)` → `extras[idx - 1]` path returns null mid-clear
        // and the subsequent virtual call on a null `IoBuf` faults at
        // 0x0). Confirmed by a 30-run baseline showing ~10% SIGSEGV /
        // SIGABRT under `server-http × nwconnection × openssl × /hello`
        // while a `HttpHeadersPool.bypassPool = true` run on the same
        // load yields 0 crashes (`KEEL_BENCH_HTTP_HEADERS_POOL_BYPASS=1`).
        //
        // The install is idempotent per queue — calling it for a queue
        // that has already received a scoped pool replaces the previous
        // `StableRef` via the destructor (a no-op in practice because
        // every `NwIoTransport` constructs its own queue, but the call
        // is safe regardless).
        installScopedHeadersPool(connQueue)
    }

    /**
     * Fails fast if the caller is not currently executing on
     * [connQueue]. Wraps [NwConnectionQueueDispatcher.assertInConnectionQueue]
     * so callback paths can declare the invariant inline without
     * leaking the dispatcher cast.
     */
    private fun assertOnConnQueue(operation: String) =
        connQueueDispatcher.assertInConnectionQueue(operation)

    // --- Read path ---

    override var readEnabled: Boolean = false
        set(value) {
            field = value
            // [IdleReadPolicy.DETECT_PEER_CLOSE]: receive is already
            // armed from construction and stays armed for the lifetime
            // of the transport — flipping `readEnabled` only controls
            // whether [onReceiveCompletion] delivers bytes through [onRead]
            // or releases them silently.
            if (value && opened) {
                // The connection is now waiting to read → the read-side idle timeout
                // applies (covers accept-to-first-byte, slowloris-silent, keep-alive
                // idle); policy-independent.
                armIdleTimeout()
                if (idleReadPolicy == IdleReadPolicy.PRESERVE_BACKPRESSURE) armRead()
            } else if (!value) {
                cancelIdleTimeout() // back-pressure: pause the read-idle timeout
            }
        }

    // Tracks the pending read buffer so close() can release it if the
    // async callback hasn't fired yet. Set in armRead(), cleared in
    // onReceiveCompletion(). Volatile: armRead() and close() may run on
    // different threads (dispatch queue vs coroutine thread).
    @kotlin.concurrent.Volatile
    private var pendingReadBuf: IoBuf? = null

    /**
     * Recycled fallback buffer for the multi-region copy path.
     *
     * The zero-copy fast path does NOT use this buffer — it wraps the
     * framework-managed memory as an IoBuf directly. But the C wrapper
     * needs a destination for the multi-region branch, so a buffer is
     * always supplied to `keel_nw_read_async`. Caching it across
     * receives avoids per-iteration allocator pool churn on the
     * zero-copy hot path (the buffer is allocated once on first arm
     * and reused until the multi-region branch claims it).
     *
     * Lifecycle: allocated on demand in [armRead] when null; reused
     * across all-zero-copy receives; consumed (handed to onRead and
     * not reclaimed) when the multi-region branch fires.
     */
    @kotlin.concurrent.Volatile
    private var spareFallbackBuf: IoBuf? = null

    /**
     * Starts the async read loop via [keel_nw_read_async].
     *
     * Allocates (or reuses) a fallback buffer, creates a StableRef
     * with a [ReadContext], and passes it to the C wrapper. The read
     * callback re-arms automatically on each successful read.
     */
    // Flow-control pause ([pauseReads]): when set, [armRead] becomes a
    // no-op, so the receive loop stops re-arming after the in-flight
    // receive (at most one delivery of overshoot) and the NWConnection
    // framework receive buffer retains further bytes — TCP back-pressure
    // reaches the peer regardless of [idleReadPolicy]. Connection-queue
    // confined like the rest of the read bookkeeping.
    private var readPaused = false

    override fun pauseReads() {
        readPaused = true
    }

    override fun resumeReads() {
        readPaused = false
        // Restore the policy's steady state: DETECT keeps a receive armed
        // at all times; PRESERVE arms only while reads are enabled.
        if (opened && (idleReadPolicy == IdleReadPolicy.DETECT_PEER_CLOSE || readEnabled)) {
            armRead()
        }
    }

    private fun armRead() {
        if (readPaused) return
        if (!opened) return
        if (pendingReadBuf != null) return
        val buf = spareFallbackBuf
            ?.also { spareFallbackBuf = null }
            ?: allocator.allocate(IoTransport.DEFAULT_READ_BUFFER_SIZE)
        pendingReadBuf = buf
        val ptr = checkNotNull(buf.unsafePointer + buf.writerIndex) {
            "buf.unsafePointer + writerIndex returned null; IoBuf pointer must be valid"
        }
        val ref = StableRef.create(ReadContext(this, buf))
        keel_nw_read_async(conn, ptr, buf.writableBytes.toUInt(), readCallback, ref.asCPointer())
    }

    /**
     * Receive completion handler invoked from the dispatch queue via the
     * C wrapper's read callback.
     *
     * The raw callback parameters are classified into [NwReceiveOutcome]
     * at the C-callback boundary so this method consumes a single
     * fully-typed value:
     *
     * - [NwReceiveOutcome.ZeroCopy] — single-region zero-copy. The
     *   region pointer is valid until
     *   `keel_nw_dispatch_data_release` is called on `handle`. The
     *   pre-allocated [fallbackBuf] is unused and is recycled via
     *   [spareFallbackBuf]; the framework memory is wrapped as a
     *   [DispatchDataIoBuf] (engine-direct, 1 allocation per receive)
     *   whose [DispatchDataIoBuf.release] releases `handle` at
     *   refcount zero.
     * - [NwReceiveOutcome.Copied] — multi-region copy. Bytes have been
     *   memcpy'd into [fallbackBuf] by the C wrapper; the same buffer
     *   is delivered to [onRead] as before zero-copy support existed.
     * - [NwReceiveOutcome.Closed] — failed or EOF. Fallback buffer is
     *   released and [onReadClosed] is invoked.
     * - [NwReceiveOutcome.Spurious] — 0 bytes, not complete. Fallback
     *   is released and the read is re-armed without delivery.
     */
    internal fun onReceiveCompletion(fallbackBuf: IoBuf, outcome: NwReceiveOutcome) {
        assertOnConnQueue("NwIoTransport.onReceiveCompletion")
        pendingReadBuf = null
        if (!opened) {
            fallbackBuf.release()
            if (outcome is NwReceiveOutcome.ZeroCopy) {
                keel_nw_dispatch_data_release(outcome.handle)
            }
            return
        }
        // Any successful receive (zero-copy or copied) is read progress — refresh
        // the read-idle deadline before delivery. A no-op when not armed.
        if (outcome is NwReceiveOutcome.ZeroCopy || outcome is NwReceiveOutcome.Copied) {
            touchIdleTimeout()
        }
        when (outcome) {
            is NwReceiveOutcome.ZeroCopy -> {
                // Zero-copy single-region path: the framework-managed
                // memory is wrapped as an engine-direct IoBuf; the
                // pre-allocated fallback buffer was unused — recycle
                // it via [spareFallbackBuf] so the next armRead skips
                // a pool allocate+release pair.
                fallbackBuf.clear()
                spareFallbackBuf = fallbackBuf
                // Forward the per-engine allocator's lifecycle listener so
                // this engine-direct inbound IoBuf fires onAllocated /
                // onReleased through the same channel as the allocator-
                // produced fallback path (pluggability item 12 B2.5 step
                // 3). The listener flows from the user-passed
                // config.allocator.lifecycleListener through createChild
                // into the per-engine allocator the transport holds.
                val zcBuf = DispatchDataIoBuf.wrapInbound(
                    outcome.ptr,
                    outcome.bytesRead,
                    outcome.handle,
                    allocator.lifecycleListener,
                )
                // Same delivery semantics as the copy path. See the
                // KDoc above on idle-read policies for how this
                // interacts with `readEnabled`.
                onRead?.invoke(zcBuf)
                // One receive completion is one batch.
                armRead()
            }
            is NwReceiveOutcome.Copied -> {
                // Multi-region copy path: bytes already memcpy'd into
                // fallbackBuf by the C wrapper. Identical to the
                // pre-zero-copy implementation.
                fallbackBuf.writerIndex += outcome.bytesRead
                onRead?.invoke(fallbackBuf)
                armRead()
            }
            is NwReceiveOutcome.Closed -> {
                // A real receive failure (errno != 0, e.g. ECONNRESET) is
                // logged; a clean EOF (errno == 0) closes silently.
                if (outcome.errno != 0) {
                    logger.warn { "NWConnection receive failed (errno=${outcome.errno}); closing connection" }
                }
                fallbackBuf.release()
                onReadClosed?.invoke()
            }
            NwReceiveOutcome.Spurious -> {
                fallbackBuf.release()
                armRead()
            }
        }
    }

    // --- Lifecycle ---

    /**
     * Sends TCP FIN to the peer via NWConnection, on [connQueue] like [close]
     * does.
     *
     * The half-close inspects [pendingWrites] and [writeInFlight] to decide
     * whether the FIN has to wait for buffered output; both are
     * connQueue-confined, as is the `outputShutdown` guard.
     *
     * Idempotent, and safe to call from any thread. The FIN is sent
     * asynchronously, and after any buffered writes have been sent.
     */
    override fun shutdownOutput() {
        dispatch_async(connQueue) {
            shutdownOutputOwned()
        }
    }

    /**
     * [performFlush] moves the batch out of [pendingWrites] before handing it
     * to `nw_connection_send`, so an empty queue with [writeInFlight] still
     * set means the bytes are outstanding, not delivered.
     *
     * [writeInFlight] stays a flag rather than a count even though
     * `flushCoalescing = false` lets two sends overlap: `keel_nw_shutdown_output`
     * is itself an `nw_connection_send` with `NW_CONNECTION_FINAL_MESSAGE_CONTEXT`,
     * and NWConnection orders sends on the connection — so a FIN issued while a
     * second send is outstanding still lands behind it. The wait exists for
     * keel's own queue, which the framework knows nothing about.
     */
    override val outputDrained: Boolean
        get() = pendingWrites.isEmpty() && !writeInFlight

    override fun sendFin() {
        keel_nw_shutdown_output(conn)
    }

    // --- Write path ---

    /**
     * Deferred completed by [flushCallback] when the NWConnection write
     * callback fires. Set in [flush] before dispatching the write;
     * cleared in [awaitPendingFlush] after the await returns.
     *
     * Touched only from connQueue (flush runs on connQueue, the callback
     * fires on connQueue, and awaitPendingFlush suspends on connQueue),
     * so no volatile/atomic is required.
     */
    private var pendingFlushCompletion: CompletableDeferred<Unit>? = null

    /**
     * True while an `nw_connection_send` initiated by [performFlush] has not yet
     * fired its completion callback. Used by [flush] to coalesce back-to-back
     * `requestFlush` calls that arrive while a previous send is in flight:
     * their bytes accumulate in [pendingWrites] and are drained together by
     * [drainInFlightCompletion] when the in-flight callback fires. The first
     * flush of any response bypasses this and dispatches immediately, so
     * single-shot responses (`/hello`, `/large`) pay no extra latency, while
     * high-frequency flushes (SSE / chunked streaming) collapse per-frame
     * `nw_connection_send` calls into gathered writev sends.
     *
     * Touched only from connQueue (same invariant as [pendingFlushCompletion]).
     */
    private var writeInFlight: Boolean = false

    /**
     * Suspends until every currently-outstanding `nw_connection_send` initiated
     * by [flush] has fired its completion callback.
     *
     * Ordinarily this awaits one completion and returns. When [drainInFlightCompletion]
     * chains another send (bytes accumulated in [pendingWrites] while the previous
     * send was in flight), it swaps [pendingFlushCompletion] to a new deferred
     * before signalling the old one; the loop below observes the swap and awaits
     * the new completion too, so the caller never resumes with data still in flight.
     */
    override suspend fun awaitPendingFlush() {
        while (true) {
            val current = pendingFlushCompletion ?: return
            current.await()
            if (pendingFlushCompletion === current) {
                pendingFlushCompletion = null
                return
            }
            // drainInFlightCompletion queued another send; wait for it too.
        }
    }

    /**
     * Sends pending writes via NWConnection.
     *
     * NWConnection's `nw_connection_send` accepts data without EAGAIN — flow
     * control is handled internally by the framework. Each send incurs a GCD
     * dispatch onto the connection queue, so per-frame flushing on chunked
     * streaming (SSE) turns into N × `nw_connection_send`, which caps
     * throughput. To collapse those without breaking the per-frame streaming
     * semantic, [flush] only initiates a send when no other send is in
     * flight; concurrent `requestFlush` calls accumulate in [pendingWrites]
     * and are drained by [drainInFlightCompletion] when the in-flight
     * callback fires.
     *
     * @return always `false` because NWConnection writes are asynchronous.
     */
    override fun flush(): Boolean {
        if (pendingWrites.isEmpty()) return true
        // Opt-out: skip the in-flight coalescing when the engine config disables
        // it. Every flush() issues its own nw_connection_send immediately —
        // matches the pre-#894 behaviour for latency-sensitive workloads.
        if (flushCoalescing) {
            // Coalesce with the outstanding send — its completion callback will
            // pick up whatever we accumulate here via drainInFlightCompletion.
            if (writeInFlight) return false
        }

        val completion = CompletableDeferred<Unit>()
        pendingFlushCompletion = completion
        writeInFlight = true
        performFlush(completion)
        return false
    }

    /**
     * Drains one batch of [pendingWrites] via `nw_connection_send`.
     *
     * The caller MUST set [writeInFlight] before calling; [drainInFlightCompletion]
     * clears it and re-drives another [performFlush] if more writes have
     * accumulated in the meantime.
     */
    private fun performFlush(completion: CompletableDeferred<Unit>) {
        val writes = ArrayList(pendingWrites)
        pendingWrites.clear()
        val totalBytes = writes.sumOf { it.length }
        val transport = this

        if (writes.size == 1) {
            val pw = writes[0]
            val ptr = checkNotNull(pw.buf.unsafePointer + pw.offset) {
                "buf.unsafePointer + offset returned null; IoBuf pointer must be valid"
            }
            val ref = StableRef.create(
                FlushContext(
                    writes,
                    totalBytes,
                    onFlushComplete,
                    completion,
                    logger,
                    { delta -> transport.updatePendingBytes(delta) },
                    { transport.drainInFlightCompletion() },
                ),
            )
            keel_nw_write_async(conn, ptr, pw.length.toUInt(), flushCallback, ref.asCPointer())
        } else {
            memScoped {
                val bufs = allocArray<CPointerVar<ByteVar>>(writes.size)
                val lens = allocArray<UIntVar>(writes.size)
                for (i in writes.indices) {
                    val p = checkNotNull(writes[i].buf.unsafePointer + writes[i].offset) {
                        "buf.unsafePointer + offset returned null at index $i; IoBuf pointer must be valid"
                    }
                    bufs[i] = p.reinterpret()
                    lens[i] = writes[i].length.toUInt()
                }
                val ref = StableRef.create(
                    FlushContext(
                        writes,
                        totalBytes,
                        onFlushComplete,
                        completion,
                        logger,
                        { delta -> transport.updatePendingBytes(delta) },
                        { transport.drainInFlightCompletion() },
                    ),
                )
                keel_nw_writev_async(conn, bufs.reinterpret(), lens, writes.size, flushCallback, ref.asCPointer())
            }
        }
        // Arm the write-idle timer here (the send is outstanding until its
        // callback drains it). NWConnection applies peer flow control to that
        // callback (delayed while the peer's receive window is full), so this
        // is where a slow-read peer that never drains the response gets
        // detected. The completion's updatePendingBytes refreshes the timer on
        // partial drain and cancels it once pendingBytes reaches 0.
        armWriteIdleTimeout()
    }

    /**
     * Invoked from [flushCallback] after the in-flight send completes. Clears
     * [writeInFlight] and, if [pendingWrites] accumulated more bytes while
     * the send was outstanding, kicks off another [performFlush] so the
     * coalesced batch drains without waiting for the next caller-driven
     * `requestFlush`.
     */
    private fun drainInFlightCompletion() {
        writeInFlight = false
        if (pendingWrites.isEmpty()) {
            sendFinIfDrained()
            return
        }
        val nextCompletion = CompletableDeferred<Unit>()
        pendingFlushCompletion = nextCompletion
        writeInFlight = true
        performFlush(nextCompletion)
    }

    /**
     * Cancels the NWConnection and releases pending write buffers.
     *
     * The pending read buffer (if any) is released by the async read
     * callback via [onReceiveCompletion] when it detects [opened] is false.
     * Use [awaitClosed] to wait for the callback to complete.
     *
     * Idempotent and thread-safe. The teardown is dispatched onto
     * [connQueue], the same per-connection serial queue that every
     * NWConnection callback (read / write completion) runs on, so the
     * `opened` flip, pending-write release, and
     * [nw_connection_cancel] observe a total order with any in-flight
     * read / write callback's `pendingWrites` mutations. Concurrent
     * `close()` callers collapse to a single tear-down because the
     * re-check of `opened` inside the dispatched block turns every
     * non-first invocation into a no-op.
     */
    override fun close() {
        if (!markClosing()) return
        dispatch_async(connQueue) {
            teardownOnConnQueue()
        }
    }

    /**
     * Completed once [teardownOnConnQueue] has finished, including the
     * per-connection allocator child's [BufferAllocator.close]. The engine's
     * per-connection tracking coroutine (`NwEngine.trackConnection`) awaits
     * this to join the async GCD teardown at engine close — now that the child
     * is untracked (`createUntrackedChild`), this teardown is the only path
     * that closes it, so the engine must wait for it before the shared arena
     * can be torn down.
     */
    private val teardownComplete = CompletableDeferred<Unit>()

    /** Suspends until this connection's [teardownOnConnQueue] has completed. */
    internal suspend fun awaitTeardown() = teardownComplete.await()

    /** True once [teardownOnConnQueue] has run to completion. */
    internal val isTornDown: Boolean get() = teardownComplete.isCompleted

    private fun teardownOnConnQueue() {
        assertOnConnQueue("NwIoTransport.teardownOnConnQueue")
        if (!markTeardownStarted()) return
        cancelIdleTimeout()
        cancelWriteIdleTimeout()
        for (pw in pendingWrites) pw.buf.release()
        pendingWrites.clear()
        pendingBytes = 0
        spareFallbackBuf?.release()
        spareFallbackBuf = null
        nw_connection_cancel(conn)
        // Drain the per-connection allocator child's pool. The child is an
        // untracked child of the engine allocator (createUntrackedChild), so
        // this is its *only* close path — the engine does not fan out to it.
        // Its chunks return to the shared arena immediately instead of waiting
        // for the engine-level close.
        allocator.close()
        // Signal teardown completion so the engine's tracking coroutine can
        // join this async teardown at close(), guaranteeing the untracked
        // child is drained before the shared arena is torn down.
        teardownComplete.complete(Unit)
    }

    /**
     * Suspends until the pending async read callback has completed.
     *
     * After [close], NWConnection delivers the pending read callback
     * with an error on the dispatch queue. This method polls until
     * [pendingReadBuf] is cleared by [onReceiveCompletion], ensuring all
     * buffers are released before the caller checks for leaks.
     *
     * Times out after [AWAIT_CLOSED_TIMEOUT_MS] as a safety net against
     * dispatch queue deadlock or NWConnection callback not firing.
     */
    override suspend fun awaitClosed() {
        var elapsed = 0L
        while (pendingReadBuf != null) {
            if (elapsed >= AWAIT_CLOSED_TIMEOUT_MS) return
            delay(AWAIT_CLOSED_POLL_MS)
            elapsed += AWAIT_CLOSED_POLL_MS
        }
    }

    private class ReadContext(val transport: NwIoTransport, val buf: IoBuf)

    private class FlushContext(
        val writes: List<PendingWrite>,
        val totalBytes: Int,
        val onComplete: (() -> Unit)?,
        val completion: CompletableDeferred<Unit>,
        val logger: Logger,
        val onPendingBytesUpdate: (Int) -> Unit,
        val onDrainCheck: () -> Unit,
    )

    companion object {
        private const val AWAIT_CLOSED_POLL_MS = 10L

        /** Safety timeout for awaitClosed() to prevent infinite loop if dispatch callback never fires. */
        private const val AWAIT_CLOSED_TIMEOUT_MS = 5000L

        private val readCallback = staticCFunction {
                zcHandle: COpaquePointer?,
                zcPtr: COpaquePointer?,
                len: UInt,
                isComplete: Int,
                error: Int,
                ctx: COpaquePointer?,
            ->
            val ref = checkNotNull(ctx) { "read callback ctx is null" }.asStableRef<ReadContext>()
            val readCtx = ref.get()
            ref.dispose()
            val outcome = classifyReceive(
                zcHandle = zcHandle,
                zcPtr = zcPtr,
                bytesRead = len.toInt(),
                isComplete = isComplete != 0,
                errno = error,
            )
            readCtx.transport.onReceiveCompletion(readCtx.buf, outcome)
        }

        /**
         * Maps the raw `keel_nw_dispatch_received` callback parameters
         * to a fully-typed [NwReceiveOutcome] variant. Performed once
         * at the C-callback boundary so the rest of the engine code
         * gets exhaustive `when` smart-casting instead of correlated
         * nullable bookkeeping.
         *
         * Branch ordering matches the original flat `when` in
         * [onReceiveCompletion] before the sealed refactor: terminal
         * conditions (failed / EOF) first, then zero-copy, then
         * multi-region copy, then spurious 0-byte.
         */
        private fun classifyReceive(
            zcHandle: COpaquePointer?,
            zcPtr: COpaquePointer?,
            bytesRead: Int,
            isComplete: Boolean,
            errno: Int,
        ): NwReceiveOutcome = when {
            // errno != 0 is a real receive failure; errno == 0 with
            // is_complete + 0 bytes is a clean EOF. Closed carries the
            // errno so onReceiveCompletion can log the reason.
            errno != 0 || (bytesRead == 0 && isComplete) -> NwReceiveOutcome.Closed(errno)
            zcHandle != null && bytesRead > 0 -> {
                val ptr = checkNotNull(zcPtr) {
                    "zcPtr must be non-null when zcHandle is non-null (zero-copy single-region contract)"
                }.reinterpret<ByteVar>()
                NwReceiveOutcome.ZeroCopy(zcHandle, ptr, bytesRead)
            }
            bytesRead > 0 -> NwReceiveOutcome.Copied(bytesRead)
            else -> NwReceiveOutcome.Spurious
        }

        private val flushCallback = staticCFunction { error: Int, ctx: kotlinx.cinterop.COpaquePointer? ->
            val ref = checkNotNull(ctx) { "flush callback ctx is null" }.asStableRef<FlushContext>()
            val flushCtx = ref.get()
            ref.dispose()
            // A send failure (e.g. EPIPE / ECONNRESET) was previously
            // discarded here, completing the flush as if it succeeded. Log
            // it so a broken write is no longer silent; the peer-gone read
            // close still drives connection teardown.
            if (error != 0) {
                flushCtx.logger.warn { "NWConnection send failed (errno=$error)" }
            }
            for (pw in flushCtx.writes) pw.buf.release()
            flushCtx.onPendingBytesUpdate(-flushCtx.totalBytes)
            // Resume any awaitPendingFlush() waiter before invoking onComplete
            // so that the waiter can observe a fully-settled write state.
            // Drain any coalesced writes BEFORE resuming any awaitPendingFlush waiter,
            // so the waiter observes writeInFlight=false when it resumes. If we
            // completed first and the awaiter ran inline, it could enqueue a new
            // chunk while writeInFlight was still true — flush() would coalesce it
            // and close() would then cancel it. drainInFlightCompletion may swap
            // pendingFlushCompletion to a new deferred; awaitPendingFlush chains
            // onto that so no send is silently orphaned.
            flushCtx.onDrainCheck()
            flushCtx.completion.complete(Unit)
            flushCtx.onComplete?.invoke()
            Unit
        }
    }
}
