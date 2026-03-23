package io.github.fukusaka.keel.engine.kqueue

import kotlin.concurrent.AtomicInt

/**
 * A group of [KqueueEventLoop] instances for distributing I/O across
 * multiple threads.
 *
 * New channels are assigned to EventLoops in round-robin order via [next].
 * Each EventLoop runs on its own pthread with an independent kqueue fd,
 * so channels on different EventLoops never contend for the same lock.
 *
 * Same design as [NioEventLoopGroup][io.github.fukusaka.keel.engine.nio.NioEventLoopGroup]:
 * Array + AtomicInt round-robin.
 *
 * @param size Number of EventLoop threads. Must be >= 1.
 */
internal class KqueueEventLoopGroup(size: Int) {

    private val loops = Array(size) { KqueueEventLoop() }
    private val index = AtomicInt(0)

    /** Starts all EventLoop threads. */
    fun start() {
        for (loop in loops) loop.start()
    }

    /**
     * Returns the next EventLoop in round-robin order.
     *
     * Uses atomic increment with overflow-safe masking (same as NIO).
     * Thread-safe: multiple accept threads can call this concurrently.
     */
    fun next(): KqueueEventLoop {
        val i = (index.getAndIncrement() and Int.MAX_VALUE) % loops.size
        return loops[i]
    }

    /** Stops all EventLoop threads and releases resources. */
    fun close() {
        for (loop in loops) loop.close()
    }
}
