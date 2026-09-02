package io.github.fukusaka.keel.engine.nodejs

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.unsafeArray
import io.github.fukusaka.keel.pipeline.AbstractIoTransport
import io.github.fukusaka.keel.pipeline.EventLoopTimer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Schedules [callback] to run on the next iteration of Node's event loop after
 * pending I/O events. Used by [NodeIoTransport.flush] to coalesce corked writes.
 */
private external fun setImmediate(callback: () -> Unit): dynamic

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
 * **I/O ownership invariant**: JS is single-threaded; every
 * `socket.on(...)` callback, `socket.write` continuation, and
 * coroutine resumption runs on the single Node.js event-loop thread
 * in FIFO order. This matches the "strict single-thread per loop +
 * cross-thread funnel" contract that the POSIX engines enforce
 * explicitly via `if (inEventLoop()) apply else dispatch(Runnable)`,
 * but the enforcement is upstream-delegated (V8 + libuv). All state
 * fields are accessed from that single thread — no locking required,
 * no funnel needed. JS exposes no thread-identity primitive, so
 * unlike the POSIX engines there is no runtime `assertInEventLoop`
 * analog; the invariant is documented and structurally enforced
 * rather than runtime-checked. See `NodeEngine` / `IoEngine` KDoc for
 * the cross-engine contract.
 */
internal class NodeIoTransport(
    private val socket: Socket,
    allocator: BufferAllocator,
    idleTimeoutMillis: Long = 0,
    private val flushCoalescing: Boolean = true,
) : AbstractIoTransport(allocator) {

    override val ioDispatcher: CoroutineDispatcher get() = Dispatchers.Unconfined

    /** Read/write idle (no-progress) timeout for this connection; see [AbstractIoTransport]. */
    override val idleTimeoutMillis: Long = idleTimeoutMillis

    /** Backed by Node.js `setTimeout` on the single libuv event-loop thread. */
    override val eventLoopTimer: EventLoopTimer get() = NodeEventLoopTimer

    // --- Read path ---

    /**
     * Stable [Socket.on] listener for "data" events. Captured into a field
     * so [readEnabled] setter can attach / detach the same function
     * instance via `socket.on / socket.removeListener`. Node.js stream
     * listener removal requires referential equality with the registered
     * function — fresh lambdas on each call would leak the previous
     * registration.
     */
    private val dataListener: (dynamic) -> Unit = { data: dynamic ->
        if (opened) {
            val dataLength = data.length as Int
            if (dataLength > 0) {
                touchIdleTimeout() // progress: refresh the read-idle deadline
                val buf = allocator.allocate(dataLength)
                // Copy Node.js Buffer (Uint8Array subclass) to IoBuf's
                // Int8Array. Int8Array and Uint8Array share the same byte
                // representation, so we create an Int8Array view over the
                // Buffer's ArrayBuffer and use IoBuf.unsafeArray.set() for
                // a single native memcpy.
                val srcView = js("new Int8Array(data.buffer, data.byteOffset, data.length)")
                buf.unsafeArray.asDynamic().set(srcView, buf.writerIndex)
                buf.writerIndex += dataLength
                onRead?.invoke(buf)
                // One 'data' event is one batch.
                onReadComplete?.invoke()
            }
        }
    }

    override var readEnabled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (!opened) return
            if (value) {
                // The connection is now waiting to read → the read-side idle timeout
                // applies (covers accept-to-first-byte, slowloris-silent, keep-alive idle).
                armIdleTimeout()
                // Attach 'data' listener — Node.js transitions the stream
                // into flowing mode and starts emitting 'data' events.
                socket.on("data", dataListener)
            } else {
                cancelIdleTimeout() // back-pressure: pause the read-idle timeout
                // Detach 'data' listener — Node.js drops back to paused
                // mode when no 'data' listeners remain. Kernel `rcvbuf`
                // retains the bytes for genuine TCP back-pressure. The
                // 'end' / 'error' listeners installed in [init] continue
                // to fire, so peer FIN / RST is still surfaced via
                // [onReadClosed].
                socket.asDynamic().removeListener("data", dataListener)
            }
        }

    init {
        // Register 'end' / 'error' listeners at construction so peer FIN /
        // peer RST is surfaced via [onReadClosed] even when the user keeps
        // [readEnabled] = false for the entire connection lifetime (e.g.
        // write-only push client, one-direction logger, monitoring metrics
        // sender). Node.js delivers 'end' / 'error' to all registered
        // listeners regardless of stream paused / flowing state, so this
        // listener stays effective even when no 'data' listener is
        // attached. Without this, kqueue's `EV_EOF` / epoll's
        // `EPOLLRDHUP` analogue (Node's 'end') was effectively lost
        // because the listener was lazily registered inside `armRead()`,
        // which was never reached when `readEnabled` stayed `false`.
        socket.on("end") { _: dynamic ->
            if (opened) onReadClosed?.invoke()
        }
        socket.on("error") { _: dynamic ->
            if (opened) onReadClosed?.invoke()
        }
        // 'drain' fires when Node's internal write buffer has emptied into the
        // kernel — the peer has been draining, so a stalled write recovered. Cancel
        // the write-idle timer that [flush] arms on back-pressure. A no-op when not
        // armed (no timeout configured or the write never stalled).
        socket.on("drain") { _: dynamic ->
            if (opened) cancelWriteIdleTimeout()
        }
    }

    // --- Lifecycle ---

    /**
     * Sends TCP FIN to the peer via Node.js `socket.end()`.
     *
     * Node's writable stream already orders `end()` behind the writes it has
     * accepted, so the only output that could be overtaken is what is still
     * in keel's own [pendingWrites] — the shared half-close path drains that
     * first. Fire-and-forget: no blocking or suspend needed, and no thread
     * hop (Node is single-threaded).
     */
    override fun shutdownOutput() {
        shutdownOutputOwned()
    }

    override fun sendFin() {
        socket.end()
    }

    // --- Write path ---

    /**
     * Set to `true` while a corked write batch is scheduled to be flushed by
     * a pending [setImmediate] tick. Additional [flush] calls that arrive
     * before that tick fires leave the socket corked and let their bytes
     * accumulate in Node's internal buffer; the scheduled tick emits a single
     * `uncork()`, at which point Node collapses all queued writes into one
     * `Socket._writev` → `writev(2)` gather send.
     */
    private var corkPending: Boolean = false

    /**
     * Sends all pending writes via Node.js `socket.write()`.
     *
     * On the first call in a given event-loop tick this corks the socket
     * and schedules an `uncork()` via [setImmediate]. Subsequent calls in
     * the same tick observe [corkPending] and write straight into the corked
     * buffer without arming another tick. When the scheduled callback runs,
     * `uncork()` releases every buffered write together — Node maps that
     * to `Socket._writev` on POSIX, coalescing the per-frame `socket.write`
     * calls typical of SSE / chunked streaming into one `writev(2)` gather.
     *
     * Node.js buffers data internally — no EAGAIN handling needed.
     *
     * @return always `true` because Node.js socket.write is synchronous
     *         from the caller's perspective (buffers internally).
     */
    override fun flush(): Boolean {
        // Skip the cork/uncork protocol when there is nothing to write — otherwise
        // a stray `requestFlush` without a preceding `requestWrite` would still cork
        // the socket, schedule a setImmediate, and burn a tick to uncork nothing.
        if (pendingWrites.isEmpty()) return true

        var totalFlushed = 0
        var backpressured = false
        // Opt-out: skip the cork/uncork protocol when the engine config disables
        // coalescing. Each socket.write goes straight to Node's internal buffer
        // as it did before #895.
        if (flushCoalescing && !corkPending) {
            socket.cork()
            corkPending = true
            setImmediate {
                if (corkPending) {
                    corkPending = false
                    // Skip the uncork if the socket was already destroyed by close():
                    // uncork() on a destroyed socket is a no-op in Node but avoiding
                    // the call keeps the trace clean when the connection was aborted
                    // mid-batch.
                    if (opened) socket.uncork()
                }
            }
        }
        for (pw in pendingWrites) {
            val src = pw.buf.unsafeArray
            // Int8Array.subarray shares the same underlying ArrayBuffer (zero-copy view).
            // Buffer.from(TypedArray) copies the data into a new Node.js Buffer.
            // This replaces the previous byte-by-byte jsArray.push loop (O(n) per byte).
            val slice = src.subarray(pw.offset, pw.offset + pw.length)
            val nodeBuf = nodeBuffer.from(slice)
            // socket.write returns false once Node's internal buffer exceeds its high
            // water mark — i.e. the kernel send buffer is full because the peer is not
            // draining (slow-read). The data is still queued, but the connection is
            // back-pressured until the 'drain' event.
            if (socket.write(nodeBuf) == false) backpressured = true
            pw.buf.release()
            totalFlushed += pw.length
        }
        pendingWrites.clear()
        // Node accepts every write into its own buffer synchronously, so keel's
        // pendingBytes returns to 0 here (this cancels any seam-driven write-idle).
        updatePendingBytes(-totalFlushed)
        onFlushComplete?.invoke()
        sendFinIfDrained()
        // Drive the write-idle timer from Node's real back-pressure instead: arm it
        // when a write stalled (peer not draining), to be cancelled by the 'drain'
        // listener when the buffer empties — or to fire and force-close a peer that
        // never drains. A fast peer leaves `backpressured` false and trips nothing.
        if (backpressured) armWriteIdleTimeout()
        return true
    }

    /**
     * Releases all pending write buffers and destroys the socket.
     * Unsent data is discarded. Idempotent: subsequent calls are no-ops.
     *
     * **Thread safety**: JS is single-threaded, so every caller runs on
     * the Node.js event-loop thread and the `opened` read / write and
     * the `pendingWrites` mutations are atomic by construction. No
     * locking is needed, but the idempotent first-call contract matches
     * the multi-threaded transports.
     */
    override fun close() {
        if (!opened) return
        opened = false
        cancelIdleTimeout()
        cancelWriteIdleTimeout()
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
