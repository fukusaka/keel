package io.github.fukusaka.keel.engine.nio

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.unsafeBuffer
import io.github.fukusaka.keel.pipeline.IoTransport
import java.nio.channels.SelectionKey
import java.nio.channels.SocketChannel

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
) : IoTransport {

    private val pendingWrites = mutableListOf<PendingWrite>()

    override var onFlushComplete: (() -> Unit)? = null

    override fun write(buf: IoBuf) {
        val bytes = buf.readableBytes
        if (bytes == 0) return
        val offset = buf.readerIndex
        buf.retain()
        buf.readerIndex += bytes
        pendingWrites.add(PendingWrite(buf, offset, bytes))
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
     * Releases all pending write buffers and closes the socket channel.
     * Unsent data is discarded. Idempotent.
     */
    override fun close() {
        for (pw in pendingWrites) pw.buf.release()
        pendingWrites.clear()
        if (socketChannel.isOpen) socketChannel.close()
    }

    private fun flushSingle(pw: PendingWrite): Boolean {
        val bb = pw.buf.unsafeBuffer
        bb.position(pw.offset)
        bb.limit(pw.offset + pw.length)
        while (bb.hasRemaining()) {
            val n = socketChannel.write(bb)
            if (n == 0) {
                // Send buffer full — defer via OP_WRITE callback.
                val remaining = bb.remaining()
                val newOffset = bb.position()
                pendingWrites.add(0, PendingWrite(pw.buf, newOffset, remaining))
                registerWriteCallback()
                return false
            }
        }
        pw.buf.release()
        return true
    }

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
        registerWriteCallback()
        return false
    }

    private fun registerWriteCallback() {
        eventLoop.setInterestCallback(selectionKey, SelectionKey.OP_WRITE, Runnable {
            val done = flush()
            if (done) {
                onFlushComplete?.invoke()
            }
        })
    }

    internal class PendingWrite(val buf: IoBuf, val offset: Int, val length: Int)
}
