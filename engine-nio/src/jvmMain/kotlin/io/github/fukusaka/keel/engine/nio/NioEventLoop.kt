package io.github.fukusaka.keel.engine.nio

import kotlinx.coroutines.CancellableContinuation
import java.nio.channels.SelectableChannel
import java.nio.channels.Selector
import kotlin.coroutines.resume

/**
 * Single-threaded NIO event loop for JVM.
 *
 * Drives all I/O for channels created by [NioEngine]. A dedicated daemon
 * [Thread] runs [loop], calling [Selector.select] to wait for channel
 * readiness events and resuming suspended coroutines when their channels
 * become ready.
 *
 * **Scalability**: Currently single-threaded. All channel readiness events
 * are dispatched serially. Multi-thread support
 * (`IoEngineConfig.threads > 1`) will address this in a future PR.
 *
 * **Wakeup mechanism**: [Selector.wakeup] is used to interrupt
 * [Selector.select] when new registrations are added. This is JVM's
 * built-in wakeup mechanism (no pipe or eventfd needed).
 *
 * **Thread safety**: [pendingRegistrations] is protected by
 * `synchronized(lock)`. Registrations are queued and processed at the
 * beginning of each select loop iteration, because
 * [SelectableChannel.register] must not be called while `select()` is
 * in progress.
 *
 * ```
 * EventLoop thread:
 *   while (running):
 *     drain pending registrations → channel.register(selector, ops, cont)
 *     selector.select()
 *     for each selected key:
 *       cancel key, continuation.resume(Unit)
 *
 * Coroutine thread:
 *   suspendCancellableCoroutine { cont ->
 *     eventLoop.register(channel, OP_READ, cont)
 *   }
 *   // resumed when channel is readable → retry NIO read
 * ```
 */
internal class NioEventLoop {

    private val selector: Selector = Selector.open()
    private val lock = Any()
    private val pendingRegistrations = mutableListOf<Registration>()
    @Volatile
    private var running = true
    private val thread: Thread

    /**
     * A pending channel registration request.
     *
     * Queued by [register] and processed by the EventLoop thread.
     * [SelectableChannel.register] requires the channel to not be in
     * a blocking select call, so registrations are deferred to the
     * beginning of each loop iteration.
     */
    class Registration(
        val channel: SelectableChannel,
        val ops: Int,
        val continuation: CancellableContinuation<Unit>,
    )

    init {
        thread = Thread({ loop() }, "keel-nio-eventloop").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Registers a channel for readiness notification.
     *
     * The registration is queued and [Selector.wakeup] is called to
     * interrupt the select loop. The EventLoop thread processes the
     * queue and calls [SelectableChannel.register] on the selector
     * thread.
     *
     * When the channel becomes ready, the [cont] is resumed with [Unit]
     * and the [SelectionKey][java.nio.channels.SelectionKey] is cancelled
     * (one-shot).
     */
    fun register(channel: SelectableChannel, ops: Int, cont: CancellableContinuation<Unit>) {
        synchronized(lock) {
            pendingRegistrations.add(Registration(channel, ops, cont))
        }
        selector.wakeup()
    }

    /**
     * The EventLoop's main loop.
     *
     * 1. Drains pending registrations (must be done on selector thread)
     * 2. Calls [Selector.select] (blocks until events or wakeup)
     * 3. For each selected key, cancels the key and resumes the continuation
     */
    private fun loop() {
        while (running) {
            drainRegistrations()

            val n = selector.select()
            if (n == 0) continue

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
    }

    /**
     * Processes all pending registrations on the EventLoop thread.
     *
     * Each registration calls [SelectableChannel.register] with the
     * continuation as the attachment. Closed channels are skipped.
     */
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

    /**
     * Stops the EventLoop and closes the selector.
     */
    fun close() {
        running = false
        selector.wakeup()
        thread.join(2000)
        selector.close()
    }
}
