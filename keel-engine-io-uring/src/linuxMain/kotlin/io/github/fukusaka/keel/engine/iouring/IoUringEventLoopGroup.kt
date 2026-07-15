package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.warn
import io.github.fukusaka.keel.native.posix.errnoMessage
import io.github.fukusaka.keel.pipeline.IoTransport
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.pthread_cond_destroy
import platform.posix.pthread_cond_init
import platform.posix.pthread_cond_signal
import platform.posix.pthread_cond_t
import platform.posix.pthread_cond_wait
import platform.posix.pthread_mutex_destroy
import platform.posix.pthread_mutex_init
import platform.posix.pthread_mutex_lock
import platform.posix.pthread_mutex_t
import platform.posix.pthread_mutex_unlock
import kotlin.concurrent.AtomicInt
import kotlin.coroutines.EmptyCoroutineContext

/**
 * A group of [IoUringEventLoop] instances for distributing I/O across
 * multiple threads.
 *
 * New channels are assigned to EventLoops in round-robin order via [next].
 * Each EventLoop runs on its own pthread with an independent io_uring ring,
 * so channels on different EventLoops never contend for the same ring.
 *
 * Each EventLoop has its own [BufferAllocator] instance created via
 * [BufferAllocator.createChild], enabling lock-free pooling.
 *
 * Each worker EventLoop owns a [ProvidedBufferRing] for multishot recv
 * with kernel-managed buffer selection. The ring is registered with the
 * worker's io_uring instance and used by [IoUringPushSource] for zero-copy
 * data delivery.
 *
 * @param size Number of EventLoop threads. Must be >= 1.
 * @param logger Logger for each EventLoop in the group.
 * @param allocator Base allocator; [createChild] is called per EventLoop.
 * @param capabilities Runtime-detected io_uring kernel capabilities.
 * @param ringSize SQE ring size per EventLoop. See [IoUringEventLoop.DEFAULT_RING_SIZE].
 * @param readBufferSize Per-read receive buffer size (see
 *   [io.github.fukusaka.keel.core.IoEngineConfig.readBufferSize]): the
 *   per-buffer size of each EventLoop's provided buffer ring on
 *   ring-capable kernels, and the per-recv allocation size of the
 *   allocator-buffer fallback when no ring exists (< 5.19). Does not
 *   affect the SEND-side registered buffers / warmup, which stay at the
 *   allocator segment size.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
// LongParameterList: the group threads the per-loop sizing, capabilities, and
// registered-buffer tuning to each worker loop it spawns; a config bundle would
// only move the same fields behind one more indirection.
@Suppress("LongParameterList")
internal class IoUringEventLoopGroup(
    size: Int,
    private val logger: Logger,
    allocator: BufferAllocator,
    capabilities: IoUringCapabilities = IoUringCapabilities(),
    ringSize: Int = IoUringEventLoop.DEFAULT_RING_SIZE,
    cqSize: Int = 0,
    val readBufferSize: Int = IoTransport.DEFAULT_READ_BUFFER_SIZE,
    idleTimeoutMillis: Long = 0,
    /**
     * Per-EventLoop Fixed Buffer registry strategy. Resolved against
     * `capabilities.registeredBuffers`: `STATIC` falls back to `DISABLED`
     * with a warn log when the kernel lacks `IORING_REGISTER_BUFFERS`;
     * `DYNAMIC` (not yet implemented) is rejected with an early
     * `IllegalStateException`. See [RegisteredBufferStrategy] for the
     * per-value semantics.
     */
    private val registeredBufferStrategy: RegisteredBufferStrategy = RegisteredBufferStrategy.STATIC,
    /**
     * Per-EventLoop upper bound on the number of buffers the STATIC warmup
     * touches before enumeration. The registered set is whatever the
     * allocator's pool holds after warmup, so the effective count is
     * clamped by the pool's own slot capacity — values above it register
     * the pool capacity, not more. Consulted by STATIC only.
     */
    private val registeredBufferSlotCount: Int = DEFAULT_REGISTERED_BUFFER_SLOT_COUNT,
    /**
     * Size in bytes of each buffer the STATIC warmup allocates. Should
     * match the allocator's pooled size class (the read-buffer class by
     * default) — other sizes miss the pool and contribute nothing to the
     * registered set. Consulted by STATIC only.
     */
    private val registeredBufferSize: Int = IoTransport.DEFAULT_READ_BUFFER_SIZE,
    /**
     * Number of recv buffers in each EventLoop's [ProvidedBufferRing]
     * (kernel requirement: a power of two — validated at ring construction).
     * Shared by every connection on the loop; bounds in-flight deliveries
     * before copy-on-pressure kicks in.
     */
    bufferRingSlotCount: Int = ProvidedBufferRing.DEFAULT_BUFFER_COUNT,
) {

    /** Number of EventLoop threads in this group. */
    val size: Int = size

    private val loops = Array(size) {
        IoUringEventLoop(logger, capabilities, ringSize, cqSize, idleTimeoutMillis = idleTimeoutMillis)
    }
    private val allocators = Array(size) { allocator.createChild() }
    private val bufferRings: Array<ProvidedBufferRing?> = if (capabilities.providedBufferRing) {
        Array(size) { i ->
            ProvidedBufferRing(
                loops[i],
                logger,
                bufferCount = bufferRingSlotCount,
                bufferSize = readBufferSize,
                bgid = i,
            )
        }
    } else {
        arrayOfNulls(size)
    }
    private val fileRegistries: Array<FixedFileRegistry?> = if (capabilities.fixedFiles) {
        Array(size) { i -> FixedFileRegistry(loops[i], logger) }
    } else {
        arrayOfNulls(size)
    }

    // Whether the kernel supports registered buffers (SEND_ZC_FIXED). Captured as a
    // field because the per-EventLoop warmup + enumeration + table construction is
    // deferred to the EventLoop pthread in start(), where `capabilities` (a
    // constructor parameter, not a field) is no longer in scope.
    // Derived from both the requested strategy and the kernel capability.
    // DISABLED short-circuits warmup + enumeration + registration entirely.
    private val registeredBuffersEnabled = capabilities.registeredBuffers &&
        registeredBufferStrategy == RegisteredBufferStrategy.STATIC

    // Per-EventLoop SEND_ZC_FIXED registered-buffer tables. Populated on each
    // EventLoop's own pthread during start() (warmup -> pool enumeration ->
    // table construction), not on the construction thread. Keeping the enumerated
    // pool addresses resident on the thread that hands them out is a prerequisite
    // for confining the allocator onto a per-EventLoop scope.
    //
    // Behaviour-neutral under instance-handout: pooled buffers live in the
    // process-global native heap, so the enumerated pointers are identical regardless
    // of which thread warms and enumerates them. Allocators that are not a
    // PooledAllocator subclass leave the entry null and SEND_ZC falls back to per-send
    // page pinning. The kernel io_uring_register_buffers syscall already ran on the
    // EventLoop pthread (StaticRegisteredBufferRegistry.initOnEventLoop); only
    // warmup + enumeration + registry construction move here.
    private val bufferTables: Array<IoUringFixedBufferRegistry?> = arrayOfNulls(size)
    private val index = AtomicInt(0)

    init {
        // Resolve the requested strategy against kernel capabilities.
        // DYNAMIC is not yet implemented — surface a clear error at engine
        // construction so a deployment that requested it does not silently
        // get the STATIC fallback.
        check(registeredBufferStrategy != RegisteredBufferStrategy.DYNAMIC) {
            "RegisteredBufferStrategy.DYNAMIC is not yet implemented. " +
                "Use STATIC (default) or DISABLED."
        }
        // STATIC + kernel < 5.6 (no IORING_REGISTER_BUFFERS) → auto-fall back
        // to DISABLED with a warn log. The engine still starts; every send
        // goes through regular SEND_ZC with per-send page pinning.
        if (registeredBufferStrategy == RegisteredBufferStrategy.STATIC && !capabilities.registeredBuffers) {
            logger.warn {
                "RegisteredBufferStrategy.STATIC requested but the kernel does not support " +
                    "IORING_REGISTER_BUFFERS (requires Linux ≥ 5.6); falling back to DISABLED."
            }
        }
    }

    /**
     * Warms up the allocator pool by allocating and releasing buffers
     * for each registered size class. After warmup, all pool slots are
     * filled and [io.github.fukusaka.keel.buf.enumerateNativePooledBuffers]
     * returns the complete set of pooled addresses.
     */
    private fun warmupPool(alloc: io.github.fukusaka.keel.buf.BufferAllocator) {
        // Allocate enough buffers to fill the pool, then release them back.
        // This ensures all pool slots have been touched and addresses are stable.
        val bufs = mutableListOf<io.github.fukusaka.keel.buf.IoBuf>()
        // Allocate the configured size class up to the configured slot count;
        // the pool's own capacity clamps the effective registered set.
        repeat(registeredBufferSlotCount) {
            bufs.add(alloc.allocate(registeredBufferSize))
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
     * The per-EventLoop registered-buffer warmup (pool fill + [warmupPool]),
     * pooled-address enumeration, and [StaticRegisteredBufferRegistry] construction also run
     * inside this dispatch — on the owning pthread, immediately before the kernel
     * registration — so the enumerated pool addresses are resident on the thread that
     * will hand them out. This is behaviour-neutral under instance-handout but is the
     * groundwork for confining the allocator onto a per-EventLoop scope.
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

        // Dispatch register-class initialisation onto each EventLoop's pthread
        // and block via pthread_cond_t until every loop has finished. The first
        // task drained from taskQueue inside loop() runs after initRing() +
        // submitWakeupSqe(), so the ring is ready when the Runnable fires.
        //
        // pthread_cond_t (rather than a sched_yield spin) so the caller thread
        // blocks without burning CPU on systems where #cores == #EventLoops and
        // the yielding thread would otherwise delay the loop pthreads.
        memScoped {
            val mutex = alloc<pthread_mutex_t>()
            val cond = alloc<pthread_cond_t>()
            val initRet = pthread_mutex_init(mutex.ptr, null)
            check(initRet == 0) { "pthread_mutex_init() failed: ${errnoMessage(initRet)}" }
            val condInitRet = pthread_cond_init(cond.ptr, null)
            check(condInitRet == 0) { "pthread_cond_init() failed: ${errnoMessage(condInitRet)}" }

            val pending = AtomicInt(size)
            for (i in 0 until size) {
                loops[i].dispatch(EmptyCoroutineContext) {
                    // Warm the per-EventLoop pool and build its registered-buffer table
                    // on the owning pthread, before the kernel registration below. Runs
                    // here (not in the constructor) so the enumerated pool addresses are
                    // resident on the thread that owns them. Behaviour-neutral under
                    // instance-handout (process-global PooledAllocator pools).
                    if (registeredBuffersEnabled) {
                        val alloc = allocators[i]
                        warmupPool(alloc)
                        val pooledAlloc = alloc as? io.github.fukusaka.keel.buf.PooledAllocator
                        val pooled = pooledAlloc?.let {
                            io.github.fukusaka.keel.buf.enumerateNativePooledBuffers(it)
                        }
                        if (pooled != null && pooled.isNotEmpty()) {
                            bufferTables[i] = StaticRegisteredBufferRegistry(loops[i], pooled, logger)
                        }
                    }
                    // Every non-STATIC outcome (strategy DISABLED, kernel-capability
                    // fallback, non-pooled allocator, empty pool enumeration) gets the
                    // null-object registry, so bufferTableAt never hands out null once
                    // the group has started and downstream plumbing can drop its
                    // nullable handling as it migrates to the interface.
                    if (bufferTables[i] == null) {
                        bufferTables[i] = DisabledRegisteredBufferRegistry
                    }
                    bufferRings[i]?.initOnEventLoop()
                    fileRegistries[i]?.initOnEventLoop()
                    bufferTables[i]?.initOnEventLoop()
                    val lockRet = pthread_mutex_lock(mutex.ptr)
                    if (lockRet != 0) {
                        logger.warn { "pthread_mutex_lock() failed: ${errnoMessage(lockRet)}" }
                    }
                    val remaining = pending.decrementAndGet()
                    if (remaining == 0) {
                        val signalRet = pthread_cond_signal(cond.ptr)
                        if (signalRet != 0) {
                            logger.warn { "pthread_cond_signal() failed: ${errnoMessage(signalRet)}" }
                        }
                    }
                    val unlockRet = pthread_mutex_unlock(mutex.ptr)
                    if (unlockRet != 0) {
                        logger.warn { "pthread_mutex_unlock() failed: ${errnoMessage(unlockRet)}" }
                    }
                }
            }

            val lockRet = pthread_mutex_lock(mutex.ptr)
            check(lockRet == 0) { "pthread_mutex_lock() failed: ${errnoMessage(lockRet)}" }
            while (pending.value > 0) {
                val waitRet = pthread_cond_wait(cond.ptr, mutex.ptr)
                // POSIX does not define any error return from pthread_cond_wait;
                // any non-zero is a programming error (invalid cond or mutex).
                check(waitRet == 0) { "pthread_cond_wait() failed: ${errnoMessage(waitRet)}" }
            }
            val unlockRet = pthread_mutex_unlock(mutex.ptr)
            if (unlockRet != 0) {
                logger.warn { "pthread_mutex_unlock() failed: ${errnoMessage(unlockRet)}" }
            }

            val destroyCondRet = pthread_cond_destroy(cond.ptr)
            if (destroyCondRet != 0) {
                logger.warn { "pthread_cond_destroy() failed: ${errnoMessage(destroyCondRet)}" }
            }
            val destroyMutexRet = pthread_mutex_destroy(mutex.ptr)
            if (destroyMutexRet != 0) {
                logger.warn { "pthread_mutex_destroy() failed: ${errnoMessage(destroyMutexRet)}" }
            }
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

    /**
     * Returns the per-EventLoop [IoUringFixedBufferRegistry] at [i].
     *
     * Non-null once [start] has run: every non-STATIC outcome was populated
     * with [DisabledRegisteredBufferRegistry] during the init dispatch.
     * Before [start] (or for a group that never starts) the slot is empty
     * and the null-object is substituted here, preserving the same
     * "every lookup answers -1" semantics.
     */
    fun bufferTableAt(i: Int): IoUringFixedBufferRegistry =
        bufferTables[i] ?: DisabledRegisteredBufferRegistry

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
        // Close the per-EL allocator children. Order matters: each loop's
        // pthread is joined first by `loop.close()` above, so by the time
        // we touch the allocators no thread can race a returnToPool /
        // allocate against the closed-flag guard. The allocators are
        // stored on `IoUringEventLoopGroup` (not on the loop) because the
        // io_uring registered-buffer / fixed-file / provided-buffer-ring
        // tables also live here — keeping the close points local to this
        // group keeps the teardown surface small. Default no-op for
        // `DefaultAllocator`.
        for (a in allocators) a.close()
    }

    /**
     * Sums [IoUringEventLoop.sendZcFixedCount] across all loops. Only safe
     * after [close] has joined every EventLoop pthread (the per-EL counters
     * are plain unsynchronised `Long`s).
     */
    fun totalSendZcFixedCount(): Long = loops.sumOf { it.sendZcFixedCount }

    /** Counterpart of [totalSendZcFixedCount] for the regular `SEND_ZC` dispatches. */
    fun totalSendZcRegularCount(): Long = loops.sumOf { it.sendZcRegularCount }

    companion object {
        /**
         * Default per-EventLoop warmup count for the STATIC registered-buffer
         * strategy. Matches the read class's default slot cap
         * (PAGE_CLASS_SLOTS in PooledAllocator) so the default registers
         * exactly the pool's capacity.
         */
        internal const val DEFAULT_REGISTERED_BUFFER_SLOT_COUNT = 8
    }
}
