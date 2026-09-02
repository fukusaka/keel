@file:OptIn(UnsafeIoBufApi::class)

package io.github.fukusaka.keel.engine.netty

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.UnsafeIoBufApi
import io.github.fukusaka.keel.buf.unsafeBuffer
import io.github.fukusaka.keel.core.IdleReadPolicy
import io.github.fukusaka.keel.pipeline.AbstractIoTransport
import io.github.fukusaka.keel.pipeline.AbstractIoTransport.PendingWrite
import io.github.fukusaka.keel.pipeline.EventLoopTimer
import io.github.fukusaka.keel.pipeline.PendingWriteSnapshotPool
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.EventLoop
import io.netty.channel.socket.ChannelInputShutdownEvent
import io.netty.channel.socket.ChannelInputShutdownReadComplete
import io.netty.channel.socket.DuplexChannel
import io.netty.handler.ssl.SslContext
import io.netty.util.concurrent.Future
import io.netty.util.concurrent.GenericFutureListener
import kotlin.coroutines.resume
import io.netty.channel.Channel as NettyNativeChannel

/**
 * Non-suspend [IoTransport] for Netty pipeline channels.
 *
 * Handles both read and write paths for Netty channels.
 *
 * **Read path**: Netty delivers data asynchronously via [channelRead]
 * before the user provides a buffer. When the inbound [ByteBuf] has a
 * single NIO backing (`nioBufferCount() == 1`), the transport wraps
 * it via [NettyByteBufIoBuf.borrowInbound] — zero copy + engine-direct,
 * pooled (mirrors the engine-direct `DispatchDataIoBuf` path on the
 * NWConnection engine). Pool pressure is shifted to Netty's arena.
 * Composite buffers (`nioBufferCount() > 1`) fall back to the
 * allocate-and-copy path.
 *
 * **auto-read**: Pipeline mode uses `autoRead = true` (Netty delivers data
 * continuously). Coroutine mode starts with `autoRead = false` and switches
 * to `autoRead = true` when [readEnabled] is set to start the read loop.
 *
 * **Write path**: batches all pending writes into Netty's outbound buffer
 * via [write][NettyNativeChannel.write], then issues a single flush on the
 * last write. The last write's [ChannelFuture] listener releases buffers
 * and invokes [onFlushComplete]. [flush] also stores the future in
 * [lastFlushFuture] for use by [awaitPendingFlush].
 *
 * **Buffer lifecycle**: `write()` retains the buffer. The flush completion
 * callback releases all buffers after Netty finishes sending.
 *
 * **Thread model**: [channelRead], [channelInactive], and [exceptionCaught]
 * run on Netty's worker [EventLoop] thread. Pipeline handlers execute
 * synchronously on the same thread. The flush completion callback runs
 * on the same EventLoop.
 *
 * [ioDispatcher] is a [NettyEventLoopDispatcher] bound to this channel's
 * [EventLoop], so coroutine-side `withContext(ioDispatcher)` hops (e.g.
 * `PipelinedChannel.read` / `write` / `flush`) resume on the same thread
 * Netty uses for inbound callbacks. This satisfies
 * [io.github.fukusaka.keel.pipeline.SuspendBridgeHandler]'s documented
 * single-thread invariant the same way the EventLoop-based Native engines
 * (epoll / kqueue / io_uring) already do.
 *
 * **awaitPendingFlush**: [flush] stores its `lastFuture` in [lastFlushFuture].
 * [awaitPendingFlush] suspends on that future so callers (e.g.
 * [io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel.awaitFlushComplete])
 * block until the write reaches the TCP send buffer. Without this, the HTTP
 * response terminator (`0\r\n\r\n`) can be queued in Netty's outbound pipeline
 * while the connection handler already moves to the next request, causing
 * truncated SSE / chunked responses under concurrent load.
 *
 * @param nettyChannel The underlying Netty channel.
 * @param allocator    Buffer allocator for read operations.
 */
internal class NettyIoTransport(
    internal val nettyChannel: NettyNativeChannel,
    allocator: BufferAllocator,
    private val idleReadPolicy: IdleReadPolicy,
    idleTimeoutMillis: Long = 0,
    private val flushCoalescing: Boolean = true,
) : AbstractIoTransport(allocator) {

    override val ioDispatcher: CoroutineDispatcher = NettyEventLoopDispatcher(nettyChannel.eventLoop())

    override val inOwningContext: Boolean get() = nettyChannel.eventLoop().inEventLoop()

    /** Read/write idle (no-progress) timeout for this connection; see [AbstractIoTransport]. */
    override val idleTimeoutMillis: Long = idleTimeoutMillis

    /**
     * Backed by this channel's Netty [EventLoop] via [NettyEventLoopTimer]. The
     * timer fires inline on the same EventLoop thread that drives [channelRead] /
     * [flush], so no cross-thread hand-off is needed to close an idle connection.
     */
    override val eventLoopTimer: EventLoopTimer = NettyEventLoopTimer(nettyChannel.eventLoop())

    // --- Read path ---

    /**
     * [IdleReadPolicy.DETECT_PEER_CLOSE]: enable Netty's auto-read here
     * so the underlying Java NIO `Selector` keeps `OP_READ` registered.
     * Arming runs *after* `AbstractPipelinedChannel.init` has wired up
     * [onRead] / [onReadClosed], so the first [channelRead] always
     * observes non-null callbacks; arming earlier in `init { }` races
     * with the channel-construction sequence and can leak bytes
     * through a still-null [onRead] when Netty's EventLoop fires
     * channelRead before `AbstractPipelinedChannel.init` finishes.
     *
     * Engine applicability is resolved upstream in [NettyEngine]: for
     * the [NettyTransport.Epoll] / [NettyTransport.KQueue] native
     * transports the engine passes [IdleReadPolicy.PRESERVE_BACKPRESSURE]
     * regardless of the user's [IoEngineConfig.idleReadPolicy], because
     * native transports observe peer FIN through `EPOLLRDHUP` /
     * `EV_EOF` independently of auto-read state.
     */
    override fun onChannelAttached() {
        if (idleReadPolicy == IdleReadPolicy.DETECT_PEER_CLOSE) {
            armRead()
        }
    }

    // Guarded by EventLoop thread — both channelInactive and userEventTriggered
    // run there, so a plain var suffices.
    private var readClosedFired = false

    private fun fireReadClosed() {
        if (!readClosedFired) {
            readClosedFired = true
            onReadClosed?.invoke()
        }
    }

    override var readEnabled: Boolean = false
        set(value) {
            field = value
            // [IdleReadPolicy.DETECT_PEER_CLOSE]: auto-read is already
            // armed from construction and stays armed for the lifetime
            // of the transport. [channelRead] always delivers to [onRead]
            // in both modes (pre-attach journal closed the old data-drop
            // caveat), so under this policy flipping `readEnabled = false`
            // does NOT stop inbound delivery — kernel back-pressure needs
            // [IdleReadPolicy.PRESERVE_BACKPRESSURE].
            if (value && opened) {
                // The connection is now waiting to read → the read-side idle timeout
                // applies (covers accept-to-first-byte, slowloris-silent, keep-alive
                // idle); policy-independent.
                armIdleTimeout()
                if (idleReadPolicy == IdleReadPolicy.PRESERVE_BACKPRESSURE) armRead()
            } else if (!value) {
                cancelIdleTimeout() // back-pressure: pause the read-idle timeout
            }
        }

    // Flow-control pause ([pauseReads]): turning auto-read off stops
    // Netty's read loop at the next iteration regardless of
    // [idleReadPolicy], so the kernel `rcvbuf` fills and the peer's TCP
    // window stalls. Deliveries already read by Netty when the pause
    // lands still reach [channelRead] — the bounded overshoot the
    // contract allows. EventLoop-thread confined.
    private var readPaused = false

    override fun pauseReads() {
        readPaused = true
        if (nettyChannel.isOpen) nettyChannel.config().isAutoRead = false
    }

    override fun resumeReads() {
        readPaused = false
        // Restore the policy's steady state: DETECT keeps auto-read on at
        // all times; PRESERVE arms only while reads are enabled.
        if (nettyChannel.isOpen &&
            (idleReadPolicy == IdleReadPolicy.DETECT_PEER_CLOSE || readEnabled)
        ) {
            armRead()
        }
    }

    /**
     * Enables auto-read on the Netty channel to start the read loop.
     *
     * Once enabled, Netty continuously delivers data via [channelRead]
     * callbacks in the [handler]. A no-op while [readPaused] — the
     * flow-control pause owns auto-read until [resumeReads].
     */
    private fun armRead() {
        if (readPaused) return
        if (!nettyChannel.isOpen) return
        nettyChannel.config().isAutoRead = true
        // Trigger initial read in case data arrived before auto-read was enabled.
        nettyChannel.read()
    }

    /**
     * Netty [ChannelInboundHandlerAdapter] that bridges push events to
     * the keel [IoTransport] callbacks.
     *
     * **Zero-copy path**: when the inbound [ByteBuf] is backed by a
     * single NIO [ByteBuffer] (`nioBufferCount() == 1`), the transport
     * wraps it directly via [NettyByteBufIoBuf.borrowInbound]
     * (engine-direct, pooled wrapper). Ownership of the Netty [ByteBuf]
     * is transferred to the wrapping `IoBuf`; the pooled buffer is
     * returned to Netty's arena when the keel pipeline releases the
     * `IoBuf`. No memory copy occurs.
     *
     * **Copy fallback**: composite buffers (`nioBufferCount() > 1`) fall
     * back to allocating a keel [IoBuf] from the configured [allocator]
     * (typically [NettyByteBufAllocator] returning [NettyByteBufIoBuf])
     * and copying into it via [ByteBuf.getBytes]. The allocation size is
     * rounded up to [POOL_FRIENDLY_CAPACITY] so the underlying pool
     * freelist can serve it.
     */
    internal val handler = object : ChannelInboundHandlerAdapter() {
        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            val byteBuf = msg as ByteBuf
            if (!byteBuf.isReadable) {
                byteBuf.release()
                return
            }

            // Always deliver via [onRead] in both modes. In
            // [IdleReadPolicy.PRESERVE_BACKPRESSURE] auto-read is only
            // enabled when `readEnabled = true`, so this branch only
            // runs with `readEnabled = true`. In
            // [IdleReadPolicy.DETECT_PEER_CLOSE] auto-read stays
            // enabled regardless of `readEnabled`; bytes that arrive
            // while no user [InboundHandler] is installed are absorbed
            // by `DefaultPipeline`'s pre-attach event journal and
            // replayed when the first user handler is added — this
            // trades engine-level data dropping for pipeline-level
            // buffering, closing the data-loss caveat that
            // DETECT_PEER_CLOSE previously documented.

            val readable = byteBuf.readableBytes()
            touchIdleTimeout() // progress: refresh the read-idle deadline
            if (byteBuf.nioBufferCount() == 1) {
                // Engine-direct zero-copy wrap: ownership of byteBuf
                // transfers to the wrapper; refcount-zero release frees
                // the pooled ByteBuf inline and returns this wrapper
                // object to NettyByteBufIoBuf's own Recycler pool
                // (borrowInbound, not wrapInbound — avoids a fresh
                // wrapper allocation per receive). Forward the
                // allocator's BufferAllocatorLifecycleListener so this
                // inbound engine-direct IoBuf fires the same onAllocated /
                // onReleased channel as the write-side allocate() path
                // (pluggability item 12 B2.5 step 2).
                val buf = NettyByteBufIoBuf.borrowInbound(byteBuf, allocator.lifecycleListener)
                try {
                    onRead?.invoke(buf)
                } catch (t: Throwable) {
                    // onRead did not take ownership (threw before release);
                    // ensure the Netty ref is not leaked.
                    buf.release()
                    throw t
                }
            } else {
                try {
                    val cap = maxOf(readable, POOL_FRIENDLY_CAPACITY)
                    val buf = allocator.allocate(cap)
                    val bb = buf.unsafeBuffer
                    bb.position(buf.writerIndex)
                    bb.limit(buf.writerIndex + readable)
                    byteBuf.getBytes(byteBuf.readerIndex(), bb)
                    buf.writerIndex += readable
                    onRead?.invoke(buf)
                } finally {
                    byteBuf.release()
                }
            }
        }

        override fun channelReadComplete(ctx: ChannelHandlerContext) {
            // Netty's own batch boundary, which is exactly what this signal
            // is: the read loop for this wake has finished handing over what
            // it had.
            onReadComplete?.invoke()
            ctx.fireChannelReadComplete()
        }

        override fun channelInactive(ctx: ChannelHandlerContext) {
            fireReadClosed()
        }

        /**
         * Fires read-closed when the inbound half of a half-closed connection
         * has been fully drained. With [ChannelOption.ALLOW_HALF_CLOSURE] = true
         * on server channels, the peer's TCP FIN triggers two related events
         * depending on the underlying [NettyTransport]:
         *
         * - **Native transport** ([NettyTransport.Epoll] / [NettyTransport.KQueue]):
         *   [ChannelInputShutdownEvent] fires the moment kernel-level
         *   `EPOLLRDHUP` / `EV_EOF` is observed, *regardless* of the
         *   `setAutoRead(false)` state. This is the path that lets keel
         *   surface peer FIN to write-only push clients
         *   (`PipelinedChannel.readEnabled = false`) without waiting on
         *   `SO_KEEPALIVE`.
         * - **NIO transport** ([NettyTransport.Nio]):
         *   [ChannelInputShutdownReadComplete] fires after all buffered
         *   data has been delivered via [channelRead], so body bytes are
         *   not lost before the bridge pump consumes them. NIO requires
         *   `setAutoRead(true)` for these events to fire at all (Java NIO
         *   `Selector` only delivers `POLLIN`-derived events, never
         *   `POLLRDHUP`); see [NettyTransport] KDoc for the
         *   `translateInterestOps` constraint.
         *
         * Both events route to [fireReadClosed], which is idempotent.
         */
        override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
            when (evt) {
                is ChannelInputShutdownEvent -> fireReadClosed()
                is ChannelInputShutdownReadComplete -> fireReadClosed()
            }
            ctx.fireUserEventTriggered(evt)
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            fireReadClosed()
            ctx.close()
        }
    }

    // --- Netty SslHandler integration ---

    /**
     * Installs Netty's [SslHandler][io.netty.handler.ssl.SslHandler] in the
     * Netty pipeline before the keel handler.
     *
     * Decryption happens at the Netty transport level — the keel pipeline
     * receives plaintext. No keel TlsHandler is needed.
     *
     * Must be called before [readEnabled] = true to ensure the SslHandler
     * processes the TLS handshake before data delivery.
     */
    fun installSslHandler(sslContext: SslContext) {
        check(opened) { "Transport is closed" }
        val engine = sslContext.newEngine(nettyChannel.alloc())
        nettyChannel.pipeline().addFirst("ssl", io.netty.handler.ssl.SslHandler(engine))
    }

    // --- Lifecycle ---

    /**
     * Sends TCP FIN to the peer via Netty's [DuplexChannel.shutdownOutput],
     * on the channel's EventLoop like [close] does.
     *
     * The half-close inspects `pendingWrites` and the in-flight flush future
     * to decide whether the FIN has to wait for buffered output, and both are
     * EventLoop-confined. Waiting matters more here than on the POSIX
     * engines: Netty's own `shutdownOutput` fails everything still in its
     * `ChannelOutboundBuffer` with `ChannelOutputShutdownException`, so a FIN
     * that overtakes a flush discards those bytes rather than queueing them.
     *
     * Idempotent, and safe to call from any thread. The FIN is sent
     * asynchronously when the caller is off-loop, and after any buffered
     * writes have reached the OS send buffer.
     */
    override fun shutdownOutput() {
        val loop = nettyChannel.eventLoop()
        if (loop.inEventLoop()) {
            shutdownOutputOwned()
        } else {
            loop.execute { shutdownOutputOwned() }
        }
    }

    /**
     * [flush] hands its buffers to Netty and clears [pendingWrites] straight
     * away, so the queue emptying says nothing about the bytes having left —
     * [lastFlushFuture] is what completes when they reach the OS send buffer.
     *
     * Only the most recent future is tracked, which is sufficient because
     * `ChannelOutboundBuffer` is a singly-linked FIFO whose `remove()`
     * completes the head entry's promise: a later write's future cannot
     * complete before an earlier one's. [awaitPendingFlush] already relies on
     * the same ordering.
     */
    override val outputDrained: Boolean
        get() = pendingWrites.isEmpty() && lastFlushFuture.let { it == null || it.isDone }

    override fun sendFin() {
        if (nettyChannel is DuplexChannel) {
            nettyChannel.shutdownOutput()
        }
    }

    // --- Write path ---

    /**
     * The [ChannelFuture] returned by the most recent `write()` in [flush].
     * Read by [awaitPendingFlush] so callers block until the last queued
     * message reaches the OS TCP send buffer.
     * Only accessed on the EventLoop thread.
     */
    private var lastFlushFuture: ChannelFuture? = null

    /**
     * True while a `Channel.flush()` is already scheduled to run on the next
     * `EventLoop.execute` iteration. Additional [flush] calls that arrive
     * before that iteration fires only queue their bytes via `Channel.write`
     * — the scheduled flush picks them up together, so per-frame keel
     * `requestFlush` calls collapse into one Netty `doWrite()` pass and
     * therefore one gathered `writev(2)` on the socket (instead of one
     * `writeAndFlush` → `doWrite` per frame).
     *
     * Only accessed on the EventLoop thread.
     */
    private var flushScheduled: Boolean = false

    /**
     * Reuses the `ArrayList<PendingWrite>` ownership-snapshot [flush] takes
     * before handing it to Netty's async completion listener, instead of
     * allocating a fresh list on every call. See
     * [PendingWriteSnapshotPool]'s KDoc for why a fixed-size double-buffer
     * isn't safe here (multiple flush generations can be in flight under
     * backpressure). Only accessed on the EventLoop thread.
     */
    private val pendingWriteSnapshotPool = PendingWriteSnapshotPool()

    /**
     * Reusable [GenericFutureListener] for [flush]'s write-completion
     * callback, borrowed from [flushListenerPool] instead of a fresh Kotlin
     * lambda per call. A lambda literal that captures `writes`/`totalBytes`/
     * `callback` (all of which differ per flush) cannot be a singleton — the
     * Kotlin compiler allocates a new closure object on every `addListener {
     * ... }` call. Holding that state in mutable fields instead lets the
     * listener object itself be pooled.
     *
     * Not a fixed-size pool, for the same reason [pendingWriteSnapshotPool]
     * isn't: under backpressure (a slow peer), multiple `flush()`
     * generations can have listeners outstanding simultaneously — the
     * earlier one's [ChannelFuture] hasn't completed when a later `flush()`
     * borrows another. [flushListenerPool] only accessed on the EventLoop
     * thread, matching [pendingWriteSnapshotPool].
     */
    private inner class FlushCompletionListener : GenericFutureListener<Future<Void>> {
        lateinit var writes: ArrayList<PendingWrite>
        var totalBytes: Int = 0
        var callback: (() -> Unit)? = null

        override fun operationComplete(future: Future<Void>) {
            for (pw in writes) pw.buf.release()
            updatePendingBytes(-totalBytes)
            pendingWriteSnapshotPool.recycle(writes)
            flushListenerPool.addLast(this)
            // Clear before invoking: matches pendingWriteSnapshotPool.recycle's
            // discipline of dropping references before an instance re-enters
            // the free list, so a recycled-but-idle listener doesn't keep
            // whatever `callback` closes over reachable for the rest of the
            // transport's lifetime. Captured to a local first so a reentrant
            // flush() borrowing this same instance during the invoke() below
            // (setting a fresh `callback`) can't be undone by this clear.
            val cb = callback
            callback = null
            cb?.invoke()
            sendFinIfDrained()
        }
    }

    private val flushListenerPool = ArrayDeque<FlushCompletionListener>()

    /**
     * Returns a [FlushCompletionListener] (reused from [flushListenerPool],
     * or freshly allocated if none is free) populated with this flush
     * cycle's state.
     */
    private fun borrowFlushListener(
        writes: ArrayList<PendingWrite>,
        totalBytes: Int,
        callback: (() -> Unit)?,
    ): FlushCompletionListener {
        val listener = flushListenerPool.removeLastOrNull() ?: FlushCompletionListener()
        listener.writes = writes
        listener.totalBytes = totalBytes
        listener.callback = callback
        return listener
    }

    /**
     * Queues all pending writes into Netty's outbound buffer via
     * [write][NettyNativeChannel.write] and schedules a single
     * `Channel.flush()` on the next `EventLoop.execute` iteration. If a
     * flush is already scheduled (from an earlier call in the same tick),
     * this call just adds to the outbound buffer — the pending scheduled
     * flush drains everything in one pass through `ChannelOutboundBuffer`,
     * which Netty turns into a single gathered `writev(2)` send.
     *
     * The per-write [ChannelFuture] returned by `write()` still completes
     * only after the deferred flush has actually pushed the bytes to the
     * wire, so the buffer-release listener and [awaitPendingFlush] retain
     * their original semantics.
     *
     * @return always `false` because Netty writes are asynchronous.
     */
    override fun flush(): Boolean {
        val size = pendingWrites.size
        if (size == 0) return true

        // Transfer ownership for release in callback. Borrowed from
        // pendingWriteSnapshotPool instead of a fresh ArrayList — recycled
        // back once this snapshot's last reference (the sync release path
        // below, the async listener, or the catch block) is done with it.
        val writes = pendingWriteSnapshotPool.borrow(pendingWrites)
        pendingWrites.clear()
        val totalBytes = writes.sumOf { it.length }

        val callback = onFlushComplete
        try {
            var lastFuture: ChannelFuture? = null
            for (pw in writes) {
                val buf = pw.buf
                val nettyBuf: ByteBuf = if (buf is NettyByteBufIoBuf) {
                    // Zero-wrap path: keel IoBuf is directly a Netty ByteBuf.
                    // Hand the underlying ByteBuf to Netty with retained slice
                    // so the flush listener's buf.release() still matches one
                    // retain (ByteBuf refCnt dropped by Netty after send).
                    buf.byteBuf.retainedSlice(pw.offset, pw.length)
                } else {
                    val bb = buf.unsafeBuffer.duplicate()
                    bb.position(pw.offset)
                    bb.limit(pw.offset + pw.length)
                    Unpooled.wrappedBuffer(bb)
                }
                // With `flushCoalescing = true` (default), write() only queues
                // into ChannelOutboundBuffer's unflushed segment; the deferred
                // flush() scheduled below promotes them together. With
                // `flushCoalescing = false`, opt-out to immediate send by using
                // writeAndFlush on the last buffer (Netty's pre-#896 pattern).
                lastFuture = if (flushCoalescing || pw !== writes.last()) {
                    nettyChannel.write(nettyBuf)
                } else {
                    nettyChannel.writeAndFlush(nettyBuf)
                }
            }

            if (flushCoalescing && !flushScheduled) {
                flushScheduled = true
                val transport = this
                nettyChannel.eventLoop().execute {
                    transport.flushScheduled = false
                    transport.nettyChannel.flush()
                }
            }

            if (lastFuture != null) {
                lastFlushFuture = lastFuture
                // If the write did not complete synchronously, bytes are sitting
                // in Netty's outbound buffer because the peer's receive window is
                // full (slow-read) — start the write-idle clock. The listener's
                // updatePendingBytes drains it: a full drain (pendingBytes == 0)
                // cancels the timer, a partial drain refreshes it. A synchronously
                // completed write (fast peer) skips arming entirely.
                if (!lastFuture.isDone) armWriteIdleTimeout()
                lastFuture.addListener(borrowFlushListener(writes, totalBytes, callback))
            } else {
                for (pw in writes) pw.buf.release()
                updatePendingBytes(-totalBytes)
                pendingWriteSnapshotPool.recycle(writes)
            }
        } catch (e: Exception) {
            // Release all buffers on write failure (e.g. channel already closed).
            for (pw in writes) pw.buf.release()
            updatePendingBytes(-totalBytes)
            pendingWriteSnapshotPool.recycle(writes)
            throw e
        }

        return false // Always async.
    }

    /**
     * Suspends until the [ChannelFuture] from the most recent [writeAndFlush]
     * completes, ensuring the written bytes have been handed off to the OS TCP
     * send buffer. Returns immediately if no flush has been issued yet or the
     * future is already done.
     *
     * Called by [io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel.awaitFlushComplete]
     * to give streaming response senders (e.g. SSE, chunked transfer) a way to
     * confirm the final `0\r\n\r\n` terminator has left the Netty pipeline
     * before the connection is reused for the next request.
     */
    override suspend fun awaitPendingFlush() {
        val future = lastFlushFuture ?: return
        if (future.isDone) return
        suspendCancellableCoroutine { cont ->
            future.addListener { cont.resume(Unit) }
        }
    }

    /**
     * Releases all pending write buffers and closes the Netty channel.
     * Unsent data is discarded. Idempotent and thread-safe.
     *
     * A non-EventLoop caller dispatches the teardown onto the Netty
     * channel's EventLoop so the `pendingWrites` / `pendingBytes`
     * mutations stay serialised with the read / write / flush paths.
     * Concurrent callers may both enqueue a teardown; the re-check
     * inside [teardownOnEventLoop] keeps the cleanup idempotent.
     */
    override fun close() {
        if (!markClosing()) return
        val loop = nettyChannel.eventLoop()
        if (loop.inEventLoop()) {
            teardownOnEventLoop()
        } else {
            loop.execute { teardownOnEventLoop() }
        }
    }

    private fun teardownOnEventLoop() {
        if (!markTeardownStarted()) return
        cancelIdleTimeout()
        cancelWriteIdleTimeout()
        for (pw in pendingWrites) pw.buf.release()
        pendingWrites.clear()
        pendingBytes = 0
        // Async close — do not call sync() to avoid EventLoop deadlock.
        nettyChannel.close()
    }

    /**
     * Suspends until the Netty channel is fully closed.
     * Returns immediately if already closed.
     */
    override suspend fun awaitClosed() {
        if (!nettyChannel.isOpen) return
        suspendCancellableCoroutine { cont ->
            nettyChannel.closeFuture().addListener {
                cont.resume(Unit)
            }
        }
    }

    companion object {
        /**
         * Preferred minimum capacity for inbound read buffers, chosen to
         * match [io.github.fukusaka.keel.buf.PooledDirectAllocator]'s default
         * freelist slot size (8 KiB).
         *
         * Netty delivers inbound data in packets whose size tracks the TCP
         * segment / TLS record boundary and is typically smaller than the
         * pool slot. Requesting less than the slot size forces the allocator
         * to skip the freelist and allocate a fresh `DirectByteBuffer` on
         * every read, producing a `Cleaner` + `Deallocator` pair per packet.
         * Rounding the request up to this size lets small packets hit the
         * freelist; larger packets still bypass the pool as before.
         *
         * This is a hint, not a contract: if the allocator later exposes a
         * different preferred capacity (for example, a size-class pool),
         * this constant can be adjusted independently of the allocator
         * implementation.
         */
        private const val POOL_FRIENDLY_CAPACITY = 8192
    }
}
