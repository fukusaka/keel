package io.github.fukusaka.keel.engine.nodejs

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.unsafeArray
import io.github.fukusaka.keel.pipeline.AbstractIoTransport
import io.github.fukusaka.keel.pipeline.AbstractIoTransport.PendingWrite
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Node.js socket-based [IoTransport] implementation.
 *
 * Handles both read and write paths for Node.js sockets.
 *
 * **Read path (copy from Node.js Buffer)**: Node.js delivers data
 * asynchronously via `socket.on("data")` before the user provides a buffer.
 * The Buffer content is copied into [IoBuf] via Int8Array.set(). This is an
 * accepted limitation — same structural constraint as Netty's ByteBuf
 * and NWConnection's dispatch_data_t copy.
 *
 * **Write path**: Buffers writes and flushes them to the Node.js socket.
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
    allocator: BufferAllocator,
) : AbstractIoTransport(allocator) {

    override val ioDispatcher: CoroutineDispatcher get() = Dispatchers.Unconfined
    override val supportsDeferredFlush: Boolean get() = false

    // --- Read path ---

    override var readEnabled: Boolean = false
        set(value) {
            field = value
            if (value && opened) armRead()
        }

    /**
     * Registers `socket.on("data")` and `socket.on("end"/"error")` to
     * deliver data via [onRead] and [onReadClosed] callbacks.
     *
     * Each "data" event copies the Node.js Buffer into [IoBuf] and calls [onRead].
     */
    private fun armRead() {
        socket.on("data") { data: dynamic ->
            if (!opened) return@on
            val dataLength = data.length as Int
            if (dataLength == 0) return@on

            val buf = allocator.allocate(dataLength)
            // Copy Node.js Buffer (Uint8Array subclass) to IoBuf's Int8Array.
            // Int8Array and Uint8Array share the same byte representation,
            // so we create an Int8Array view over the Buffer's ArrayBuffer
            // and use IoBuf.unsafeArray.set() for a single native memcpy.
            val srcView = js("new Int8Array(data.buffer, data.byteOffset, data.length)")
            buf.unsafeArray.asDynamic().set(srcView, buf.writerIndex)
            buf.writerIndex += dataLength

            onRead?.invoke(buf)
        }

        socket.on("end") { _: dynamic ->
            if (opened) {
                onReadClosed?.invoke()
            }
        }

        socket.on("error") { _: dynamic ->
            if (opened) {
                onReadClosed?.invoke()
            }
        }
    }

    // --- Lifecycle ---

    private var outputShutdown = false

    /**
     * Sends TCP FIN to the peer via Node.js `socket.end()`.
     * Fire-and-forget: no blocking or suspend needed.
     */
    override fun shutdownOutput() {
        if (!outputShutdown && opened) {
            outputShutdown = true
            socket.end()
        }
    }

    // --- Write path ---

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
     * Unsent data is discarded. Idempotent: subsequent calls are no-ops.
     */
    override fun close() {
        if (!opened) return
        opened = false
        for (pw in pendingWrites) {
            pw.buf.release()
        }
        pendingWrites.clear()
        pendingBytes = 0
        socket.destroy()
    }

    companion object {
        /** Cached Node.js Buffer constructor to avoid per-flush require() lookup. */
        private val nodeBuffer = js("require('buffer').Buffer")
    }
}
