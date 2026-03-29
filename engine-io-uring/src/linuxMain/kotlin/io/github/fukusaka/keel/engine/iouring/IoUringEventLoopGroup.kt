package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.io.BufferAllocator
import io.github.fukusaka.keel.logging.Logger
import kotlin.concurrent.AtomicInt

/**
 * A group of [IoUringEventLoop] instances for distributing I/O across
 * multiple threads.
 *
 * New channels are assigned to EventLoops in round-robin order via [next].
 * Each EventLoop runs on its own pthread with an independent io_uring ring,
 * so channels on different EventLoops never contend for the same ring.
 *
 * Each EventLoop has its own [BufferAllocator] instance created via
 * [BufferAllocator.createForEventLoop], enabling lock-free pooling.
 *
 * @param size Number of EventLoop threads. Must be >= 1.
 * @param logger Logger for each EventLoop in the group.
 * @param allocator Base allocator; [createForEventLoop] is called per EventLoop.
 * @param ringSize SQE ring size per EventLoop. See [IoUringEventLoop.DEFAULT_RING_SIZE].
 */
internal class IoUringEventLoopGroup(
    size: Int,
    logger: Logger,
    allocator: BufferAllocator,
    ringSize: Int = IoUringEventLoop.DEFAULT_RING_SIZE,
) {

    private val loops = Array(size) { IoUringEventLoop(logger, ringSize) }
    private val allocators = Array(size) { allocator.createForEventLoop() }
    private val index = AtomicInt(0)

    /** Starts all EventLoop threads and initialises their provided buffer rings. */
    fun start() {
        for (loop in loops) {
            loop.initBufferRing()
            loop.start()
        }
    }

    /**
     * Returns the index of the next EventLoop in round-robin order.
     * Use [loopAt] and [allocatorAt] to access the EventLoop and allocator.
     */
    fun nextIndex(): Int =
        (index.getAndIncrement() and Int.MAX_VALUE) % loops.size

    /** Returns the EventLoop at [i]. */
    fun loopAt(i: Int): IoUringEventLoop = loops[i]

    /** Returns the per-EventLoop allocator at [i]. */
    fun allocatorAt(i: Int): BufferAllocator = allocators[i]

    /** Stops all EventLoop threads and releases resources. */
    fun close() {
        for (loop in loops) loop.close()
    }
}
