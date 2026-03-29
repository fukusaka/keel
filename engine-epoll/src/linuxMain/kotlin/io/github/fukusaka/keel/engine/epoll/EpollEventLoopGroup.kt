package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.logging.Logger
import kotlin.concurrent.AtomicInt

/**
 * A group of [EpollEventLoop] instances for distributing I/O across
 * multiple threads.
 *
 * New channels are assigned to EventLoops in round-robin order via [next].
 * Each EventLoop runs on its own pthread with an independent epoll fd,
 * so channels on different EventLoops never contend for the same lock.
 *
 * Each EventLoop has its own [BufferAllocator] instance created via
 * [BufferAllocator.createForEventLoop], enabling lock-free pooling.
 *
 * @param size Number of EventLoop threads. Must be >= 1.
 * @param logger Logger for each EventLoop in the group.
 * @param allocator Base allocator; [createForEventLoop] is called per EventLoop.
 */
internal class EpollEventLoopGroup(size: Int, logger: Logger, allocator: BufferAllocator) {

    private val loops = Array(size) { EpollEventLoop(logger) }
    private val allocators = Array(size) { allocator.createForEventLoop() }
    private val index = AtomicInt(0)

    /** Starts all EventLoop threads. */
    fun start() {
        for (loop in loops) loop.start()
    }

    /**
     * Returns the next EventLoop and its per-EventLoop allocator in round-robin order.
     */
    fun next(): Pair<EpollEventLoop, BufferAllocator> {
        val i = (index.getAndIncrement() and Int.MAX_VALUE) % loops.size
        return loops[i] to allocators[i]
    }

    /** Stops all EventLoop threads and releases resources. */
    fun close() {
        for (loop in loops) loop.close()
    }
}
