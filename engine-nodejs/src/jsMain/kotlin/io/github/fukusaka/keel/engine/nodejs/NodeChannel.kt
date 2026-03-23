package io.github.fukusaka.keel.engine.nodejs

import io.github.fukusaka.keel.io.BufferAllocator
import io.github.fukusaka.keel.io.NativeBuf
import io.github.fukusaka.keel.core.SocketAddress
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import io.github.fukusaka.keel.core.Channel as KeelChannel

/**
 * Snapshot of a buffered write.
 */
private class PendingWrite(val buf: NativeBuf, val offset: Int, val length: Int)

/**
 * Node.js `net.Socket`-based [KeelChannel] implementation for JS.
 *
 * **Push-to-pull bridge**: Node.js delivers data via `socket.on("data")`
 * callbacks (push model). This class buffers received chunks in an
 * [ArrayDeque] and suspends [read] via [suspendCoroutine] when no data
 * is available.
 *
 * **Read path (copy)**: Node.js Buffer (`dynamic`) is copied byte-by-byte
 * into [NativeBuf]. Same structural constraint as Netty/NWConnection —
 * push model engines deliver data before the user provides a buffer.
 *
 * Phase (a): all suspend functions use `suspendCoroutine` with Node.js
 * event callbacks. JS is single-threaded, so no locking is needed.
 *
 * @param socket    The Node.js net.Socket.
 * @param allocator Buffer allocator for read operations.
 */
internal class NodeChannel(
    private val socket: Socket,
    override val allocator: BufferAllocator,
    override val remoteAddress: SocketAddress?,
    override val localAddress: SocketAddress?,
) : KeelChannel {

    private val readQueue = ArrayDeque<dynamic>()
    private var pendingRead: ((dynamic) -> Unit)? = null
    private var eofReceived = false
    private val pendingWrites = mutableListOf<PendingWrite>()
    private var _open = true
    private var _active = true
    private var outputShutdown = false

    init {
        socket.on("data") { data: dynamic ->
            if (pendingRead != null) {
                val callback = pendingRead!!
                pendingRead = null
                callback(data)
            } else {
                readQueue.addLast(data)
            }
        }
        socket.on("end") { _: dynamic ->
            eofReceived = true
            if (pendingRead != null) {
                val callback = pendingRead!!
                pendingRead = null
                callback(null)
            }
        }
        socket.on("error") { _: dynamic ->
            eofReceived = true
            if (pendingRead != null) {
                val callback = pendingRead!!
                pendingRead = null
                callback(null)
            }
        }
    }

    override val isOpen: Boolean get() = _open
    override val isActive: Boolean get() = _active

    override suspend fun awaitClosed() {}

    /**
     * Reads data from the socket into [buf].
     *
     * If data is already buffered, copies immediately. Otherwise suspends
     * until Node.js fires the "data" event.
     *
     * @return number of bytes read, or -1 on EOF/error.
     */
    override suspend fun read(buf: NativeBuf): Int {
        check(_open) { "Channel is closed" }

        val data: dynamic = if (readQueue.isNotEmpty()) {
            readQueue.removeFirst()
        } else if (eofReceived) {
            null
        } else {
            suspendCoroutine { cont ->
                pendingRead = { d -> cont.resume(d) }
            }
        }

        if (data == null) return -1

        // Copy Node.js Buffer to NativeBuf byte-by-byte
        val length = (data.length as Int).coerceAtMost(buf.writableBytes)
        for (i in 0 until length) {
            buf.writeByte((data[i] as Int).toByte())
        }
        return length
    }

    override suspend fun write(buf: NativeBuf): Int {
        check(_open) { "Channel is closed" }
        check(!outputShutdown) { "Output already shut down" }
        val bytes = buf.readableBytes
        if (bytes == 0) return 0
        val offset = buf.readerIndex
        buf.retain()
        buf.readerIndex += bytes
        pendingWrites.add(PendingWrite(buf, offset, bytes))
        return bytes
    }

    override suspend fun flush() {
        check(_open) { "Channel is closed" }
        for (pw in pendingWrites) {
            // Read bytes from NativeBuf's backing Int8Array at the recorded offset.
            // Cannot use readByte() because readerIndex was already advanced in write().
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
    }

    override fun shutdownOutput() {
        if (!outputShutdown && _open) {
            outputShutdown = true
            socket.end()
        }
    }

    override fun close() {
        if (_open) {
            _open = false
            _active = false
            for (pw in pendingWrites) {
                pw.buf.release()
            }
            pendingWrites.clear()
            socket.destroy()
        }
    }

}
