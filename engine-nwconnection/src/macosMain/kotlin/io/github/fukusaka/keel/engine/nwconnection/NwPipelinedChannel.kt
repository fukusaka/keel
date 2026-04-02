package io.github.fukusaka.keel.engine.nwconnection

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.unsafePointer
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.pipeline.ChannelPipeline
import io.github.fukusaka.keel.pipeline.DefaultChannelPipeline
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.plus
import kotlinx.cinterop.staticCFunction
import nwconnection.keel_nw_read_async
import platform.Network.nw_connection_t

/**
 * Pipeline channel for NWConnection-based I/O on macOS.
 *
 * Reads are driven by [keel_nw_read_async] callbacks. Each callback
 * allocates a buffer, receives data via `dispatch_data_apply` + `memcpy`,
 * and feeds it into the [pipeline] via [ChannelPipeline.notifyRead].
 *
 * Unlike kqueue/epoll pipeline channels which use POSIX `read()` directly,
 * NWConnection delivers data as `dispatch_data_t` — copy is unavoidable.
 *
 * **Thread model**: all callbacks run on the per-connection serial dispatch
 * queue. Pipeline handlers execute synchronously on the dispatch queue thread.
 *
 * **StableRef ownership**: [armRead] creates a StableRef pointing to this
 * channel. The read callback disposes and re-creates it on each invocation
 * to re-arm the next read. On close, the closed flag prevents re-arming.
 */
@OptIn(ExperimentalForeignApi::class)
internal class NwPipelinedChannel(
    private val conn: nw_connection_t,
    private val transport: NwIoTransport,
    override val allocator: BufferAllocator,
    logger: Logger,
) : PipelinedChannel {

    override val pipeline: ChannelPipeline = DefaultChannelPipeline(this, transport, logger)
    override val isActive: Boolean get() = !closed
    override val isWritable: Boolean get() = true

    @kotlin.concurrent.Volatile
    private var closed = false

    /**
     * Starts the async read loop via [keel_nw_read_async].
     *
     * Must be called after pipeline initialization. The read callback
     * re-arms automatically on each successful read.
     */
    fun armRead() {
        if (closed) return
        val buf = allocator.allocate(READ_BUFFER_SIZE)
        val ptr = (buf.unsafePointer + buf.writerIndex)!!
        val ref = StableRef.create(ReadContext(this, buf))
        keel_nw_read_async(conn, ptr, buf.writableBytes.toUInt(), readCallback, ref.asCPointer())
    }

    internal fun onReadComplete(buf: io.github.fukusaka.keel.buf.IoBuf, bytesRead: Int, isComplete: Boolean, failed: Boolean) {
        if (closed) {
            buf.release()
            return
        }
        when {
            failed || (bytesRead == 0 && isComplete) -> {
                buf.release()
                pipeline.notifyInactive()
                close()
            }
            bytesRead > 0 -> {
                buf.writerIndex += bytesRead
                pipeline.notifyRead(buf)
                armRead()
            }
            else -> {
                // 0 bytes, not complete — re-arm.
                buf.release()
                armRead()
            }
        }
    }

    /**
     * Cancels the NWConnection and releases pending write buffers.
     * Does NOT release the read buffer — the read callback handles it. Idempotent.
     */
    fun close() {
        if (closed) return
        closed = true
        transport.close()
    }

    private class ReadContext(val channel: NwPipelinedChannel, val buf: io.github.fukusaka.keel.buf.IoBuf)

    companion object {
        private const val READ_BUFFER_SIZE = 8192

        private val readCallback = staticCFunction {
                len: UInt, isComplete: Int, error: Int, ctx: kotlinx.cinterop.COpaquePointer? ->
            val ref = checkNotNull(ctx) { "read callback ctx is null" }.asStableRef<ReadContext>()
            val readCtx = ref.get()
            ref.dispose()
            readCtx.channel.onReadComplete(readCtx.buf, len.toInt(), isComplete != 0, error != 0)
        }
    }
}
