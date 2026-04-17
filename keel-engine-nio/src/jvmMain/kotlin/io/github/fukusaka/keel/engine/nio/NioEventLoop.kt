package io.github.fukusaka.keel.engine.nio

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.warn
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import java.nio.channels.SelectableChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext
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
 * `key.cancel()` that caused the Phase (a) → Phase 5b regression.
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
internal class NioEventLoop(name: String, private val logger: Logger) : CoroutineDispatcher() {

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
     * Sets interest ops with a callback for readiness notification.
     *
     * This is the fast path called on every `read()` / `accept()` / `connect()`.
     * It only mutates the [SelectionKey]'s interest ops (memory operation) and
     * does NOT call `channel.register()` (JNI). The Selector is woken up to
     * re-evaluate the updated interest set.
     *
     * [callback] is invoked directly on the EventLoop thread when the channel
     * is ready. One-shot: the attachment is cleared after fire via
     * [clearInterest].
     *
     * **Design invariant**: attachments MUST be plain [Runnable] instances,
     * never a [CancellableContinuation]. `CancellableContinuationImpl`
     * transitively implements `Runnable` (via `DispatchedTask → scheduling.Task`),
     * so attaching a continuation directly would make [processSelectedKeys]
     * invoke `DispatchedTask.run()` on the selector thread — this bypasses the
     * continuation's own state machine and leaves it installed as a stale child
     * handler of the parent Job, producing a `ClassCastException` on later
     * cancel. Always wrap the resume logic in an explicit `Runnable { ... }`
     * lambda. See [NioServer] class KDoc for the full history.
     *
     * @param key      The cached SelectionKey from [registerChannel].
     * @param ops      Interest ops to add (e.g., [SelectionKey.OP_READ]).
     * @param callback Runnable to execute when the channel becomes ready.
     */
    fun setInterestCallback(key: SelectionKey, ops: Int, callback: Runnable) {
        key.attach(callback)
        key.interestOps(key.interestOps() or ops)
        selector.wakeup()
    }

    /**
     * Removes specific interest ops from a SelectionKey.
     *
     * Called from [invokeOnCancellation] when a coroutine waiting on
     * OP_WRITE (flush) or OP_READ is cancelled. Clears only the
     * specified ops without affecting other interest bits.
     */
    fun removeInterest(key: SelectionKey, ops: Int) {
        if (key.isValid) {
            key.interestOps(key.interestOps() and ops.inv())
            key.attach(null)
        }
    }

    /**
     * Clears all interest ops (e.g., after processing a ready event).
     * Called from [processSelectedKeys] to disable readiness notification
     * until the next [setInterest] call.
     */
    private fun clearInterest(key: SelectionKey) {
        key.interestOps(0)
        key.attach(null)
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
                // Attachments are always plain Runnable instances via
                // setInterestCallback. See the invariant note on
                // setInterestCallback — do NOT attach CancellableContinuation
                // or any other Runnable-implementing type with state-machine
                // semantics.
                val attachment = key.attachment() as Runnable? ?: continue
                clearInterest(key)
                attachment.run()
            } catch (e: Exception) {
                // Individual key failure must not stop processing other keys.
                // The channel's coroutine will observe the error on next I/O.
                logger.warn(e) { "SelectionKey processing failed" }
            }
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
 */
internal class NioEventLoopGroup(size: Int, namePrefix: String, logger: Logger, allocator: BufferAllocator) {
    private val loops = Array(size) { i -> NioEventLoop("$namePrefix-$i", logger) }
    private val allocators = Array(size) { allocator.createForEventLoop() }
    private val index = java.util.concurrent.atomic.AtomicInteger(0)

    /** Number of EventLoops in this group. */
    val size: Int get() = loops.size

    /** Returns the EventLoop and allocator at the given [index] (direct access, no round-robin). */
    fun at(index: Int): Pair<NioEventLoop, BufferAllocator> = loops[index] to allocators[index]

    /** Returns the next EventLoop and its per-EventLoop allocator in round-robin order. */
    fun next(): Pair<NioEventLoop, BufferAllocator> {
        val i = (index.getAndIncrement() and Int.MAX_VALUE) % loops.size
        return loops[i] to allocators[i]
    }

    /** Closes all EventLoops in this group. Blocks until each thread terminates (up to 2s each). */
    fun close() {
        for (loop in loops) loop.close()
    }
}
