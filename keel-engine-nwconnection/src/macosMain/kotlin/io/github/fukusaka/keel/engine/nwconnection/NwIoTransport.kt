package io.github.fukusaka.keel.engine.nwconnection

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.unsafePointer
import io.github.fukusaka.keel.pipeline.AbstractIoTransport
import io.github.fukusaka.keel.pipeline.AbstractIoTransport.PendingWrite
import io.github.fukusaka.keel.pipeline.IoTransport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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
import nwconnection.keel_nw_read_async
import nwconnection.keel_nw_shutdown_output
import nwconnection.keel_nw_write_async
import nwconnection.keel_nw_writev_async
import platform.Network.nw_connection_cancel
import platform.Network.nw_connection_t

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
 * **Thread model**: all callbacks run on the per-connection serial dispatch
 * queue. Pipeline handlers execute synchronously on the dispatch queue thread.
 */
@OptIn(ExperimentalForeignApi::class)
internal class NwIoTransport(
    private val conn: nw_connection_t,
    allocator: BufferAllocator,
) : AbstractIoTransport(allocator) {

    override val ioDispatcher: CoroutineDispatcher get() = Dispatchers.Default

    // --- Read path ---

    override var readEnabled: Boolean = false
        set(value) {
            field = value
            if (value && opened) armRead()
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
     * Sends all pending writes via NWConnection.
     *
     * NWConnection's `nw_connection_send` accepts data without EAGAIN —
     * flow control is handled internally by the framework. The write callback
     * releases buffers and invokes [onFlushComplete].
     *
     * @return always `false` because NWConnection writes are asynchronous.
     */
    override fun flush(): Boolean {
        if (pendingWrites.isEmpty()) return true

        // Transfer ownership to FlushContext for release in callback.
        val writes = ArrayList(pendingWrites)
        pendingWrites.clear()
        val totalBytes = writes.sumOf { it.length }
        val transport = this

        if (writes.size == 1) {
            val pw = writes[0]
            val ptr = (pw.buf.unsafePointer + pw.offset)!!
            val ref = StableRef.create(FlushContext(writes, totalBytes, onFlushComplete) { delta ->
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
                val ref = StableRef.create(FlushContext(writes, totalBytes, onFlushComplete) { delta ->
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
     * Idempotent: subsequent calls are no-ops.
     */
    override fun close() {
        if (!opened) return
        opened = false
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
            for (pw in flushCtx.writes) pw.buf.release()
            flushCtx.onPendingBytesUpdate(-flushCtx.totalBytes)
            flushCtx.onComplete?.invoke()
            ref.dispose()
        }
    }
}
