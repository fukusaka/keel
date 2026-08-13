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
import java.nio.ByteBuffer
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

    override val inOwningContext: Boolean get() = eventLoop.inEventLoop()

    /** Read/write idle (no-progress) timeout for this connection; see [AbstractIoTransport]. */
    override val idleTimeoutMillis: Long = idleTimeoutMillis

    /** Backed by this EventLoop's per-loop [DeadlineScheduler] (EventLoop-confined). */
    override val eventLoopTimer: EventLoopTimer get() = eventLoop.deadlineScheduler

    // One-time guard for lazy pool-class registration (see [readBufferSize]).
    // Touched only on the EventLoop thread (the read path).
    private var readPoolRegistered = false

    // Reusable scratch array fed to [java.nio.channels.GatheringByteChannel.write]
    // by [flushGather]. Grown lazily (1.5x) via [ensureBbArrayCapacity] when
    // `pendingWrites` exceeds the current capacity. Avoids the per-flush
    // `Array<ByteBuffer>` allocation the old rebuild-per-call path required.
    // Slots beyond `count` may hold stale ByteBuffer references between flushes;
    // they are never read (the write call is bounded by `count`) and are
    // overwritten when needed. Counterpart of the readiness engines'
    // `writevPtrs` / `writevLens` primitive-array cache.
    private var bbArray: Array<ByteBuffer?> = arrayOfNulls(INITIAL_BB_ARRAY_CAPACITY)

    private fun ensureBbArrayCapacity(n: Int) {
        if (bbArray.size >= n) return
        val grown = maxOf(bbArray.size + (bbArray.size shr 1), n)
        bbArray = arrayOfNulls(grown)
    }

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

    // Flow-control pause ([pauseReads]): when set, [armRead] becomes a
    // no-op and [onReadable] returns without consuming, so OP_READ stays
    // deregistered (interest is one-shot), kernel `rcvbuf` retains the
    // bytes, and the peer's TCP window stalls — regardless of
    // [idleReadPolicy]. EventLoop-thread confined like the rest of the
    // read bookkeeping.
    private var readPaused = false

    override fun pauseReads() {
        readPaused = true
    }

    override fun resumeReads() {
        readPaused = false
        // Restore the policy's steady state: DETECT keeps the primitive
        // armed at all times; PRESERVE arms only while reads are enabled.
        if (socketChannel.isOpen &&
            (idleReadPolicy == IdleReadPolicy.DETECT_PEER_CLOSE || readEnabled)
        ) {
            armRead()
        }
    }

    private fun armRead() {
        if (readPaused) return
        if (!socketChannel.isOpen) return
        eventLoop.setInterestCallback(
            selectionKey,
            SelectionKey.OP_READ,
            Runnable { onReadable() },
        )
    }

    private fun onReadable() {
        if (!socketChannel.isOpen) return
        if (readPaused) {
            // Paused after the interest was registered: do not consume and
            // do not re-arm. The one-shot interest is already cleared by
            // the selector loop, rcvbuf retains the data, and
            // [resumeReads] re-arms.
            return
        }
        if (!readPoolRegistered) {
            // Idempotent; on the EventLoop thread that owns the allocator.
            // No-op for the engine-default size already pooled by the
            // allocator child, and for pool-less allocators.
            allocator.hintSizeClass(readBufferSize, READ_BUFFER_HINT_COUNT)
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

    /**
     * Sends FIN on the EventLoop thread, like [close] does.
     *
     * The half-close reads `pendingWrites` to decide whether the FIN has to
     * wait for buffered output, and both that queue and the `outputShutdown`
     * guard are EventLoop-confined — so the decision belongs on the loop even
     * though [java.nio.channels.SocketChannel.shutdownOutput] itself is
     * thread-safe.
     *
     * Idempotent, and safe to call from any thread. The FIN is sent
     * asynchronously when the caller is off-loop, and after any buffered
     * writes have drained.
     */
    override fun shutdownOutput() {
        if (eventLoop.inEventLoop()) {
            shutdownOutputOwned()
        } else {
            eventLoop.dispatch(EmptyCoroutineContext, Runnable { shutdownOutputOwned() })
        }
    }

    override fun sendFin() {
        if (socketChannel.isOpen) socketChannel.shutdownOutput()
    }

    // --- Write path ---

    /**
     * True while a [performFlush] runnable is already queued to run on the
     * next [NioEventLoop.dispatch] tick. Subsequent [flush] calls that arrive
     * before that tick fires just leave their bytes in [pendingWrites]; the
     * scheduled tick drains everything through [flushGather] in a single
     * `writev(2)`, so per-frame keel `requestFlush` calls collapse into one
     * gathered send instead of one `SocketChannel.write` per frame.
     *
     * Only accessed on the EventLoop thread.
     */
    private var flushScheduled: Boolean = false

    /**
     * Schedules pending writes to be drained on the next [NioEventLoop.dispatch]
     * tick. If a scheduled tick is already pending ([flushScheduled] == true),
     * this call just leaves the bytes in [pendingWrites] — the pending tick
     * picks them up together, so per-frame `requestFlush` calls collapse into
     * one `flushGather` (`GatheringByteChannel.write` → `writev(2)`) call.
     *
     * SSE / chunked-streaming semantics are preserved: order is FIFO,
     * back-pressure via [pipeline.isWritable] + [awaitPendingFlush] still fires
     * on `pendingBytes` accumulation, and the per-frame delivery latency
     * increases by at most one EL tick (μs on loopback). Correctness is
     * verified by the SSE bench (`checks_succeeded = 100%` on the 100-frame
     * body-size check).
     *
     * @return always `false` because the actual write is deferred to the next
     *         EL tick; the write completion resumes any [awaitPendingFlush]
     *         waiter and invokes [onFlushComplete] from inside the scheduled
     *         runnable.
     */
    override fun flush(): Boolean {
        if (pendingWrites.isEmpty()) return true
        // Opt-out: bypass coalescing when the engine config disables it.
        // Each flush() drains synchronously through performFlush, matching
        // the pre-#897 immediate-send behaviour for latency-sensitive
        // workloads.
        if (!eventLoop.flushCoalescing) return performFlush()
        if (flushScheduled) return false
        flushScheduled = true
        val transport = this
        eventLoop.dispatch(
            EmptyCoroutineContext,
            Runnable {
                // No-op if the transport was torn down between scheduling and
                // this tick: [teardownOnEventLoop] already drained
                // `pendingWrites`, cleared `flushScheduled`, and released
                // buffers, so we would only fire `onFlushComplete` for an
                // already-closed channel here.
                if (!transport.opened) return@Runnable
                transport.flushScheduled = false
                val done = transport.performFlush()
                if (done && transport.pendingWrites.isEmpty()) {
                    transport.flushContinuation?.let { cont ->
                        transport.flushContinuation = null
                        cont.resume(Unit)
                    }
                    transport.onFlushComplete?.invoke()
                    transport.sendFinIfDrained()
                }
            },
        )
        return false
    }

    /**
     * Attempts to drain [pendingWrites] via [SocketChannel.write] (single write)
     * or [flushGather] (gather `writev(2)`). Called both by the deferred
     * runnable in [flush] and by [teardownOnEventLoop] to flush any
     * outstanding bytes before releasing them.
     *
     * @return `true` if all pending data was sent synchronously, `false` if
     *         the send buffer is full and an async OP_WRITE callback is
     *         pending (installed by [flushSingle] / [flushGather] via
     *         [registerWriteCallback]).
     */
    private fun performFlush(): Boolean {
        if (pendingWrites.isEmpty()) return true
        flushCount++
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
        // Drain any deferred flush before releasing pending writes: a
        // `close()` that races the `EventLoop.dispatch` scheduled by
        // [flush] would otherwise drop the buffered bytes on the floor.
        // This is exercised by e.g. `KeelWebSocketTest` where the server
        // handler `send`s a message and then returns (letting the pipeline
        // close the channel) all on the same EventLoop task — the
        // deferred flush task is still behind us in the queue when we
        // reach here.
        if (flushScheduled && pendingWrites.isNotEmpty()) {
            flushScheduled = false
            performFlush()
        }
        cancelIdleTimeout()
        cancelWriteIdleTimeout()
        for (pw in pendingWrites) pw.buf.release()
        pendingWrites.clear()
        pendingBytes = 0
        selectionKey.cancel()
        logTransportStatsOnClose(eventLoop.logger, "channel=$socketChannel")
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
                if (written > 0) partialWriteCount++
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
     * On partial write, fully-written buffers are released and the
     * partially-written entry is mutated in place at the head of the deque
     * (the trailing untouched entries stay as-is). Counterpart of
     * the readiness engines' `flushGather` — the cached [bbArray] scratch and
     * the in-place head-mutation pattern together eliminate the per-flush
     * `Array<ByteBuffer>` and (on partial) the `mutableListOf<PendingWrite>`
     * rebuild that the old code required, reducing the `PendingWrite`
     * allocation to one (only the partial entry).
     */
    private fun flushGather(): Boolean {
        val count = pendingWrites.size
        ensureBbArrayCapacity(count)
        var totalBytes = 0L
        for (i in 0 until count) {
            val pw = pendingWrites[i]
            val bb = pw.buf.unsafeBuffer.duplicate()
            bb.position(pw.offset)
            bb.limit(pw.offset + pw.length)
            bbArray[i] = bb
            totalBytes += pw.length.toLong()
        }
        val written = socketChannel.write(bbArray, 0, count)

        if (written >= totalBytes) {
            for (pw in pendingWrites) pw.buf.release()
            pendingWrites.clear()
            updatePendingBytes(-totalBytes.toInt())
            return true
        }

        // Send buffer full or partial write. Drain fully-written entries
        // from the head of the deque, mutate the partially-written entry in
        // place at the head, leave the rest. Skips the re-enqueue alloc
        // when `written == 0L` (nothing changed).
        if (written == 0L) {
            registerWriteCallback()
            return false
        }
        partialWriteCount++
        var consumed = 0L
        while (pendingWrites.isNotEmpty()) {
            val pw = pendingWrites.first()
            if (consumed + pw.length <= written) {
                consumed += pw.length.toLong()
                pw.buf.release()
                pendingWrites.removeFirst()
            } else {
                val alreadyWritten = (written - consumed).toInt()
                pendingWrites[0] = PendingWrite(pw.buf, pw.offset + alreadyWritten, pw.length - alreadyWritten)
                break
            }
        }
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
                    sendFinIfDrained()
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
                        // Mirror of the epoll defer eager-run: when a caller reaches
                        // this branch, they are about to suspend and pay for a full EL
                        // tick before the coalesced flush drains. Run the deferred
                        // flush inline so the caller wakes on this dispatch instead of
                        // the next one. The `flush()` deferral path is unchanged and
                        // still coalesces SSE-style rapid emits when no one awaits.
                        if (flushScheduled) {
                            flushScheduled = false
                            val done = performFlush()
                            if (done && pendingWrites.isEmpty()) {
                                sendFinIfDrained()
                                cont.resume(Unit)
                                return@Runnable
                            }
                        }
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
         * `maxCount` hint for the read-buffer size class — passed to
         * [BufferAllocator.hintSizeClass] at bind time. Matches the
         * allocator's default read-buffer pooling depth; the hint is
         * a best-effort no-op for the already-registered default and
         * for allocators that do not structure memory by size class.
         */
        const val READ_BUFFER_HINT_COUNT = 16

        /**
         * Initial size of the reusable `bbArray` scratch. Sized for this
         * engine alone — the readiness engines' gather scratch starts larger,
         * and nothing keeps the two in step; covers the steady-state
         * pendingWrites depth without
         * triggering [ensureBbArrayCapacity] in common workloads.
         */
        const val INITIAL_BB_ARRAY_CAPACITY = 8
    }
}
