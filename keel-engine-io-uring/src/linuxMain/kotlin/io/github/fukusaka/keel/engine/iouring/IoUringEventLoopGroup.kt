package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.buf.BufferAllocator
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
 * Each worker EventLoop owns a [ProvidedBufferRing] for multishot recv
 * with kernel-managed buffer selection. The ring is registered with the
 * worker's io_uring instance and used by [IoUringPushSource] for zero-copy
 * data delivery.
 *
 * @param size Number of EventLoop threads. Must be >= 1.
 * @param logger Logger for each EventLoop in the group.
 * @param allocator Base allocator; [createForEventLoop] is called per EventLoop.
 * @param capabilities Runtime-detected io_uring kernel capabilities.
 * @param ringSize SQE ring size per EventLoop. See [IoUringEventLoop.DEFAULT_RING_SIZE].
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
internal class IoUringEventLoopGroup(
    size: Int,
    logger: Logger,
    allocator: BufferAllocator,
    capabilities: IoUringCapabilities = IoUringCapabilities(),
    ringSize: Int = IoUringEventLoop.DEFAULT_RING_SIZE,
) {

    /** Number of EventLoop threads in this group. */
    val size: Int = size

    private val loops = Array(size) { IoUringEventLoop(logger, capabilities, ringSize) }
    private val allocators = Array(size) { allocator.createForEventLoop() }
    private val bufferRings: Array<ProvidedBufferRing?> = if (capabilities.providedBufferRing) {
        Array(size) { i -> ProvidedBufferRing(loops[i].ringPtr, bgid = i) }
    } else {
        arrayOfNulls(size)
    }
    private val fileRegistries: Array<FixedFileRegistry?> = if (capabilities.fixedFiles) {
        Array(size) { i -> FixedFileRegistry(loops[i].ringPtr) }
    } else {
        arrayOfNulls(size)
    }
    private val index = AtomicInt(0)

    /** Starts all EventLoop threads. */
    fun start() {
        for (loop in loops) loop.start()
    }

    /**
     * Returns the index of the next EventLoop in round-robin order.
     * Use [loopAt], [allocatorAt], and [bufferRingAt] to access resources.
     */
    fun nextIndex(): Int =
        (index.getAndIncrement() and Int.MAX_VALUE) % loops.size

    /** Returns the EventLoop at [i]. */
    fun loopAt(i: Int): IoUringEventLoop = loops[i]

    /** Returns the per-EventLoop allocator at [i]. */
    fun allocatorAt(i: Int): BufferAllocator = allocators[i]

    /** Returns the per-EventLoop [ProvidedBufferRing] at [i], or null if not supported. */
    fun bufferRingAt(i: Int): ProvidedBufferRing? = bufferRings[i]

    /** Returns the per-EventLoop [FixedFileRegistry] at [i], or null if not supported. */
    fun fileRegistryAt(i: Int): FixedFileRegistry? = fileRegistries[i]

    /** Stops all EventLoop threads and releases resources. */
    fun close() {
        for (reg in fileRegistries) reg?.close()
        for (ring in bufferRings) ring?.close()
        for (loop in loops) loop.close()
    }
}
