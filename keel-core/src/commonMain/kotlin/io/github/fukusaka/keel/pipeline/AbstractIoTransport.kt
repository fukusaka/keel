package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.IoBuf
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Base class for [IoTransport] implementations with shared defaults.
 *
 * Provides:
 * - **Write buffering**: [write] retains and enqueues buffers into [pendingWrites].
 *   Subclasses implement [flush] to drain the queue via platform syscalls.
 * - **Write backpressure**: [pendingBytes] / [isWritable] / [updatePendingBytes]
 *   track buffered data and invoke [onWritabilityChanged] at high/low water marks.
 * - **Open state**: [opened] flag with [isOpen] property for idempotent close.
 * - **Callback properties**: [onRead], [onReadClosed], [onFlushComplete],
 *   [onWritabilityChanged] initialized to `null`.
 * - **Defaults**: [appDispatcher] = [ioDispatcher], [supportsDeferredFlush] = true,
 *   [awaitPendingFlush] = no-op, [awaitClosed] = no-op.
 *
 * Engine implementations extend this class and override platform-specific
 * members: [readEnabled] setter, [flush], [shutdownOutput], [close].
 *
 * @param allocator Buffer allocator for read operations.
 */
abstract class AbstractIoTransport(
    override val allocator: BufferAllocator,
) : IoTransport {

    // --- Open state ---

    /**
     * Transport open state.
     *
     * Written by [close] once (idempotent transition true → false) and
     * read by [isOpen], [write], and subclass flush paths. `@Volatile`
     * guarantees that a `false` written on the EventLoop thread is
     * visible to a caller that reads [isOpen] on another dispatcher.
     *
     * Subclasses MUST flip this flag only from the EventLoop thread
     * (or the engine-local equivalent) to keep the `pendingWrites` /
     * `pendingBytes` mutations below serialised.
     */
    @Volatile
    protected var opened = true
    override val isOpen: Boolean get() = opened

    // --- Read path callbacks ---

    override var onRead: ((IoBuf) -> Unit)? = null
    override var onReadClosed: (() -> Unit)? = null

    // --- Write path callbacks ---

    override var onFlushComplete: (() -> Unit)? = null
    override var onWritabilityChanged: ((Boolean) -> Unit)? = null

    // --- Write buffering ---

    /**
     * Queue of retained buffers awaiting [flush].
     *
     * [write] appends to this list; [flush] implementations drain it
     * via platform-specific syscalls and release each buffer after
     * successful transmission.
     */
    protected val pendingWrites = mutableListOf<PendingWrite>()

    /**
     * Buffers [buf] for the next [flush] call.
     *
     * Captures (readerIndex, readableBytes) snapshot and retains the buffer.
     * The caller's readerIndex is advanced immediately so it can reuse the buf.
     */
    override fun write(buf: IoBuf) {
        val bytes = buf.readableBytes
        if (bytes == 0) return
        val offset = buf.readerIndex
        buf.retain()
        buf.readerIndex += bytes
        pendingWrites.add(PendingWrite(buf, offset, bytes))
        updatePendingBytes(bytes)
    }

    // --- Write backpressure ---

    /**
     * Total bytes buffered in [pendingWrites] but not yet flushed.
     *
     * Incremented by [write], decremented by [updatePendingBytes] after
     * flush (partial or complete). Drives [isWritable] state transitions.
     */
    protected var pendingBytes: Int = 0
    private var writable: Boolean = true
    override val isWritable: Boolean get() = writable

    /**
     * Adjusts [pendingBytes] by [delta] and checks water mark thresholds.
     *
     * Called by subclass [flush] implementations after sending data
     * (negative delta) or by [write] after buffering (positive delta via
     * [write]). Triggers [onWritabilityChanged] when crossing thresholds.
     */
    protected fun updatePendingBytes(delta: Int) {
        pendingBytes += delta
        if (writable && pendingBytes >= IoTransport.DEFAULT_HIGH_WATER_MARK) {
            writable = false
            onWritabilityChanged?.invoke(false)
        } else if (!writable && pendingBytes < IoTransport.DEFAULT_LOW_WATER_MARK) {
            writable = true
            onWritabilityChanged?.invoke(true)
        }
    }

    // --- Defaults ---

    override val appDispatcher: CoroutineDispatcher get() = ioDispatcher
    override val supportsDeferredFlush: Boolean get() = true
    override suspend fun awaitPendingFlush() {}
    override suspend fun awaitClosed() {}

    /**
     * Snapshot of a buffered write: the [IoBuf] (retained), the byte offset
     * where readable data starts, and the number of bytes to write.
     *
     * Offset/length are recorded separately because [IoBuf.readerIndex] is
     * advanced at [write] time so the caller can reuse the buffer immediately.
     */
    class PendingWrite(val buf: IoBuf, val offset: Int, val length: Int)
}
