package io.github.fukusaka.keel.engine.nodejs

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.unsafeArray
import io.github.fukusaka.keel.pipeline.IoTransport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Node.js socket-based [IoTransport] implementation.
 *
 * Buffers writes and flushes them to the Node.js socket.
 * Node.js `socket.write()` buffers internally, so flush always
 * completes synchronously from keel's perspective.
 *
 * **Buffer lifecycle**: [write] retains the buffer and records the
 * byte range. [flush] copies data to a Node.js Buffer, sends it
 * via `socket.write()`, and releases the retained buffer.
 *
 * **Thread model**: JS is single-threaded (Node.js event loop).
 * All state fields are accessed from the same thread — no locking required.
 */
internal class NodeIoTransport(
    private val socket: Socket,
    override val allocator: BufferAllocator,
) : IoTransport {

    @Suppress("VarCouldBeVal") // will be mutated in close() after read path migration
    private var _open = true
    override val isOpen: Boolean get() = _open
    override val coroutineDispatcher: CoroutineDispatcher get() = Dispatchers.Unconfined
    override val supportsDeferredFlush: Boolean get() = false

    override var onFlushComplete: (() -> Unit)? = null

    private val pendingWrites = mutableListOf<PendingWrite>()

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
     * Sends all pending writes via Node.js `socket.write()`.
     *
     * Node.js buffers data internally — no EAGAIN handling needed.
     * Each pending write is copied byte-by-byte from [IoBuf]'s backing
     * Int8Array into a Node.js Buffer.
     *
     * @return always `true` because Node.js socket.write is synchronous
     *         from the caller's perspective (buffers internally).
     */
    override fun flush(): Boolean {
        var totalFlushed = 0
        for (pw in pendingWrites) {
            val src = pw.buf.unsafeArray
            // Int8Array.subarray shares the same underlying ArrayBuffer (zero-copy view).
            // Buffer.from(TypedArray) copies the data into a new Node.js Buffer.
            // This replaces the previous byte-by-byte jsArray.push loop (O(n) per byte).
            val slice = src.subarray(pw.offset, pw.offset + pw.length)
            val nodeBuf = nodeBuffer.from(slice)
            socket.write(nodeBuf)
            pw.buf.release()
            totalFlushed += pw.length
        }
        pendingWrites.clear()
        updatePendingBytes(-totalFlushed)
        onFlushComplete?.invoke()
        return true
    }

    /**
     * Releases all pending write buffers and destroys the socket.
     * Unsent data is discarded. Idempotent (Node.js socket.destroy is idempotent).
     */
    override fun close() {
        for (pw in pendingWrites) {
            pw.buf.release()
        }
        pendingWrites.clear()
        pendingBytes = 0
        _writable = true
        socket.destroy()
    }

    /**
     * Snapshot of a buffered write: the [IoBuf] (retained), the byte offset
     * where readable data starts, and the number of bytes to write.
     */
    internal class PendingWrite(val buf: IoBuf, val offset: Int, val length: Int)

    companion object {
        /** Cached Node.js Buffer constructor to avoid per-flush require() lookup. */
        private val nodeBuffer = js("require('buffer').Buffer")
    }
}
