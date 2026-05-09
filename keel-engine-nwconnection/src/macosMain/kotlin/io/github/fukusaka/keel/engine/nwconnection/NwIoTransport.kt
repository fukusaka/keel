package io.github.fukusaka.keel.engine.nwconnection

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.unsafePointer
import io.github.fukusaka.keel.core.IdleReadPolicy
import io.github.fukusaka.keel.pipeline.AbstractIoTransport
import io.github.fukusaka.keel.pipeline.AbstractIoTransport.PendingWrite
import io.github.fukusaka.keel.pipeline.IoTransport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.cinterop.ByteVar
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
 * copies data segment-by-segment via `dispatch_data_apply` + `memcpy` —
 * copy is unavoidable.
 *
 * **Read path**: [readEnabled] arms the async read loop via [keel_nw_read_async].
 * Each read callback allocates a buffer, fills it, and invokes [onRead].
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
 * **Thread model**: all callbacks (read completion, write completion, and
 * the teardown block dispatched from [close]) run on [connQueue], the
 * per-connection serial dispatch queue. Pipeline handlers execute
 * synchronously on that thread. [close] itself is safe to invoke from
 * any thread — it fires a `dispatch_async` onto [connQueue] and returns
 * immediately; the actual teardown runs serialised with in-flight
 * read / write callbacks, so `pendingWrites` / `pendingBytes` mutations
 * never race.
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
     * This aligns the engine with the single-thread contract of
     * `SuspendBridgeHandler` (see `NwConnectionQueueDispatcher` KDoc
     * for the race this fixes).
     */
    override val ioDispatcher: CoroutineDispatcher = NwConnectionQueueDispatcher(connQueue)

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
     * Starts the async read loop via [keel_nw_read_async].
     *
     * Allocates a buffer, creates a StableRef with a [ReadContext], and
     * passes it to the C wrapper. The read callback re-arms automatically
     * on each successful read.
     */
    private fun armRead() {
        if (!opened) return
        if (pendingReadBuf != null) return
        val buf = allocator.allocate(IoTransport.DEFAULT_READ_BUFFER_SIZE)
        pendingReadBuf = buf
        val ptr = (buf.unsafePointer + buf.writerIndex)!!
        val ref = StableRef.create(ReadContext(this, buf))
        keel_nw_read_async(conn, ptr, buf.writableBytes.toUInt(), readCallback, ref.asCPointer())
    }

    internal fun onReadComplete(buf: IoBuf, bytesRead: Int, isComplete: Boolean, failed: Boolean) {
        pendingReadBuf = null
        if (!opened) {
            buf.release()
            return
        }
        when {
            failed || (bytesRead == 0 && isComplete) -> {
                buf.release()
                onReadClosed?.invoke()
            }
            bytesRead > 0 -> {
                buf.writerIndex += bytesRead
                // Always deliver via [onRead] in both modes. In
                // [IdleReadPolicy.PRESERVE_BACKPRESSURE] this branch is
                // only reachable when the receive is armed (which only
                // happens after `readEnabled = true`). In
                // [IdleReadPolicy.DETECT_PEER_CLOSE] we deliver
                // regardless of `readEnabled`; bytes that arrive while
                // no user [InboundHandler] is installed are absorbed by
                // `DefaultPipeline`'s pre-attach event journal and
                // replayed when the first user handler is added — this
                // trades engine-level data dropping for pipeline-level
                // buffering, closing the data-loss caveat that
                // DETECT_PEER_CLOSE previously documented.
                onRead?.invoke(buf)
                armRead()
            }
            else -> {
                // 0 bytes, not complete — re-arm.
                buf.release()
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
        if (!markTeardownStarted()) return
        for (pw in pendingWrites) pw.buf.release()
        pendingWrites.clear()
        pendingBytes = 0
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
                len: UInt, isComplete: Int, error: Int, ctx: kotlinx.cinterop.COpaquePointer? ->
            val ref = checkNotNull(ctx) { "read callback ctx is null" }.asStableRef<ReadContext>()
            val readCtx = ref.get()
            ref.dispose()
            readCtx.transport.onReadComplete(readCtx.buf, len.toInt(), isComplete != 0, error != 0)
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
