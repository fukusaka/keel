package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.pipeline.IoTransport
import kotlin.concurrent.AtomicInt

/**
 * A group of [KqueueEventLoop] instances for distributing I/O across
 * multiple threads.
 *
 * New channels are assigned to EventLoops in round-robin order via [next].
 * Each EventLoop runs on its own pthread with an independent kqueue fd,
 * so channels on different EventLoops never contend for the same lock.
 *
 * Each EventLoop has its own [BufferAllocator] instance created via
 * [BufferAllocator.createForEventLoop], enabling lock-free pooling.
 *
 * Same design as [NioEventLoopGroup][io.github.fukusaka.keel.engine.nio.NioEventLoopGroup]:
 * Array + AtomicInt round-robin.
 *
 * @param size Number of EventLoop threads. Must be >= 1.
 * @param logger Logger for each EventLoop in the group.
 * @param allocator Base allocator; [createForEventLoop] is called per EventLoop.
 * @param readBufferSize Per-read buffer size propagated to each EventLoop
 *   (see [io.github.fukusaka.keel.core.IoEngineConfig.readBufferSize]).
 */
internal class KqueueEventLoopGroup(
    size: Int,
    logger: Logger,
    allocator: BufferAllocator,
    readBufferSize: Int = IoTransport.DEFAULT_READ_BUFFER_SIZE,
) {

    private val loops = Array(size) { KqueueEventLoop(logger, allocator.createForEventLoop(), readBufferSize) }
    private val index = AtomicInt(0)

    /** Number of EventLoops in this group. */
    val size: Int get() = loops.size

    /** Starts all EventLoop threads. */
    fun start() {
        for (loop in loops) loop.start()
    }

    /**
     * Returns the next [KqueueEventLoop] in round-robin order. The
     * per-EventLoop allocator is exposed as [KqueueEventLoop.allocator].
     *
     * Uses atomic increment with overflow-safe masking (same as NIO).
     * Thread-safe: multiple accept threads can call this concurrently.
     */
    fun next(): KqueueEventLoop {
        val i = (index.getAndIncrement() and Int.MAX_VALUE) % loops.size
        return loops[i]
    }

    /** Returns the [KqueueEventLoop] at [index] (direct access, no round-robin). */
    fun at(index: Int): KqueueEventLoop = loops[index]

    /** Stops all EventLoop threads and releases resources. */
    fun close() {
        for (loop in loops) loop.close()
    }
}
