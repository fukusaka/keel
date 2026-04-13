package io.github.fukusaka.keel.engine.nio

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.unsafeBuffer
import io.github.fukusaka.keel.pipeline.IoTransport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.nio.channels.SelectionKey
import java.nio.channels.SocketChannel
import kotlin.coroutines.resume

/**
 * Non-suspend [IoTransport] for NIO pipeline channels.
 *
 * Buffers outbound [IoBuf] writes and flushes them via [SocketChannel.write].
 * When the send buffer is full (write returns 0), registers OP_WRITE with the
 * [eventLoop] and retries via [onFlushComplete] callback.
 *
 * **Thread safety**: all methods must be called on the [eventLoop] thread.
 *
 * **Buffer lifecycle**: `write()` retains the buffer; `flush()` releases it
 * after transmission or on error.
 */
internal class NioIoTransport(
    private val socketChannel: SocketChannel,
    private val selectionKey: SelectionKey,
    private val eventLoop: NioEventLoop,
    override val allocator: BufferAllocator,
) : IoTransport {

    private var _open = true
    override val isOpen: Boolean get() = _open
    override val coroutineDispatcher: CoroutineDispatcher get() = eventLoop

    @Suppress("InjectDispatcher")
    override val appDispatcher: CoroutineDispatcher get() = Dispatchers.Default

    // --- Read path ---

    override var onRead: ((IoBuf) -> Unit)? = null
    override var onReadClosed: (() -> Unit)? = null

    override var readEnabled: Boolean = false
        set(value) {
            field = value
            if (value && _open) armRead()
        }

    private fun armRead() {
        if (!socketChannel.isOpen) return
        eventLoop.setInterestCallback(
            selectionKey,
            SelectionKey.OP_READ,
            Runnable { onReadable() },
        )
    }

    private fun onReadable() {
        if (!socketChannel.isOpen) return
        val buf = allocator.allocate(IoTransport.DEFAULT_READ_BUFFER_SIZE)
        val bb = buf.unsafeBuffer
        bb.position(buf.writerIndex)
        bb.limit(buf.capacity)
        val n = socketChannel.read(bb)
        when {
            n > 0 -> {
                buf.writerIndex += n
                onRead?.invoke(buf)
                armRead()
            }
            n == -1 -> {
                buf.release()
                onReadClosed?.invoke()
            }
            else -> {
                buf.release()
                armRead()
            }
        }
    }

    // --- Lifecycle ---

    private var outputShutdown = false

    override fun shutdownOutput() {
        if (!outputShutdown && socketChannel.isOpen) {
            outputShutdown = true
            socketChannel.shutdownOutput()
        }
    }

    // --- Write path ---

    private val pendingWrites = mutableListOf<PendingWrite>()

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

    /**
     * Attempts to send all pending writes via [SocketChannel.write].
     *
     * @return `true` if all data was sent synchronously, `false` if the send
     *         buffer is full and an async OP_WRITE callback is pending.
     */
    override fun flush(): Boolean {
        if (pendingWrites.isEmpty()) return true
        if (pendingWrites.size == 1) {
            return flushSingle(pendingWrites.removeFirst())
        }
        return flushGather()
    }

    /**
     * Releases all pending write buffers, cancels the SelectionKey, and
     * closes the socket channel. Idempotent.
     */
    override fun close() {
        if (!_open) return
        _open = false
        for (pw in pendingWrites) pw.buf.release()
        pendingWrites.clear()
        pendingBytes = 0
        _writable = true
        selectionKey.cancel()
        if (socketChannel.isOpen) socketChannel.close()
    }

    /**
     * Writes a single [PendingWrite] via [SocketChannel.write].
     *
     * On send buffer full (write returns 0), re-enqueues the remainder
     * and registers OP_WRITE callback for async retry.
     */
    private fun flushSingle(pw: PendingWrite): Boolean {
        val bb = pw.buf.unsafeBuffer
        bb.position(pw.offset)
        bb.limit(pw.offset + pw.length)
        while (bb.hasRemaining()) {
            val n = socketChannel.write(bb)
            if (n == 0) {
                // Send buffer full — defer via OP_WRITE callback.
                val written = bb.position() - pw.offset
                val remaining = bb.remaining()
                val newOffset = bb.position()
                pendingWrites.add(0, PendingWrite(pw.buf, newOffset, remaining))
                updatePendingBytes(-written)
                registerWriteCallback()
                return false
            }
        }
        pw.buf.release()
        updatePendingBytes(-pw.length)
        return true
    }

    /**
     * Writes multiple pending buffers via [java.nio.channels.GatheringByteChannel.write].
     *
     * On partial write, fully-written buffers are released and the remainder
     * is re-enqueued with OP_WRITE callback for async retry.
     */
    private fun flushGather(): Boolean {
        val bbArray = Array(pendingWrites.size) { i ->
            val pw = pendingWrites[i]
            pw.buf.unsafeBuffer.duplicate().apply {
                position(pw.offset)
                limit(pw.offset + pw.length)
            }
        }
        val totalBytes = bbArray.sumOf { it.remaining().toLong() }
        val written = socketChannel.write(bbArray)

        if (written >= totalBytes) {
            for (pw in pendingWrites) pw.buf.release()
            pendingWrites.clear()
            updatePendingBytes(-totalBytes.toInt())
            return true
        }

        // Partial write: release fully-written, re-enqueue remainder.
        val remaining = mutableListOf<PendingWrite>()
        for (i in pendingWrites.indices) {
            val pw = pendingWrites[i]
            val bb = bbArray[i]
            if (!bb.hasRemaining()) {
                pw.buf.release()
            } else {
                val consumed = bb.position() - pw.offset
                remaining.add(PendingWrite(pw.buf, pw.offset + consumed, pw.length - consumed))
            }
        }
        pendingWrites.clear()
        pendingWrites.addAll(remaining)
        updatePendingBytes(-written.toInt())
        registerWriteCallback()
        return false
    }

    private var flushContinuation: kotlinx.coroutines.CancellableContinuation<Unit>? = null

    /** Registers OP_WRITE callback on the EventLoop to retry flush when the socket becomes writable. */
    private fun registerWriteCallback() {
        eventLoop.setInterestCallback(
            selectionKey,
            SelectionKey.OP_WRITE,
            Runnable {
                val done = flush()
                if (done) {
                    flushContinuation?.let { cont ->
                        flushContinuation = null
                        cont.resume(Unit)
                    }
                    onFlushComplete?.invoke()
                }
            },
        )
    }

    override suspend fun awaitPendingFlush() {
        if (pendingWrites.isEmpty()) return
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            flushContinuation = cont
            cont.invokeOnCancellation { flushContinuation = null }
        }
    }

    /**
     * Snapshot of a buffered write: the [IoBuf] (retained), the byte offset
     * where readable data starts, and the number of bytes to write.
     *
     * Offset/length are recorded separately because [IoBuf.readerIndex] is
     * advanced at write() time so the caller can reuse the buffer immediately.
     */
    internal class PendingWrite(val buf: IoBuf, val offset: Int, val length: Int)
}
