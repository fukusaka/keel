package io.github.fukusaka.keel.engine.nwconnection

import io.github.fukusaka.keel.io.BufferAllocator
import io.github.fukusaka.keel.core.Channel
import io.github.fukusaka.keel.io.NativeBuf
import io.github.fukusaka.keel.io.unsafePointer
import io.github.fukusaka.keel.core.SocketAddress
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
import kotlinx.coroutines.suspendCancellableCoroutine
import nwconnection.keel_nw_read_async
import nwconnection.keel_nw_shutdown_output
import nwconnection.keel_nw_write_async
import nwconnection.keel_nw_writev_async
import platform.Network.nw_connection_cancel
import platform.Network.nw_connection_t

/**
 * Snapshot of a buffered write: the [NativeBuf] (retained), the byte offset
 * where readable data starts, and the number of bytes to write.
 *
 * We record offset/length separately because [NativeBuf.readerIndex] is
 * advanced at write() time so the caller can reuse the buffer immediately.
 */
private class PendingWrite(val buf: NativeBuf, val offset: Int, val length: Int)

/**
 * Result of an async NWConnection read operation.
 *
 * Returned from the C callback via [CallbackContext] to the suspended coroutine.
 */
private data class ReadResult(val bytesRead: Int, val isComplete: Boolean, val failed: Boolean)

/**
 * NWConnection-based [Channel] implementation for macOS.
 *
 * **Read path (copy from dispatch_data_t, async)**:
 * Unlike kqueue/epoll which use zero-copy POSIX `read()` directly into
 * [NativeBuf], NWConnection delivers received data as `dispatch_data_t`.
 * The C wrapper [keel_nw_read_async] copies data segment-by-segment via
 * `dispatch_data_apply` + `memcpy`, then invokes a callback that resumes
 * the suspended coroutine. No thread blocking occurs.
 *
 * **Write/flush separation**: [write] retains the [NativeBuf] and records
 * the byte range to send. [flush] concatenates all pending writes into a
 * single `dispatch_data_t` via [keel_nw_writev_async] and sends in one
 * `nw_connection_send` call. The concatenation is zero-copy
 * (`dispatch_data_t` chains internally without `memcpy`).
 *
 * **Coroutine integration**: All I/O operations use [suspendCancellableCoroutine]
 * with [staticCFunction] + [StableRef] + [CallbackContext] to bridge dispatch
 * callbacks to coroutine continuations. [CallbackContext] wraps the continuation
 * with an atomic flag for cancel-safety: [invokeOnCancellation] sets the flag,
 * the C callback checks it before resuming. The [StableRef] is always disposed
 * by the callback, never by cancellation.
 *
 * ```
 * Read path (async, copy):
 *   suspendCancellableCoroutine + keel_nw_read_async(conn, buf, callback, ctx)
 *   dispatch queue: nw_connection_receive --> dispatch_data_apply + memcpy
 *                   --> callback(len, complete, error, ctx)
 *   coroutine: resumed with ReadResult --> advance writerIndex
 *
 * Write path (buffered, async flush):
 *   write(buf)  --> retain buf, record offset/length in PendingWrite
 *   flush()     --> keel_nw_writev_async(bufs[], lens[], count)
 *                   dispatch_data_create_concat x N --> single nw_connection_send
 *                   --> callback(error, ctx) --> resume --> release all bufs
 * ```
 *
 * **Backpressure**: [pendingWrites] has no upper bound. A producer that
 * calls [write] without [flush] can accumulate unbounded memory. This is
 * acceptable for the current HTTP server use case where the ktor-engine
 * layer calls flush() after each response. An application-level write
 * watermark is deferred to Phase 7.
 *
 * **Thread model**: [read] and [write]/[flush] are called from coroutine
 * threads. Dispatch callbacks run on per-connection serial dispatch queues.
 * Cross-thread synchronization is handled by [CallbackContext] (atomic CAS)
 * and [StableRef] (C callback owns disposal). State fields ([_open],
 * [_active], [pendingWrites]) are accessed from the coroutine thread only.
 *
 * @param conn       The NWConnection handle for this channel.
 * @param allocator  Buffer allocator for read operations.
 */
@OptIn(ExperimentalForeignApi::class)
internal class NwChannel(
    private val conn: nw_connection_t,
    override val allocator: BufferAllocator,
    override val remoteAddress: SocketAddress?,
    override val localAddress: SocketAddress?,
) : Channel {

    private val pendingWrites = mutableListOf<PendingWrite>()
    private var _open = true
    private var _active = true
    private var outputShutdown = false

    override val isOpen: Boolean get() = _open
    override val isActive: Boolean get() = _active
    override val supportsDeferredFlush: Boolean get() = true

    /**
     * No-op for NWConnection channels. NWConnection does not provide
     * a close-completion notification that maps to coroutine suspension.
     * Callers should detect close via `read()` returning -1 (EOF).
     */
    override suspend fun awaitClosed() {}

    /**
     * Reads bytes into [buf] via async NWConnection receive + memcpy.
     *
     * Suspends via [suspendCancellableCoroutine] until the C wrapper
     * [keel_nw_read_async] invokes the callback with received data.
     * Data is copied from dispatch_data_t into [buf] segment-by-segment.
     *
     * @return number of bytes read, or -1 on EOF/error.
     */
    override suspend fun read(buf: NativeBuf): Int {
        check(_open) { "Channel is closed" }

        val result = suspendCancellableCoroutine<ReadResult> { cont ->
            val cbCtx = CallbackContext(cont)
            val ref = StableRef.create(cbCtx)
            val ptr = (buf.unsafePointer + buf.writerIndex)!!
            keel_nw_read_async(
                conn, ptr, buf.writableBytes.toUInt(),
                readCallback,
                ref.asCPointer(),
            )
            cont.invokeOnCancellation { cbCtx.markCancelled() }
        }

        if (result.failed) return -1
        if (result.bytesRead > 0) buf.writerIndex += result.bytesRead
        if (result.bytesRead == 0 && result.isComplete) return -1
        return result.bytesRead
    }

    /**
     * Buffers a write by retaining [buf] and recording the current readable range.
     *
     * The caller's [NativeBuf.readerIndex] is advanced immediately so the
     * buffer can be reused or released by the caller. The actual NWConnection
     * send happens on [flush].
     *
     * @return number of bytes buffered.
     */
    override suspend fun write(buf: NativeBuf): Int {
        check(_open) { "Channel is closed" }
        check(!outputShutdown) { "Output already shut down" }
        val bytes = buf.readableBytes
        if (bytes == 0) return 0
        val offset = buf.readerIndex
        buf.retain()
        buf.readerIndex += bytes
        pendingWrites.add(PendingWrite(buf, offset, bytes))
        return bytes
    }

    /**
     * Sends all buffered writes to the network in a single NWConnection send.
     *
     * When there is only one pending write, falls back to [keel_nw_write_async]
     * to avoid the overhead of scatter/gather setup. For multiple pending writes,
     * uses [keel_nw_writev_async] which concatenates all buffers into a single
     * `dispatch_data_t` via `dispatch_data_create_concat` and sends them in one
     * `nw_connection_send` call.
     */
    override suspend fun flush() {
        check(_open) { "Channel is closed" }
        if (pendingWrites.isEmpty()) return
        if (pendingWrites.size == 1) {
            flushSingle(pendingWrites[0])
        } else {
            flushGather()
        }
        for (pw in pendingWrites) {
            pw.buf.release()
        }
        pendingWrites.clear()
    }

    /**
     * Sends a single pending write directly via [keel_nw_write_async].
     * Avoids scatter/gather overhead for the common single-buffer case.
     */
    private suspend fun flushSingle(pw: PendingWrite) {
        val ptr = (pw.buf.unsafePointer + pw.offset)!!
        suspendCancellableCoroutine<Int> { cont ->
            val cbCtx = CallbackContext(cont)
            val ref = StableRef.create(cbCtx)
            keel_nw_write_async(
                conn, ptr, pw.length.toUInt(),
                writeCallback,
                ref.asCPointer(),
            )
            cont.invokeOnCancellation { cbCtx.markCancelled() }
        }
    }

    /**
     * Sends multiple pending writes as a single batch via [keel_nw_writev_async].
     *
     * Builds C arrays of buffer pointers and lengths in [memScoped] stack memory,
     * then calls the C wrapper which concatenates them into one `dispatch_data_t`.
     */
    private suspend fun flushGather() {
        val count = pendingWrites.size
        memScoped {
            val bufs = allocArray<CPointerVar<ByteVar>>(count)
            val lens = allocArray<UIntVar>(count)
            for (i in 0 until count) {
                val pw = pendingWrites[i]
                bufs[i] = (pw.buf.unsafePointer + pw.offset)!!.reinterpret()
                lens[i] = pw.length.toUInt()
            }
            suspendCancellableCoroutine<Int> { cont ->
                val cbCtx = CallbackContext(cont)
                val ref = StableRef.create(cbCtx)
                keel_nw_writev_async(
                    conn, bufs.reinterpret(), lens, count,
                    writeCallback,
                    ref.asCPointer(),
                )
                cont.invokeOnCancellation { cbCtx.markCancelled() }
            }
        }
    }

    /**
     * Sends TCP FIN to the peer via NWConnection.
     * Fire-and-forget: no blocking or suspend needed because the caller
     * does not depend on shutdown completion.
     */
    override fun shutdownOutput() {
        if (!outputShutdown && _open) {
            outputShutdown = true
            keel_nw_shutdown_output(conn)
        }
    }

    /**
     * Cancels the NWConnection and releases all pending writes.
     * Unflushed data is discarded (buffers are released without sending).
     */
    override fun close() {
        if (_open) {
            _open = false
            _active = false
            for (pw in pendingWrites) {
                pw.buf.release()
            }
            pendingWrites.clear()
            nw_connection_cancel(conn)
        }
    }

    companion object {
        /**
         * C callback for [keel_nw_read_async]. Resumes the suspended
         * coroutine with [ReadResult] via [CallbackContext].
         *
         * Must be a top-level staticCFunction because Kotlin/Native
         * cannot capture local state in C function pointers.
         *
         * The [StableRef] is always disposed here — never in
         * [invokeOnCancellation]. If the coroutine was cancelled,
         * [CallbackContext.tryResume] is a no-op (skips resume).
         */
        private val readCallback = staticCFunction {
                len: UInt, isComplete: Int, error: Int, ctx: kotlinx.cinterop.COpaquePointer? ->
            val ref = ctx!!.asStableRef<CallbackContext<ReadResult>>()
            ref.get().tryResume(ReadResult(len.toInt(), isComplete != 0, error != 0))
            ref.dispose()
        }

        /**
         * C callback for [keel_nw_write_async]. Resumes the suspended
         * coroutine with the error code via [CallbackContext].
         *
         * Same [StableRef] ownership as [readCallback]: always disposed here.
         */
        private val writeCallback = staticCFunction {
                error: Int, ctx: kotlinx.cinterop.COpaquePointer? ->
            val ref = ctx!!.asStableRef<CallbackContext<Int>>()
            ref.get().tryResume(error)
            ref.dispose()
        }
    }
}
