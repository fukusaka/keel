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
        Array(size) { i -> ProvidedBufferRing(loops[i], logger, bgid = i) }
    } else {
        arrayOfNulls(size)
    }
    private val fileRegistries: Array<FixedFileRegistry?> = if (capabilities.fixedFiles) {
        Array(size) { i -> FixedFileRegistry(loops[i], logger) }
    } else {
        arrayOfNulls(size)
    }
    private val bufferTables: Array<RegisteredBufferTable?> = if (capabilities.registeredBuffers) {
        // Warmup pool, then prepare pooled buffer addresses for kernel registration.
        // Requires SlabAllocator (Native pool). Custom allocators silently skip
        // registration — SEND_ZC falls back to per-send page pinning.
        Array(size) { i ->
            val alloc = allocators[i]
            warmupPool(alloc)
            val pooled = (alloc as? io.github.fukusaka.keel.buf.SlabAllocator)?.nativePooledBuffers()
            if (pooled != null && pooled.isNotEmpty()) {
                RegisteredBufferTable(loops[i], pooled, logger)
            } else {
                null
            }
        }
    } else {
        arrayOfNulls(size)
    }
    private val index = AtomicInt(0)

    /**
     * Warms up the allocator pool by allocating and releasing buffers
     * for each registered size class. After warmup, all pool slots are
     * filled and [io.github.fukusaka.keel.buf.SlabAllocator.nativePooledBuffers]
     * returns the complete set of pooled addresses.
     */
    private fun warmupPool(alloc: io.github.fukusaka.keel.buf.BufferAllocator) {
        // Allocate enough buffers to fill the pool, then release them back.
        // This ensures all pool slots have been touched and addresses are stable.
        val bufs = mutableListOf<io.github.fukusaka.keel.buf.IoBuf>()
        // Allocate default size class (8 KiB) up to local pool slots.
        repeat(LOCAL_WARMUP_COUNT) {
            bufs.add(alloc.allocate(io.github.fukusaka.keel.pipeline.IoTransport.DEFAULT_READ_BUFFER_SIZE))
        }
        bufs.forEach { it.release() }
    }

    /**
     * Starts all EventLoop threads and orchestrates 2-phase register-class init.
     *
     * Each EventLoop is started (spawning its pthread), and each register-class
     * `initOnEventLoop` is dispatched to the corresponding EventLoop thread so
     * that `io_uring_register_*` calls run from the submitter task — required
     * for `IORING_SETUP_SINGLE_ISSUER` and by the register classes' own
     * thread-affinity invariants.
     *
     * Also wires [IoUringEventLoop.onExitHook] for each loop so register-class
     * teardown runs on the EventLoop pthread before the ring is destroyed.
     *
     * Blocks until every loop has finished its register-class initialisation.
     */
    fun start() {
        // Wire onExitHook before start(): the hook captures index i by value and
        // fires on the EventLoop pthread as the last action of its loop().
        for (i in 0 until size) {
            loops[i].onExitHook = {
                bufferTables[i]?.close()
                fileRegistries[i]?.close()
                bufferRings[i]?.close()
            }
        }
        for (loop in loops) loop.start()

        // Dispatch register-class initialisation onto each EventLoop's pthread.
        // The first task drained from taskQueue inside loop() runs after
        // initRing() + submitWakeupSqe(), so the ring is ready.
        val pending = AtomicInt(size)
        for (i in 0 until size) {
            loops[i].dispatch(kotlin.coroutines.EmptyCoroutineContext, kotlinx.coroutines.Runnable {
                bufferRings[i]?.initOnEventLoop()
                fileRegistries[i]?.initOnEventLoop()
                bufferTables[i]?.initOnEventLoop()
                pending.decrementAndGet()
            })
        }
        // Wait for register-class init on every loop to complete before returning.
        // sched_yield gives the EventLoop pthreads CPU time; each init takes
        // microseconds so the spin is bounded.
        while (pending.value > 0) {
            platform.posix.sched_yield()
        }
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

    /** Returns the per-EventLoop [RegisteredBufferTable] at [i], or null if not enabled. */
    fun bufferTableAt(i: Int): RegisteredBufferTable? = bufferTables[i]

    /**
     * Stops all EventLoop threads and releases resources.
     *
     * The per-EventLoop register-class teardown (tables / registries / rings)
     * runs on each EventLoop's pthread via [IoUringEventLoop.onExitHook]
     * (wired up in [start]). Here we just signal each loop to stop and
     * wait for its pthread to exit.
     */
    fun close() {
        for (loop in loops) loop.close()
    }

    companion object {
        // Number of buffers to allocate during warmup per size class.
        // Matches the per-EventLoop pool slot count (LOCAL_POOL_SLOTS in SlabAllocator).
        private const val LOCAL_WARMUP_COUNT = 8
    }
}
