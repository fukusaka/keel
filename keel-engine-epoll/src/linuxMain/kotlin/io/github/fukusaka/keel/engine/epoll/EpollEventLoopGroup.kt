package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.pipeline.IoTransport
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
 * @param readBufferSize Per-read buffer size propagated to each EventLoop
 *   (see [io.github.fukusaka.keel.core.IoEngineConfig.readBufferSize]).
 */
internal class EpollEventLoopGroup(
    size: Int,
    logger: Logger,
    allocator: BufferAllocator,
    readBufferSize: Int = IoTransport.DEFAULT_READ_BUFFER_SIZE,
) {

    private val loops = Array(size) { EpollEventLoop(logger, allocator.createForEventLoop(), readBufferSize) }
    private val index = AtomicInt(0)

    /** Number of EventLoops in this group. */
    val size: Int get() = loops.size

    /** Starts all EventLoop threads. */
    fun start() {
        for (loop in loops) loop.start()
    }

    /**
     * Returns the next [EpollEventLoop] in round-robin order. The
     * per-EventLoop allocator is exposed as [EpollEventLoop.allocator].
     */
    fun next(): EpollEventLoop {
        val i = (index.getAndIncrement() and Int.MAX_VALUE) % loops.size
        return loops[i]
    }

    /** Returns the [EpollEventLoop] at [index] (direct access, no round-robin). */
    fun at(index: Int): EpollEventLoop = loops[index]

    /** Stops all EventLoop threads and releases resources. */
    fun close() {
        for (loop in loops) loop.close()
    }
}
