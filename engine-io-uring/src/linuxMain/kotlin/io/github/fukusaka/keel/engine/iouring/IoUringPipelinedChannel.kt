package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.unsafePointer
import io.github.fukusaka.keel.core.PushChannel
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.io.PushSuspendSource
import io.github.fukusaka.keel.io.PushToSuspendSourceAdapter
import io.github.fukusaka.keel.io.SuspendSource
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.pipeline.ChannelPipeline
import io.github.fukusaka.keel.pipeline.DefaultChannelPipeline
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.pipeline.SuspendBridgeHandler
import io_uring.keel_cqe_get_buf_id
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.plus
import kotlinx.coroutines.CoroutineDispatcher
import platform.posix.ENOBUFS
import platform.posix.SHUT_WR
import platform.posix.shutdown

/**
 * Unified io_uring channel supporting both Pipeline mode and Channel mode.
 *
 * **Pipeline mode** (push, zero-suspend): uses multishot recv with provided
 * buffer ring. Data received from the network is delivered directly to the
 * pipeline as [pipeline.notifyRead]. Used by [IoUringEngine.bindPipeline].
 *
 * **Channel mode** (pull, suspend): a [SuspendBridgeHandler] is installed
 * at the end of the pipeline. App calls suspend [read]/[write]/[flush].
 * Used by [IoUringEngine.bind] and [IoUringEngine.connect].
 *
 * ```
 * Pipeline mode:
 *   io_uring CQE (multishot recv)
 *     → RingBufferIoBuf → pipeline.notifyRead(buf)
 *     → handler chain → HeadHandler → IoUringIoTransport
 *
 * Channel mode:
 *   io_uring CQE (multishot recv)
 *     → RingBufferIoBuf → pipeline.notifyRead(buf)
 *     → SuspendBridgeHandler.onRead → queue
 *     → App: suspend channel.read(buf) ← dequeue
 * ```
 *
 * @param fd         The connected socket file descriptor.
 * @param transport  Shared [IoUringIoTransport] for write/flush.
 * @param eventLoop  The owning [IoUringEventLoop].
 * @param bufferRing The [ProvidedBufferRing] for multishot recv.
 * @param allocator  Buffer allocator for this channel.
 * @param logger     Logger for pipeline error reporting.
 * @param capabilities Runtime kernel feature flags.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IoUringPipelinedChannel(
    private val fd: Int,
    private val transport: IoUringIoTransport,
    private val eventLoop: IoUringEventLoop,
    private val bufferRing: ProvidedBufferRing?,
    override val allocator: BufferAllocator,
    private val logger: Logger,
    override val remoteAddress: SocketAddress? = null,
    override val localAddress: SocketAddress? = null,
    private val capabilities: IoUringCapabilities = IoUringCapabilities(),
) : PipelinedChannel, PushChannel {

    override val pipeline: ChannelPipeline = DefaultChannelPipeline(this, transport, logger)
    override val isWritable: Boolean get() = true
    override val isActive: Boolean get() = !closed
    override val isOpen: Boolean get() = !closed
    override val coroutineDispatcher: CoroutineDispatcher get() = eventLoop
    override val supportsDeferredFlush: Boolean get() = true

    private var closed = false

    // --- Pipeline mode: multishot recv with provided buffer ring ---

    // Pre-allocated IoBuf wrappers: one per buffer slot.
    // Reused on each CQE callback via reset() — zero allocation on hot path.
    private val wrappers = bufferRing?.let { ring ->
        Array(ring.bufferCount) { bufId ->
            RingBufferIoBuf(bufId, ring) { buf ->
                ring.returnBuffer(bufId)
            }
        }
    }

    private var multishotSlot = -1

    /**
     * Arms multishot recv and notifies the pipeline that the channel is active.
     *
     * Must be called on the EventLoop thread after the pipeline is initialized.
     */
    fun armRecv() {
        val ring = bufferRing ?: error("armRecv requires provided buffer ring")
        multishotSlot = eventLoop.submitMultishotRecv(
            fd = fd,
            bgid = ring.bgid,
            onCqe = { res, flags ->
                if (closed) return@submitMultishotRecv
                when {
                    res > 0 -> {
                        val bufId = keel_cqe_get_buf_id(flags).toInt()
                        val buf = wrappers!![bufId]
                        buf.reset()
                        buf.writerIndex = res
                        pipeline.notifyRead(buf)
                    }
                    res == -ENOBUFS -> {
                        // All provided buffers consumed; kernel terminated multishot.
                        armRecv()
                    }
                    else -> {
                        // EOF (0) or error: close connection.
                        pipeline.notifyInactive()
                        close()
                    }
                }
            },
        )
        pipeline.notifyActive()
    }

    // --- Channel mode: suspend API via SuspendBridgeHandler ---

    // Lazily installed when Channel mode is first used (read/write/flush).
    private var bridge: SuspendBridgeHandler? = null
    private var readArmed = false

    /**
     * Installs [SuspendBridgeHandler] and arms the multishot recv if not already done.
     * Called on first suspend read/write/flush.
     */
    private fun ensureBridge(): SuspendBridgeHandler {
        bridge?.let { return it }
        val handler = SuspendBridgeHandler()
        pipeline.addLast("__suspend_bridge__", handler)
        bridge = handler
        if (!readArmed) {
            readArmed = true
            // armRecv must run on the EventLoop thread because
            // submitMultishotRecv accesses the io_uring ring (not thread-safe).
            eventLoop.dispatch(kotlin.coroutines.EmptyCoroutineContext, kotlinx.coroutines.Runnable {
                armRecv()
            })
        }
        return handler
    }

    /**
     * Reads decrypted/decoded data via [SuspendBridgeHandler].
     *
     * On first call, installs [SuspendBridgeHandler] into the pipeline
     * and arms multishot recv. Suspends until data arrives from the
     * pipeline's inbound path.
     *
     * @return number of bytes read, or -1 on EOF.
     * @throws IllegalStateException if the channel is closed.
     */
    override suspend fun read(buf: IoBuf): Int {
        check(!closed) { "Channel is closed" }
        return ensureBridge().read(buf)
    }

    /**
     * Writes [buf] through the pipeline's outbound path.
     *
     * Enters the pipeline from TAIL and traverses outbound handlers before
     * reaching [io.github.fukusaka.keel.pipeline.HeadHandler] → [IoUringIoTransport].
     *
     * Unlike [read], write does NOT install [SuspendBridgeHandler] or arm
     * multishot recv. This prevents conflict when [asSuspendSource] has already
     * armed its own multishot recv via [IoUringPushSource].
     *
     * @return number of bytes buffered (actual send happens on [flush]).
     * @throws IllegalStateException if the channel is closed or output is shut down.
     */
    override suspend fun write(buf: IoBuf): Int {
        check(!closed) { "Channel is closed" }
        check(!outputShutdown) { "Output already shut down" }
        val n = buf.readableBytes
        if (n == 0) return 0
        pipeline.requestWrite(buf)
        return n
    }

    /**
     * Flushes buffered writes through the pipeline's outbound path.
     *
     * Enters the pipeline from TAIL and traverses outbound handlers before
     * reaching [io.github.fukusaka.keel.pipeline.HeadHandler] → [IoUringIoTransport.flush].
     * Fire-and-forget: if send buffer is full, the transport submits async
     * SEND SQE and retries via CQE callback.
     *
     * @throws IllegalStateException if the channel is closed.
     */
    override suspend fun flush() {
        check(!closed) { "Channel is closed" }
        pipeline.requestFlush()
    }

    /** No-op. EOF is detected via [read] returning -1. */
    override suspend fun awaitClosed() {}

    private var outputShutdown = false

    /**
     * Sends TCP FIN via POSIX `shutdown(SHUT_WR)`.
     * Idempotent — safe to call multiple times.
     */
    override fun shutdownOutput() {
        if (!outputShutdown && !closed) {
            outputShutdown = true
            shutdown(fd, SHUT_WR)
        }
    }

    // --- PushChannel ---

    override fun asPushSuspendSource(): PushSuspendSource {
        val ring = bufferRing
            ?: error("Push source requires provided buffer ring (kernel 5.19+)")
        return IoUringPushSource(fd, eventLoop, ring)
    }

    /**
     * Returns a [SuspendSource] for reading from this channel.
     *
     * If multishot recv and provided buffer ring are available, uses the
     * push-model [IoUringPushSource] via [PushToSuspendSourceAdapter].
     * Otherwise, falls back to the pull-model default.
     */
    override fun asSuspendSource(): SuspendSource {
        return if (capabilities.multishotRecv && capabilities.providedBufferRing && bufferRing != null) {
            PushToSuspendSourceAdapter(IoUringPushSource(fd, eventLoop, bufferRing))
        } else {
            @Suppress("RedundantOverride")
            super<PipelinedChannel>.asSuspendSource()
        }
    }

    /**
     * Closes the connection and releases resources.
     *
     * Cancels the multishot recv, releases pending write buffers,
     * and closes the socket fd. Idempotent.
     */
    override fun close() {
        if (!closed) {
            closed = true
            if (multishotSlot >= 0) {
                eventLoop.cancelMultishot(multishotSlot)
                multishotSlot = -1
            }
            transport.close()
            platform.posix.close(fd)
        }
    }
}
