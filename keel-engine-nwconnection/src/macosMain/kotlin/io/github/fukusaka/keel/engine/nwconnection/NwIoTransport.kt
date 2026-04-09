package io.github.fukusaka.keel.engine.nwconnection

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.unsafePointer
import io.github.fukusaka.keel.pipeline.IoTransport
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
import nwconnection.keel_nw_write_async
import nwconnection.keel_nw_writev_async
import platform.Network.nw_connection_cancel
import platform.Network.nw_connection_t

/**
 * Non-suspend [IoTransport] for NWConnection pipeline channels.
 *
 * Sends outbound [IoBuf] writes via `nw_connection_send`. Unlike kqueue/epoll
 * transports, NWConnection handles EAGAIN internally — `nw_connection_send`
 * accepts data immediately and delivers completion asynchronously via a
 * dispatch queue callback.
 *
 * **Buffer lifecycle**: `write()` retains the buffer. The write callback
 * releases all pending buffers after the send completes.
 *
 * **Thread model**: `write()` and `flush()` are called on the dispatch queue
 * thread (from pipeline handlers). The write completion callback also runs
 * on the same serial dispatch queue — no cross-thread contention.
 */
@OptIn(ExperimentalForeignApi::class)
internal class NwIoTransport(
    private val conn: nw_connection_t,
) : IoTransport {

    private var pendingWrites = mutableListOf<PendingWrite>()

    override var onFlushComplete: (() -> Unit)? = null

    // --- Write backpressure ---

    private var pendingBytes: Int = 0
    private var _writable: Boolean = true
    override val isWritable: Boolean get() = _writable
    override var onWritabilityChanged: ((Boolean) -> Unit)? = null

    private fun updatePendingBytes(delta: Int) {
        pendingBytes += delta
        if (_writable && pendingBytes >= IoTransport.DEFAULT_HIGH_WATER_MARK) {
            _writable = false
            onWritabilityChanged?.invoke(false)
        } else if (!_writable && pendingBytes < IoTransport.DEFAULT_LOW_WATER_MARK) {
            _writable = true
            onWritabilityChanged?.invoke(true)
        }
    }

    override fun write(buf: IoBuf) {
        val bytes = buf.readableBytes
        if (bytes == 0) return
        val offset = buf.readerIndex
        buf.retain()
        buf.readerIndex += bytes
        pendingWrites.add(PendingWrite(buf, offset, bytes))
        updatePendingBytes(bytes)
    }

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
        // Avoids List copy by swapping the backing list.
        val writes = pendingWrites
        pendingWrites = mutableListOf()
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
     * Idempotent for buffer release; `nw_connection_cancel` is safe to call multiple times.
     */
    override fun close() {
        for (pw in pendingWrites) pw.buf.release()
        pendingWrites.clear()
        pendingBytes = 0
        _writable = true
        nw_connection_cancel(conn)
    }

    internal class PendingWrite(val buf: IoBuf, val offset: Int, val length: Int)

    private class FlushContext(
        val writes: List<PendingWrite>,
        val totalBytes: Int,
        val onComplete: (() -> Unit)?,
        val onPendingBytesUpdate: (Int) -> Unit,
    )

    companion object {
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
