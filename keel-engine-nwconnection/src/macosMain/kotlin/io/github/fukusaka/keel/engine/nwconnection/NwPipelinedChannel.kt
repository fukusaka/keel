package io.github.fukusaka.keel.engine.nwconnection

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.unsafePointer
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.pipeline.ChannelPipeline
import io.github.fukusaka.keel.pipeline.DefaultChannelPipeline
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.pipeline.PipelinedChannel.Companion.SUSPEND_BRIDGE_NAME
import io.github.fukusaka.keel.pipeline.SuspendBridgeHandler
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.plus
import kotlinx.cinterop.staticCFunction
import kotlinx.coroutines.delay
import nwconnection.keel_nw_read_async
import nwconnection.keel_nw_shutdown_output
import platform.Network.nw_connection_t

/**
 * Unified NWConnection channel supporting both Pipeline mode and Channel mode.
 *
 * **Pipeline mode** (push, zero-suspend): engine feeds data into the pipeline
 * via [armRead] → [ChannelPipeline.notifyRead]. Handlers process data
 * synchronously on the dispatch queue thread. Used by [NwEngine.bindPipeline].
 *
 * **Channel mode** (pull, suspend): a [SuspendBridgeHandler] is installed
 * at the end of the pipeline. App calls suspend [read]/[write]/[flush].
 * Used by [NwEngine.bind] and ktor-engine's [handleConnection].
 *
 * ```
 * Pipeline mode:  HEAD ↔ handlers ↔ TAIL
 * Channel mode:   HEAD ↔ handlers ↔ SuspendBridgeHandler ↔ TAIL
 *                                         ↑
 *                                   App: read() / write() / flush()
 * ```
 *
 * Unlike kqueue/epoll channels which use POSIX `read()` directly into
 * [IoBuf], NWConnection delivers data as `dispatch_data_t`. The C wrapper
 * [keel_nw_read_async] copies data segment-by-segment via
 * `dispatch_data_apply` + `memcpy` — copy is unavoidable.
 *
 * **StableRef ownership**: [armRead] creates a StableRef pointing to a
 * [ReadContext]. The read callback disposes and re-arms on each invocation.
 * On close, the closed flag prevents re-arming.
 *
 * **Thread model**: all callbacks run on the per-connection serial dispatch
 * queue. Pipeline handlers execute synchronously on the dispatch queue thread.
 */
@OptIn(ExperimentalForeignApi::class)
internal class NwPipelinedChannel(
    private val conn: nw_connection_t,
    override val allocator: BufferAllocator,
    override val remoteAddress: SocketAddress?,
    override val localAddress: SocketAddress?,
    logger: Logger,
) : PipelinedChannel {

    private val transport = NwIoTransport(conn)
    override val pipeline: ChannelPipeline = DefaultChannelPipeline(this, transport, logger)
    override val isActive: Boolean get() = !closed
    override val isOpen: Boolean get() = !closed
    override val isWritable: Boolean get() = !closed && transport.isWritable
    override val supportsDeferredFlush: Boolean get() = true

    init {
        transport.onWritabilityChanged = { writable ->
            pipeline.notifyWritabilityChanged(writable)
        }
    }

    @kotlin.concurrent.Volatile
    private var closed = false

    // Tracks the pending read buffer so close() can release it if the
    // async callback hasn't fired yet. Set in armRead(), cleared in
    // onReadComplete(). Volatile: armRead() and close() may run on
    // different threads (dispatch queue vs coroutine thread).
    @kotlin.concurrent.Volatile
    private var pendingReadBuf: IoBuf? = null

    // --- Channel mode: bridge lifecycle ---

    private var bridge: SuspendBridgeHandler? = null
    private var readArmed = false

    /**
     * Lazily installs a [SuspendBridgeHandler] and arms the read loop.
     *
     * First call: creates the bridge handler, adds it to the pipeline,
     * and starts the async NWConnection read loop. Subsequent calls
     * return the existing handler.
     */
    override fun ensureBridge(): SuspendBridgeHandler {
        bridge?.let { return it }
        val handler = SuspendBridgeHandler()
        pipeline.addLast(SUSPEND_BRIDGE_NAME, handler)
        bridge = handler
        if (!readArmed) {
            readArmed = true
            armRead()
        }
        return handler
    }

    override suspend fun awaitFlushComplete() {}

    private var outputShutdown = false

    /**
     * Sends TCP FIN to the peer via NWConnection.
     * Fire-and-forget: no blocking or suspend needed.
     */
    override fun shutdownOutput() {
        if (!outputShutdown && !closed) {
            outputShutdown = true
            keel_nw_shutdown_output(conn)
        }
    }

    // --- Pipeline mode: async read loop ---

    /**
     * Starts the async read loop via [keel_nw_read_async].
     *
     * Must be called after pipeline initialization. The read callback
     * re-arms automatically on each successful read.
     */
    fun armRead() {
        if (closed) return
        val buf = allocator.allocate(READ_BUFFER_SIZE)
        pendingReadBuf = buf
        val ptr = (buf.unsafePointer + buf.writerIndex)!!
        val ref = StableRef.create(ReadContext(this, buf))
        keel_nw_read_async(conn, ptr, buf.writableBytes.toUInt(), readCallback, ref.asCPointer())
    }

    internal fun onReadComplete(buf: IoBuf, bytesRead: Int, isComplete: Boolean, failed: Boolean) {
        pendingReadBuf = null
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
     *
     * The pending read buffer (if any) is released by the async read
     * callback via [onReadComplete] when it detects [closed] is true.
     * Use [awaitClosed] to wait for the callback to complete.
     */
    override fun close() {
        if (closed) return
        closed = true
        transport.close()
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

    private class ReadContext(val channel: NwPipelinedChannel, val buf: IoBuf)

    private companion object {
        private const val READ_BUFFER_SIZE = 8192
        private const val AWAIT_CLOSED_POLL_MS = 10L
        /** Safety timeout for awaitClosed() to prevent infinite loop if dispatch callback never fires. */
        private const val AWAIT_CLOSED_TIMEOUT_MS = 5000L

        private val readCallback = staticCFunction {
                len: UInt, isComplete: Int, error: Int, ctx: kotlinx.cinterop.COpaquePointer? ->
            val ref = checkNotNull(ctx) { "read callback ctx is null" }.asStableRef<ReadContext>()
            val readCtx = ref.get()
            ref.dispose()
            readCtx.channel.onReadComplete(readCtx.buf, len.toInt(), isComplete != 0, error != 0)
        }
    }
}
