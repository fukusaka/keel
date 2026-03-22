package io.github.fukusaka.keel.engine.nio

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import java.nio.channels.SelectableChannel
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
 * **CoroutineDispatcher integration**: By extending [CoroutineDispatcher],
 * coroutines launched on this EventLoop (e.g., `launch(eventLoop) {}`)
 * execute entirely on the EventLoop thread. When `cont.resume()` is called,
 * the continuation is dispatched back to this same thread via [dispatch],
 * eliminating cross-thread dispatch overhead. This matches Netty's model
 * where channelRead/write run on the EventLoop thread.
 *
 * **Wakeup**: [Selector.wakeup] is called when tasks or registrations are
 * queued, interrupting a blocking `select()`.
 *
 * ```
 * EventLoop thread (single loop iteration):
 *   1. drainTasks()          — run coroutine continuations
 *   2. drainRegistrations()  — channel.register(selector, ops, cont)
 *   3. selector.select()     — block until events or wakeup
 *   4. processSelectedKeys() — cont.resume(Unit) for ready channels
 * ```
 */
internal class NioEventLoop(name: String) : CoroutineDispatcher() {

    private val selector: Selector = Selector.open()
    private val lock = Any()
    private val pendingRegistrations = mutableListOf<Registration>()
    private val taskQueue = ConcurrentLinkedQueue<Runnable>()
    @Volatile
    private var running = true
    private val thread: Thread

    class Registration(
        val channel: SelectableChannel,
        val ops: Int,
        val continuation: CancellableContinuation<Unit>,
    )

    init {
        thread = Thread({ loop() }, name).apply {
            isDaemon = true
            start()
        }
    }

    // --- CoroutineDispatcher ---

    /**
     * Dispatches a coroutine block to run on this EventLoop thread.
     *
     * Called by the coroutine machinery when a continuation needs to resume.
     * The block is queued and the selector is woken up to process it
     * in the next loop iteration.
     */
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        taskQueue.add(block)
        selector.wakeup()
    }

    // --- Channel registration ---

    /**
     * Registers a channel for readiness notification.
     *
     * When the channel becomes ready, the [cont] is resumed with [Unit]
     * on this EventLoop thread (via [dispatch]).
     */
    fun register(channel: SelectableChannel, ops: Int, cont: CancellableContinuation<Unit>) {
        synchronized(lock) {
            pendingRegistrations.add(Registration(channel, ops, cont))
        }
        selector.wakeup()
    }

    // --- Event loop ---

    private fun loop() {
        while (running) {
            drainTasks()
            drainRegistrations()

            val n = if (taskQueue.isNotEmpty()) {
                // Tasks pending: don't block, process them immediately
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

    /** Processes pending channel registrations. */
    private fun drainRegistrations() {
        val regs: List<Registration>
        synchronized(lock) {
            if (pendingRegistrations.isEmpty()) return
            regs = pendingRegistrations.toList()
            pendingRegistrations.clear()
        }
        for (reg in regs) {
            try {
                if (reg.channel.isOpen) {
                    reg.channel.register(selector, reg.ops, reg.continuation)
                }
            } catch (_: Exception) {
                // Channel closed between queue and register
            }
        }
    }

    /** Resumes continuations for all ready channels. */
    private fun processSelectedKeys() {
        val iter = selector.selectedKeys().iterator()
        while (iter.hasNext()) {
            val key = iter.next()
            iter.remove()
            val cont = key.attachment() as? CancellableContinuation<*> ?: continue
            key.cancel()
            @Suppress("UNCHECKED_CAST")
            (cont as CancellableContinuation<Unit>).resume(Unit)
        }
    }

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
 */
internal class NioEventLoopGroup(size: Int, namePrefix: String) {
    private val loops = Array(size) { i -> NioEventLoop("$namePrefix-$i") }
    private val index = java.util.concurrent.atomic.AtomicInteger(0)

    /** Returns the next EventLoop in round-robin order. */
    fun next(): NioEventLoop = loops[index.getAndIncrement() % loops.size]

    fun close() {
        for (loop in loops) loop.close()
    }
}
