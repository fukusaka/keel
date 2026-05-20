@file:OptIn(UnsafeIoBufApi::class)

package io.github.fukusaka.keel.engine.nwconnection

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.UnsafeIoBufApi
import io.github.fukusaka.keel.buf.unsafePointer
import io.github.fukusaka.keel.core.IdleReadPolicy
import io.github.fukusaka.keel.pipeline.AbstractIoTransport
import io.github.fukusaka.keel.pipeline.AbstractIoTransport.PendingWrite
import io.github.fukusaka.keel.pipeline.IoTransport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
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
 */
@OptIn(ExperimentalForeignApi::class)
internal class NwIoTransport(
    private val conn: nw_connection_t,
    private val connQueue: dispatch_queue_t,
    allocator: BufferAllocator,
    private val idleReadPolicy: IdleReadPolicy,
) : AbstractIoTransport(allocator) {

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
            // whether [onReadComplete] delivers bytes through [onRead]
            // or releases them silently.
            if (idleReadPolicy == IdleReadPolicy.PRESERVE_BACKPRESSURE && value && opened) {
                armRead()
            }
        }

    // Tracks the pending read buffer so close() can release it if the
    // async callback hasn't fired yet. Set in armRead(), cleared in
    // onReadComplete(). Volatile: armRead() and close() may run on
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
    private fun armRead() {
        if (!opened) return
        if (pendingReadBuf != null) return
        val buf = spareFallbackBuf
            ?.also { spareFallbackBuf = null }
            ?: allocator.allocate(IoTransport.DEFAULT_READ_BUFFER_SIZE)
        pendingReadBuf = buf
        val ptr = (buf.unsafePointer + buf.writerIndex)!!
        val ref = StableRef.create(ReadContext(this, buf))
        keel_nw_read_async(conn, ptr, buf.writableBytes.toUInt(), readCallback, ref.asCPointer())
    }

    /**
     * Receive completion handler invoked from the dispatch queue via the
     * C wrapper's read callback.
     *
     * Dual-path semantics matching [keel_nw_read_async]'s callback:
     *
     * - `zcHandle != null`: **zero-copy single-region path**. The
     *   region pointer in [zcPtr] is valid until
     *   [keel_nw_dispatch_data_release] is called on [zcHandle]. The
     *   pre-allocated [fallbackBuf] is unused and is recycled via
     *   [spareFallbackBuf]; the framework memory is wrapped as a
     *   [DispatchDataIoBuf] (engine-direct, 1 allocation per
     *   receive) whose [DispatchDataIoBuf.release] releases [zcHandle]
     *   at refcount zero.
     * - `zcHandle == null` and `bytesRead > 0`: **multi-region copy
     *   path**. [bytesRead] bytes have been memcpy'd into [fallbackBuf]
     *   by the C wrapper; it is delivered to [onRead] like before.
     * - All other cases (failed, EOF, spurious 0-byte non-complete):
     *   identical to the copy-only legacy path.
     */
    internal fun onReadComplete(
        fallbackBuf: IoBuf,
        zcHandle: COpaquePointer?,
        zcPtr: CPointer<ByteVar>?,
        bytesRead: Int,
        isComplete: Boolean,
        failed: Boolean,
    ) {
        assertOnConnQueue("NwIoTransport.onReadComplete")
        pendingReadBuf = null
        if (!opened) {
            fallbackBuf.release()
            if (zcHandle != null) keel_nw_dispatch_data_release(zcHandle)
            return
        }
        when {
            failed || (bytesRead == 0 && isComplete) -> {
                fallbackBuf.release()
                if (zcHandle != null) keel_nw_dispatch_data_release(zcHandle)
                onReadClosed?.invoke()
            }
            zcHandle != null && bytesRead > 0 -> {
                // Zero-copy single-region path: the framework-managed
                // memory is wrapped as an engine-direct IoBuf; the
                // pre-allocated fallback buffer was unused — recycle
                // it via [spareFallbackBuf] so the next armRead skips
                // a pool allocate+release pair.
                fallbackBuf.clear()
                spareFallbackBuf = fallbackBuf
                @Suppress("UnsafeCallOnNullableType")
                val zcBuf = DispatchDataIoBuf(zcPtr!!, bytesRead, zcHandle)
                // Same delivery semantics as the copy path. See the
                // KDoc above on idle-read policies for how this
                // interacts with `readEnabled`.
                onRead?.invoke(zcBuf)
                armRead()
            }
            bytesRead > 0 -> {
                // Multi-region copy path: bytes already memcpy'd into
                // fallbackBuf by the C wrapper. Identical to the
                // pre-zero-copy implementation.
                fallbackBuf.writerIndex += bytesRead
                onRead?.invoke(fallbackBuf)
                armRead()
            }
            else -> {
                // 0 bytes, not complete — re-arm.
                fallbackBuf.release()
                armRead()
            }
        }
    }

    // --- Lifecycle ---

    private var outputShutdown = false

    /**
     * Sends TCP FIN to the peer via NWConnection.
     * Fire-and-forget: no blocking or suspend needed.
     */
    override fun shutdownOutput() {
        if (!outputShutdown && opened) {
            outputShutdown = true
            keel_nw_shutdown_output(conn)
        }
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
     * Suspends until the in-flight [keel_nw_write_async] callback fires.
     *
     * Suspending releases connQueue so the write-completion callback
     * (which also runs on connQueue) can execute and complete
     * [pendingFlushCompletion]. Once resumed, the write is confirmed
     * delivered to the network layer and [close] can safely cancel the
     * connection without discarding the data.
     */
    override suspend fun awaitPendingFlush() {
        pendingFlushCompletion?.await()
        pendingFlushCompletion = null
    }

    /**
     * Sends all pending writes via NWConnection.
     *
     * NWConnection's `nw_connection_send` accepts data without EAGAIN —
     * flow control is handled internally by the framework. The write callback
     * releases buffers, completes [pendingFlushCompletion] so that
     * [awaitPendingFlush] can resume, and invokes [onFlushComplete].
     *
     * @return always `false` because NWConnection writes are asynchronous.
     */
    override fun flush(): Boolean {
        if (pendingWrites.isEmpty()) return true

        val completion = CompletableDeferred<Unit>()
        pendingFlushCompletion = completion

        // Transfer ownership to FlushContext for release in callback.
        val writes = ArrayList(pendingWrites)
        pendingWrites.clear()
        val totalBytes = writes.sumOf { it.length }
        val transport = this

        if (writes.size == 1) {
            val pw = writes[0]
            val ptr = (pw.buf.unsafePointer + pw.offset)!!
            val ref = StableRef.create(FlushContext(writes, totalBytes, onFlushComplete, completion) { delta ->
                transport.updatePendingBytes(delta)
            })
            keel_nw_write_async(conn, ptr, pw.length.toUInt(), flushCallback, ref.asCPointer())
        } else {
            memScoped {
                val bufs = allocArray<CPointerVar<ByteVar>>(writes.size)
                val lens = allocArray<UIntVar>(writes.size)
                for (i in writes.indices) {
                    bufs[i] = (writes[i].buf.unsafePointer + writes[i].offset)!!.reinterpret()
                    lens[i] = writes[i].length.toUInt()
                }
                val ref = StableRef.create(FlushContext(writes, totalBytes, onFlushComplete, completion) { delta ->
                    transport.updatePendingBytes(delta)
                })
                keel_nw_writev_async(conn, bufs.reinterpret(), lens, writes.size, flushCallback, ref.asCPointer())
            }
        }
        return false // Always async.
    }

    /**
     * Cancels the NWConnection and releases pending write buffers.
     *
     * The pending read buffer (if any) is released by the async read
     * callback via [onReadComplete] when it detects [opened] is false.
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

    private fun teardownOnConnQueue() {
        assertOnConnQueue("NwIoTransport.teardownOnConnQueue")
        if (!markTeardownStarted()) return
        for (pw in pendingWrites) pw.buf.release()
        pendingWrites.clear()
        pendingBytes = 0
        spareFallbackBuf?.release()
        spareFallbackBuf = null
        nw_connection_cancel(conn)
    }

    /**
     * Suspends until the pending async read callback has completed.
     *
     * After [close], NWConnection delivers the pending read callback
     * with an error on the dispatch queue. This method polls until
     * [pendingReadBuf] is cleared by [onReadComplete], ensuring all
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
        val onPendingBytesUpdate: (Int) -> Unit,
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
                ctx: COpaquePointer? ->
            val ref = checkNotNull(ctx) { "read callback ctx is null" }.asStableRef<ReadContext>()
            val readCtx = ref.get()
            ref.dispose()
            readCtx.transport.onReadComplete(
                fallbackBuf = readCtx.buf,
                zcHandle = zcHandle,
                zcPtr = zcPtr?.reinterpret<ByteVar>(),
                bytesRead = len.toInt(),
                isComplete = isComplete != 0,
                failed = error != 0,
            )
        }

        private val flushCallback = staticCFunction { error: Int, ctx: kotlinx.cinterop.COpaquePointer? ->
            val ref = checkNotNull(ctx) { "flush callback ctx is null" }.asStableRef<FlushContext>()
            val flushCtx = ref.get()
            ref.dispose()
            for (pw in flushCtx.writes) pw.buf.release()
            flushCtx.onPendingBytesUpdate(-flushCtx.totalBytes)
            // Resume any awaitPendingFlush() waiter before invoking onComplete
            // so that the waiter can observe a fully-settled write state.
            flushCtx.completion.complete(Unit)
            flushCtx.onComplete?.invoke() ?: Unit
        }
    }
}
