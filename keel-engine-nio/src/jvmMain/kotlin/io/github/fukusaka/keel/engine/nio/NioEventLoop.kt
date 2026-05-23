package io.github.fukusaka.keel.engine.nio

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.warn
import io.github.fukusaka.keel.pipeline.IoTransport
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import java.nio.channels.SelectableChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume

/**
 * Single-threaded NIO event loop for JVM, also serving as a [CoroutineDispatcher].
 *
 * Drives all I/O for channels assigned to this EventLoop. A dedicated daemon
 * thread runs [loop], interleaving three tasks:
 * 1. Execute queued coroutine continuations ([taskQueue])
 * 2. Process pending channel registrations ([pendingRegistrations])
 * 3. Call [Selector.select] to wait for channel readiness events
 *
 * **SelectionKey caching**: Channels are registered with the Selector once
 * at creation (via [registerChannel]) with `interestOps=0`. Subsequent I/O
 * operations use [setInterest] to toggle interest ops without re-registering.
 * This avoids the per-read JNI overhead of `channel.register()` and
 * `key.cancel()` that caused the regression observed when first migrating
 * from blocking SocketChannel to non-blocking + Selector EventLoop.
 *
 * **CoroutineDispatcher integration**: By extending [CoroutineDispatcher],
 * coroutines launched on this EventLoop execute entirely on the EventLoop
 * thread. When `cont.resume()` is called, the continuation is dispatched
 * back to this same thread via [dispatch], eliminating cross-thread
 * dispatch overhead.
 *
 * ```
 * EventLoop thread (single loop iteration):
 *   1. drainTasks()          — run coroutine continuations
 *   2. drainRegistrations()  — channel.register(selector, 0) for new channels
 *   3. selector.select()     — block until events or wakeup
 *   4. processSelectedKeys() — interestOps(0) + cont.resume(Unit)
 * ```
 */
internal class NioEventLoop(
    name: String,
    private val logger: Logger,
    /**
     * Per-EventLoop [BufferAllocator] instance. Co-located with the loop
     * (rather than tracked separately in [NioEventLoopGroup]) so callers
     * receive the allocator-loop pair as a single object — eliminating the
     * `Pair<EventLoop, BufferAllocator>` allocation that the previous
     * `EventLoopGroup.next()` API created on every accept. Default is
     * [DefaultAllocator] for boss / test loops that do not perform reads
     * and therefore never invoke the allocator.
     */
    val allocator: BufferAllocator = DefaultAllocator,
    /**
     * Engine-wide default read buffer size
     * ([io.github.fukusaka.keel.core.IoEngineConfig.readBufferSize]) for
     * connections on this loop. Used as the fallback when a connection's
     * [io.github.fukusaka.keel.core.BindConfig.readBufferSize] /
     * [io.github.fukusaka.keel.core.ConnectConfig.readBufferSize] is `null`;
     * the effective size is captured per connection on the transport.
     */
    val readBufferSize: Int = IoTransport.DEFAULT_READ_BUFFER_SIZE,
) : CoroutineDispatcher() {

    internal val selector: Selector = Selector.open()
    private val regLock = Any()
    private val pendingRegistrations = mutableListOf<ChannelRegistration>()
    private val taskQueue = ConcurrentLinkedQueue<Runnable>()

    @Volatile
    private var running = true
    private val thread: Thread

    /**
     * A pending initial channel registration.
     *
     * The channel will be registered with `interestOps=0` (no interest)
     * on the EventLoop thread. The resulting [SelectionKey] is delivered
     * via [continuation].
     */
    class ChannelRegistration(
        val channel: SelectableChannel,
        val continuation: CancellableContinuation<SelectionKey>,
    )

    init {
        thread = Thread({ loop() }, name).apply {
            isDaemon = true
            start()
        }
    }

    /** Returns true if the current thread is this EventLoop's thread. */
    fun inEventLoop(): Boolean = Thread.currentThread() == thread

    /**
     * Verifies the caller is running on the EventLoop thread.
     *
     * Used as a contract on private helpers that mutate state owned by the
     * EventLoop ([KeyCallbacks] fields, [SelectionKey.interestOps]) without
     * any other synchronisation. The public entry points funnel cross-thread
     * callers through [dispatch] so that by the time the inner helper runs
     * the assertion always holds. Matches the pattern established in
     * `EpollEventLoop.assertInEventLoop` / `KqueueEventLoop.assertInEventLoop`.
     */
    internal fun assertInEventLoop(operation: String) {
        check(inEventLoop()) {
            "$operation must run on the EventLoop thread"
        }
    }

    // --- CoroutineDispatcher ---

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        taskQueue.add(block)
        // Skip wakeup when already on the EventLoop thread — the loop
        // will drain tasks before the next select(). Selector.wakeup()
        // is a pipe write syscall; avoiding it on the hot path (coroutine
        // resume on the same thread) eliminates unnecessary overhead.
        if (!inEventLoop()) {
            selector.wakeup()
        }
    }

    // --- Channel registration (one-time) ---

    /**
     * Registers a channel with this EventLoop's Selector (one-time).
     *
     * The channel is registered with `interestOps=0` (no interest).
     * Subsequent I/O operations use [setInterest] to toggle interest ops
     * without re-registering. This must be called once per channel at
     * creation time.
     *
     * Registration is queued and executed on the EventLoop thread because
     * `channel.register()` blocks if `select()` is in progress.
     *
     * @return The cached [SelectionKey] for use with [setInterest].
     */
    suspend fun registerChannel(channel: SelectableChannel): SelectionKey {
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            synchronized(regLock) {
                pendingRegistrations.add(ChannelRegistration(channel, cont))
            }
            selector.wakeup()
        }
    }

    /**
     * Blocking version of [registerChannel] for non-suspend callers.
     *
     * Delegates to [registerChannel] via [runBlocking]. Used by
     * [NioEngine.bindPipeline] which is non-suspend (Pipeline
     * zero-coroutine principle).
     */
    fun registerChannelBlocking(channel: SelectableChannel): SelectionKey =
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeout(BIND_TIMEOUT_MS) {
                registerChannel(channel)
            }
        }

    companion object {
        // Generous timeout for blocking operations at server startup.
        // Not on the hot path — only used by bindPipeline.
        private const val BIND_TIMEOUT_MS = 10_000L
    }

    // --- Interest ops (fast path, no JNI re-register) ---

    /**
     * Per-[SelectionKey] callback container — one slot per interest op so a
     * single key can be armed for `OP_READ` and `OP_WRITE` simultaneously
     * without one registration silently overwriting the other.
     *
     * The previous design called `key.attach(callback)` directly, which only
     * holds a single value. When the second op was armed (concurrent read
     * loop + back-pressured write retry, or read while a flush continuation
     * was registered) the earlier callback was dropped. The lost callback
     * caused [NioIoTransport]'s `onReadable` to never observe the peer FIN
     * that follows a WebSocket close handshake on macOS, deadlocking the
     * upgrade session (K23). `OP_ACCEPT` and `OP_CONNECT` get their own
     * slots too — they never overlap with `OP_READ` / `OP_WRITE` on the same
     * key in practice, but having a dedicated slot keeps
     * [processSelectedKeys] uniform and avoids re-introducing the
     * single-attachment pitfall the next time someone wires up a new op.
     *
     * Stays a plain mutable holder (not a `CancellableContinuation`): see
     * the design invariant on [setInterestCallback].
     */
    internal class KeyCallbacks {
        @JvmField
        var readCallback: Runnable? = null

        @JvmField
        var writeCallback: Runnable? = null

        @JvmField
        var acceptCallback: Runnable? = null

        @JvmField
        var connectCallback: Runnable? = null
    }

    /**
     * Sets interest ops with a callback for readiness notification.
     *
     * This is the fast path called on every `read()` / `accept()` / `connect()`.
     * It only mutates the [SelectionKey]'s interest ops (memory operation) and
     * does NOT call `channel.register()` (JNI). The Selector is woken up to
     * re-evaluate the updated interest set.
     *
     * [callback] is stored in the per-op slot of the key's [KeyCallbacks]
     * attachment (created on first call) and invoked one-shot on the
     * EventLoop thread when the channel becomes ready. Multiple ops armed
     * on the same key keep independent callbacks.
     *
     * **Design invariant**: callbacks MUST be plain [Runnable] instances,
     * never a [CancellableContinuation]. `CancellableContinuationImpl`
     * transitively implements `Runnable` (via `DispatchedTask → scheduling.Task`),
     * so storing a continuation directly would make [processSelectedKeys]
     * invoke `DispatchedTask.run()` on the selector thread — this bypasses
     * the continuation's own state machine and leaves it installed as a
     * stale child handler of the parent Job, producing a
     * `ClassCastException` on later cancel. Always wrap the resume logic in
     * an explicit `Runnable { ... }` lambda. See [NioStreamServer] class
     * KDoc for the full history.
     *
     * @param key      The cached SelectionKey from [registerChannel].
     * @param ops      Interest ops to add (e.g., [SelectionKey.OP_READ]).
     * @param callback Runnable to execute when the channel becomes ready.
     */
    fun setInterestCallback(key: SelectionKey, ops: Int, callback: Runnable) {
        // Funnel cross-thread mutations through the owning EventLoop. Both
        // the [KeyCallbacks] fields and [SelectionKey.interestOps] are
        // unsynchronised — they are only safe to read/write on the loop
        // thread that owns the [Selector]. Same idiom as
        // `EpollEventLoop.register` / `KqueueEventLoop.register`: enforce a
        // single-writer invariant via dispatch so concurrent producers do
        // not race the [processSelectedKeys] reader, and so the "set
        // callback first, then set interest bit" ordering is naturally
        // happens-before for the loop thread without volatile fences.
        if (inEventLoop()) {
            applySetInterestCallback(key, ops, callback)
        } else {
            dispatch(EmptyCoroutineContext, Runnable { applySetInterestCallback(key, ops, callback) })
        }
    }

    private fun applySetInterestCallback(key: SelectionKey, ops: Int, callback: Runnable) {
        assertInEventLoop("NioEventLoop.applySetInterestCallback")
        val callbacks = (key.attachment() as? KeyCallbacks) ?: KeyCallbacks().also { key.attach(it) }
        if ((ops and SelectionKey.OP_READ) != 0) callbacks.readCallback = callback
        if ((ops and SelectionKey.OP_WRITE) != 0) callbacks.writeCallback = callback
        if ((ops and SelectionKey.OP_ACCEPT) != 0) callbacks.acceptCallback = callback
        if ((ops and SelectionKey.OP_CONNECT) != 0) callbacks.connectCallback = callback
        key.interestOps(key.interestOps() or ops)
        // No selector.wakeup() needed — we are on the EL thread, so the next
        // select() iteration (after drainTasks / drainRegistrations) picks
        // up the new interest mask. Cross-thread callers go through
        // [dispatch] above, which performs the wakeup as part of enqueueing
        // the task.
    }

    /**
     * Removes specific interest ops from a SelectionKey.
     *
     * Called from [invokeOnCancellation] when a coroutine waiting on
     * OP_WRITE (flush) or OP_READ is cancelled. Clears only the
     * specified ops and the matching callback without affecting other
     * interest bits or the other-direction callback.
     *
     * Same single-writer invariant as [setInterestCallback]: callers from
     * outside the EventLoop thread are funnelled through [dispatch].
     */
    fun removeInterest(key: SelectionKey, ops: Int) {
        if (inEventLoop()) {
            applyRemoveInterest(key, ops)
        } else {
            dispatch(EmptyCoroutineContext, Runnable { applyRemoveInterest(key, ops) })
        }
    }

    private fun applyRemoveInterest(key: SelectionKey, ops: Int) {
        assertInEventLoop("NioEventLoop.applyRemoveInterest")
        if (!key.isValid) return
        val callbacks = key.attachment() as? KeyCallbacks
        if (callbacks != null) {
            if ((ops and SelectionKey.OP_READ) != 0) callbacks.readCallback = null
            if ((ops and SelectionKey.OP_WRITE) != 0) callbacks.writeCallback = null
            if ((ops and SelectionKey.OP_ACCEPT) != 0) callbacks.acceptCallback = null
            if ((ops and SelectionKey.OP_CONNECT) != 0) callbacks.connectCallback = null
        }
        key.interestOps(key.interestOps() and ops.inv())
    }

    // --- Event loop ---

    private fun loop() {
        while (running) {
            drainTasks()
            drainRegistrations()

            val n = if (taskQueue.isNotEmpty()) {
                selector.selectNow()
            } else {
                selector.select()
            }
            if (n > 0) {
                processSelectedKeys()
            }
        }
    }

    /** Runs all queued coroutine continuations on this thread. */
    private fun drainTasks() {
        while (true) {
            val task = taskQueue.poll() ?: break
            task.run()
        }
    }

    /**
     * Processes pending initial channel registrations.
     *
     * Each channel is registered with `interestOps=0` and the resulting
     * [SelectionKey] is delivered to the waiting coroutine.
     */
    private fun drainRegistrations() {
        val regs: List<ChannelRegistration>
        synchronized(regLock) {
            if (pendingRegistrations.isEmpty()) return
            regs = pendingRegistrations.toList()
            pendingRegistrations.clear()
        }
        for (reg in regs) {
            try {
                if (reg.channel.isOpen) {
                    // Register with interestOps=0 (no interest).
                    // The caller uses setInterest() to enable OP_READ etc.
                    val key = reg.channel.register(selector, 0)
                    reg.continuation.resume(key)
                }
            } catch (e: Exception) {
                reg.continuation.resumeWith(Result.failure(e))
            }
        }
    }

    /**
     * Resumes continuations for all ready channels.
     *
     * Unlike the previous implementation that called `key.cancel()`,
     * this clears interest ops via `interestOps(0)` so the key remains
     * valid for reuse. The next `read()` / `accept()` call will set
     * interest ops again via [setInterest].
     */
    private fun processSelectedKeys() {
        val iter = selector.selectedKeys().iterator()
        while (iter.hasNext()) {
            val key = iter.next()
            iter.remove()
            try {
                val callbacks = key.attachment() as? KeyCallbacks ?: continue
                // Snapshot ready ops, clear interest+callback for ready
                // direction, then run callbacks. Read and write fire
                // independently; leaving the other direction's interest
                // and callback intact preserves armRead while a flush
                // is back-pressured (and vice versa). The previous
                // implementation cleared all interest + the single
                // `attach`ment unconditionally, dropping the
                // not-yet-fired direction (K23 root cause).
                val readyOps = key.readyOps()
                val readReady = (readyOps and SelectionKey.OP_READ) != 0
                val writeReady = (readyOps and SelectionKey.OP_WRITE) != 0
                val acceptReady = (readyOps and SelectionKey.OP_ACCEPT) != 0
                val connectReady = (readyOps and SelectionKey.OP_CONNECT) != 0
                val readCb = if (readReady) callbacks.readCallback else null
                val writeCb = if (writeReady) callbacks.writeCallback else null
                val acceptCb = if (acceptReady) callbacks.acceptCallback else null
                val connectCb = if (connectReady) callbacks.connectCallback else null
                // Defensive: a direction was reported ready but no callback was
                // registered. Should never happen — the only path that sets an
                // interest bit also installs the callback. If it does, the
                // interest must still be cleared so the next select() does not
                // spin on the same fd. Mirrors the WARN+remove guard in
                // EpollEventLoop / KqueueEventLoop (PR #447 / #449).
                warnIfStaleInterest(key, readReady, readCb, writeReady, writeCb, acceptReady, acceptCb, connectReady, connectCb)
                var clearMask = 0
                if (readReady) {
                    callbacks.readCallback = null
                    clearMask = clearMask or SelectionKey.OP_READ
                }
                if (writeReady) {
                    callbacks.writeCallback = null
                    clearMask = clearMask or SelectionKey.OP_WRITE
                }
                if (acceptReady) {
                    callbacks.acceptCallback = null
                    clearMask = clearMask or SelectionKey.OP_ACCEPT
                }
                if (connectReady) {
                    callbacks.connectCallback = null
                    clearMask = clearMask or SelectionKey.OP_CONNECT
                }
                if (clearMask != 0) {
                    key.interestOps(key.interestOps() and clearMask.inv())
                }
                readCb?.run()
                writeCb?.run()
                acceptCb?.run()
                connectCb?.run()
            } catch (e: Exception) {
                // Individual key failure must not stop processing other keys.
                // The channel's coroutine will observe the error on next I/O.
                logger.warn(e) { "SelectionKey processing failed" }
            }
        }
    }

    /**
     * Logs a WARN line for each direction that was reported ready by [Selector] but
     * had no registered callback in [KeyCallbacks]. Extracted from
     * [processSelectedKeys] so the per-key dispatch loop stays under detekt's
     * cyclomatic-complexity limit. The message format mirrors the sibling guards
     * in `EpollEventLoop` / `KqueueEventLoop` (cf. PR #447 / #449).
     */
    @Suppress("LongParameterList")
    private fun warnIfStaleInterest(
        key: SelectionKey,
        readReady: Boolean,
        readCb: Runnable?,
        writeReady: Boolean,
        writeCb: Runnable?,
        acceptReady: Boolean,
        acceptCb: Runnable?,
        connectReady: Boolean,
        connectCb: Runnable?,
    ) {
        if (readReady && readCb == null) {
            logger.warn { "processSelectedKeys: no handler for ${key.channel()} OP_READ — clearing NIO interest" }
        }
        if (writeReady && writeCb == null) {
            logger.warn { "processSelectedKeys: no handler for ${key.channel()} OP_WRITE — clearing NIO interest" }
        }
        if (acceptReady && acceptCb == null) {
            logger.warn { "processSelectedKeys: no handler for ${key.channel()} OP_ACCEPT — clearing NIO interest" }
        }
        if (connectReady && connectCb == null) {
            logger.warn { "processSelectedKeys: no handler for ${key.channel()} OP_CONNECT — clearing NIO interest" }
        }
    }

    /**
     * Stops the EventLoop thread, waits up to 2 seconds for it to finish,
     * and closes the Selector. Pending tasks and registrations are discarded.
     */
    fun close() {
        running = false
        selector.wakeup()
        thread.join(2000)
        selector.close()
    }
}

/**
 * A group of [NioEventLoop] instances for round-robin channel assignment.
 *
 * Mirrors Netty's `NioEventLoopGroup`: distributes channels across multiple
 * EventLoop threads for parallel I/O processing.
 *
 * @param size Number of EventLoop threads.
 * @param namePrefix Thread name prefix (e.g., "keel-nio-worker").
 * @param logger Logger for each EventLoop in the group.
 * @param allocator Base allocator; [BufferAllocator.createForEventLoop] is called per EventLoop.
 * @param readBufferSize Per-read buffer size propagated to each EventLoop
 *   (see [io.github.fukusaka.keel.core.IoEngineConfig.readBufferSize]).
 */
internal class NioEventLoopGroup(
    size: Int,
    namePrefix: String,
    logger: Logger,
    allocator: BufferAllocator,
    readBufferSize: Int = IoTransport.DEFAULT_READ_BUFFER_SIZE,
) {
    private val loops = Array(size) { i ->
        NioEventLoop("$namePrefix-$i", logger, allocator.createForEventLoop(), readBufferSize)
    }
    private val index = java.util.concurrent.atomic.AtomicInteger(0)

    /** Number of EventLoops in this group. */
    val size: Int get() = loops.size

    /** Returns the [NioEventLoop] at [index] (direct access, no round-robin). */
    fun at(index: Int): NioEventLoop = loops[index]

    /**
     * Returns the next [NioEventLoop] in round-robin order. The
     * per-EventLoop allocator is exposed as [NioEventLoop.allocator].
     */
    fun next(): NioEventLoop {
        val i = (index.getAndIncrement() and Int.MAX_VALUE) % loops.size
        return loops[i]
    }

    /** Closes all EventLoops in this group. Blocks until each thread terminates (up to 2s each). */
    fun close() {
        for (loop in loops) loop.close()
    }
}
