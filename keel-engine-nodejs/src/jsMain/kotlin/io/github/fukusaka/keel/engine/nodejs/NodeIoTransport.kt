package io.github.fukusaka.keel.engine.nodejs

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.unsafeArray
import io.github.fukusaka.keel.pipeline.IoTransport

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
) : IoTransport {

    override var onFlushComplete: (() -> Unit)? = null

    private val pendingWrites = mutableListOf<PendingWrite>()

    override fun write(buf: IoBuf) {
        val bytes = buf.readableBytes
        if (bytes == 0) return
        val offset = buf.readerIndex
        buf.retain()
        buf.readerIndex += bytes
        pendingWrites.add(PendingWrite(buf, offset, bytes))
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
        for (pw in pendingWrites) {
            val src = pw.buf.unsafeArray
            val jsArray = js("[]")
            for (i in 0 until pw.length) {
                jsArray.push(src.asDynamic()[pw.offset + i])
            }
            val nodeBuffer = js("require('buffer').Buffer.from(jsArray)")
            socket.write(nodeBuffer)
            pw.buf.release()
        }
        pendingWrites.clear()
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
        socket.destroy()
    }

    /**
     * Snapshot of a buffered write: the [IoBuf] (retained), the byte offset
     * where readable data starts, and the number of bytes to write.
     */
    internal class PendingWrite(val buf: IoBuf, val offset: Int, val length: Int)
}
