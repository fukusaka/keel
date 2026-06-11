@file:OptIn(UnsafeIoBufApi::class)

package io.github.fukusaka.keel.engine.nio

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.UnsafeIoBufApi
import io.github.fukusaka.keel.buf.unsafeBuffer
import io.github.fukusaka.keel.core.IdleReadPolicy
import io.github.fukusaka.keel.pipeline.AbstractIoTransport
import io.github.fukusaka.keel.pipeline.AbstractIoTransport.PendingWrite
import io.github.fukusaka.keel.pipeline.EventLoopTimer
import io.github.fukusaka.keel.pipeline.IoTransport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.channels.SelectionKey
import java.nio.channels.SocketChannel
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume

/**
 * NIO [IoTransport] implementation for JVM.
 *
 * **Read path**: registers OP_READ via [NioEventLoop.setInterestCallback].
 * On data arrival, allocates a buffer, calls [SocketChannel.read], and delivers
 * via [onRead]. EOF (read returns -1) triggers [onReadClosed].
 *
 * **Idle-read trade-off** ([idleReadPolicy]): Java NIO `Selector` exposes
 * only `POLLIN` to user code — there is no `POLLRDHUP` analogue, so the
 * engine cannot observe peer FIN without calling `SocketChannel.read`,
 * which in turn drains kernel `rcvbuf` and breaks back-pressure. The
 * selected [IdleReadPolicy] picks which side of the trade-off is
 * preserved while [readEnabled] is `false`:
 * - [IdleReadPolicy.DETECT_PEER_CLOSE]: arm `OP_READ` at construction;
 *   reads always run when the selector fires and are always delivered
 *   through [onRead] in both `readEnabled` states (the pre-attach event
 *   journal absorbs bytes that arrive before the first user handler, so
 *   nothing is dropped); `read = -1` always surfaces through
 *   [onReadClosed]. Flipping `readEnabled = false` does NOT stop inbound
 *   delivery under this policy.
 * - [IdleReadPolicy.PRESERVE_BACKPRESSURE]: arm `OP_READ` only when
 *   `readEnabled` flips to `true`; data sits in `rcvbuf` and the peer's
 *   TCP window stalls; peer FIN is not surfaced until `readEnabled`
 *   becomes `true` again or `SO_KEEPALIVE` declares the peer dead.
 *
 * See [IdleReadPolicy] KDoc for the engine applicability table and
 * recommended choice per workload.
 *
 * **Write path**: buffers outbound [IoBuf] writes and flushes via
 * [SocketChannel.write] / [GatheringByteChannel.write][java.nio.channels.GatheringByteChannel.write].
 * When the send buffer is full (write returns 0), registers OP_WRITE and retries.
 *
 * **Thread safety**: read / write / flush must be called on the [eventLoop]
 * thread. [close] is safe to call from any thread — a non-EventLoop caller
 * dispatches the teardown onto [eventLoop] and returns immediately. The
 * `opened` flag ([AbstractIoTransport]) is `@Volatile`, and the teardown
 * block re-checks it on the EventLoop thread to remain idempotent.
 *
 * [ioDispatcher] is the NIO [NioEventLoop] itself, so coroutine-side
 * `withContext(ioDispatcher)` hops (e.g. `PipelinedChannel.read` / `write` /
 * `flush`) resume on the same Selector thread that drives
 * [SocketChannel.read] / [SocketChannel.write]. An earlier `appDispatcher`
 * override to `Dispatchers.Default` was motivated by a historical
 * measurement on Ubuntu loopback in which EL dispatch regressed
 * `ktor-keel-nio` by -37%. The regression no longer reproduces
 * (513k → 562k req/s, +9.5% at 4t/100c/10s) once the PipelinedChannel +
 * HttpWriter redesign and `NioEventLoop.dispatch`'s `inEventLoop`
 * wakeup-skip optimisation together removed the overhead that motivated
 * the override.
 */
internal class NioIoTransport(
    internal val socketChannel: SocketChannel,
    private val selectionKey: SelectionKey,
    private val eventLoop: NioEventLoop,
    allocator: BufferAllocator,
    private val idleReadPolicy: IdleReadPolicy,
    /**
     * Effective per-connection read buffer size (the bind / connect override
     * or the engine-wide default — see
     * [io.github.fukusaka.keel.core.IoEngineConfig.readBufferSize]). Fixed for
     * this connection's lifetime. A matching pool size class is registered on
     * the EventLoop allocator lazily on the first read (on the EventLoop
     * thread, where the allocator is owned) so a non-default size is pooled.
     */
    private val readBufferSize: Int = IoTransport.DEFAULT_READ_BUFFER_SIZE,
    idleTimeoutMillis: Long = 0,
) : AbstractIoTransport(allocator) {

    override val ioDispatcher: CoroutineDispatcher get() = eventLoop

    /** Read/write idle (no-progress) timeout for this connection; see [AbstractIoTransport]. */
    override val idleTimeoutMillis: Long = idleTimeoutMillis

    /** Backed by this EventLoop's per-loop [DeadlineScheduler] (EventLoop-confined). */
    override val eventLoopTimer: EventLoopTimer get() = eventLoop.deadlineScheduler

    // One-time guard for lazy pool-class registration (see [readBufferSize]).
    // Touched only on the EventLoop thread (the read path).
    private var readPoolRegistered = false

    // --- Read path ---

    /**
     * [IdleReadPolicy.DETECT_PEER_CLOSE]: arm `OP_READ` here so peer FIN
     * surfaces through [onReadClosed] regardless of the user's
     * [readEnabled] state. Arming runs *after* `AbstractPipelinedChannel.init`
     * has wired up [onRead] / [onReadClosed], so the first selector
     * fire always observes non-null callbacks; arming earlier in
     * `init { }` races with the channel-construction sequence and can
     * leak bytes through a still-null [onRead] when the worker
     * EventLoop's selector picks up the readable event before
     * `AbstractPipelinedChannel.init` finishes.
     */
    override fun onChannelAttached() {
        if (idleReadPolicy == IdleReadPolicy.DETECT_PEER_CLOSE) {
            armRead()
        }
    }

    override var readEnabled: Boolean = false
        set(value) {
            field = value
            // [IdleReadPolicy.DETECT_PEER_CLOSE]: OP_READ is already
            // armed from construction and we keep it armed for the
            // lifetime of the transport — flipping `readEnabled` only
            // controls whether [onReadable] delivers the bytes or
            // releases them silently.
            if (value && opened) {
                // The connection is now waiting to read → the read-side idle timeout
                // applies (covers accept-to-first-byte, slowloris, keep-alive idle);
                // policy-independent.
                armIdleTimeout()
                if (idleReadPolicy == IdleReadPolicy.PRESERVE_BACKPRESSURE) armRead()
            } else if (!value) {
                cancelIdleTimeout() // back-pressure: pause the read-idle timeout
            }
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
        if (!readPoolRegistered) {
            // Idempotent; on the EventLoop thread that owns the allocator.
            // No-op for the engine-default size already pooled by the
            // allocator child, and for pool-less allocators.
            allocator.registerPoolSize(readBufferSize, READ_BUFFER_POOL_SLOTS)
            readPoolRegistered = true
        }
        val buf = allocator.allocate(readBufferSize)
        val bb = buf.unsafeBuffer
        bb.position(buf.writerIndex)
        bb.limit(buf.capacity)
        val n = socketChannel.read(bb)
        when {
            n > 0 -> {
                buf.writerIndex += n
                touchIdleTimeout() // progress: refresh the read-idle deadline
                // Always deliver via [onRead] in both modes. In
                // [IdleReadPolicy.PRESERVE_BACKPRESSURE] this branch is
                // only reachable when `readEnabled = true` (otherwise
                // OP_READ is not armed). In [IdleReadPolicy.DETECT_PEER_CLOSE]
                // we deliver regardless of `readEnabled`; bytes that
                // arrive while no user [InboundHandler] is installed
                // are absorbed by `DefaultPipeline`'s pre-attach event
                // journal and replayed when the first user handler is
                // added — this trades engine-level data dropping for
                // pipeline-level buffering, closing the data-loss
                // caveat that DETECT_PEER_CLOSE previously documented.
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
     * closes the socket channel. Idempotent and thread-safe.
     *
     * If the caller is already on the [eventLoop] thread the teardown
     * runs synchronously; otherwise it is dispatched to the EventLoop
     * so the `pendingWrites` / `pendingBytes` / `selectionKey` mutations
     * stay serialised with [write] / [flush] on the EventLoop side.
     */
    override fun close() {
        if (!markClosing()) return
        if (eventLoop.inEventLoop()) {
            teardownOnEventLoop()
        } else {
            eventLoop.dispatch(EmptyCoroutineContext, Runnable { teardownOnEventLoop() })
        }
    }

    private fun teardownOnEventLoop() {
        if (!markTeardownStarted()) return
        cancelIdleTimeout()
        cancelWriteIdleTimeout()
        for (pw in pendingWrites) pw.buf.release()
        pendingWrites.clear()
        pendingBytes = 0
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
        // A stalled write (OP_WRITE re-arm) means the peer is not draining its receive
        // window — start the write-idle (slow-read) clock. Drain progress refreshes it
        // and a full drain cancels it, both via updatePendingBytes.
        armWriteIdleTimeout()
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

    /**
     * Suspends until all pending async flush operations complete.
     *
     * Returns immediately if no async flush is pending (`pendingWrites` is empty
     * on the EventLoop thread when the lambda executes). Dispatches the check
     * and registration to the EventLoop so they are atomic with the OP_WRITE
     * callback: if the flush already completed before the lambda runs, [cont] is
     * resumed immediately rather than stored, avoiding a TOCTOU deadlock.
     */
    override suspend fun awaitPendingFlush() {
        suspendCancellableCoroutine { cont ->
            val register = Runnable {
                when {
                    !opened -> cont.cancel()
                    pendingWrites.isEmpty() -> cont.resume(Unit)
                    else -> {
                        flushContinuation = cont
                        cont.invokeOnCancellation { flushContinuation = null }
                    }
                }
            }
            if (eventLoop.inEventLoop()) {
                register.run()
            } else {
                eventLoop.dispatch(EmptyCoroutineContext, register)
            }
        }
    }

    private companion object {
        /**
         * Pool slots to register for this connection's read buffer size class.
         * Matches the allocator's default read-buffer pooling depth; the call
         * is idempotent so it is a no-op for the already-registered default.
         */
        const val READ_BUFFER_POOL_SLOTS = 16
    }
}
