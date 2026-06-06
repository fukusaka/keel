package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.pipeline.IoTransport
import io.github.fukusaka.keel.scope.ScopeLocal
import io.github.fukusaka.keel.scope.scopeLocal
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

    // Per-EventLoop allocator confinement on a single shared scope. Each worker
    // EventLoop thread resolves its own per-EL child via current() — the native
    // ThreadLocalScopeLocal lazily creates and caches one child per thread — so
    // confinement flows through the unified ScopeLocal primitive instead of
    // constructor-time instance handout. createForEventLoop() runs on the EL thread
    // (first read) rather than here; SlabAllocator pools are process-global native
    // heap so the child is equivalent to the previously eager instance.
    private val allocatorScope: ScopeLocal<BufferAllocator> = scopeLocal { allocator.createForEventLoop() }
    private val loops = Array(size) { EpollEventLoop(logger, allocatorScope, readBufferSize) }
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
