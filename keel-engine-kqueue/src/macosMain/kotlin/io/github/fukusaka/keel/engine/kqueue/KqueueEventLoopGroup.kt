package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.native.posix.Interest
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
 * [BufferAllocator.createChild], enabling lock-free pooling.
 *
 * Same design as [NioEventLoopGroup][io.github.fukusaka.keel.engine.nio.NioEventLoopGroup]:
 * Array + AtomicInt round-robin.
 *
 * @param size Number of EventLoop threads. Must be >= 1.
 * @param logger Logger for each EventLoop in the group.
 * @param allocator Base allocator; [createChild] is called per EventLoop.
 * @param readBufferSize Per-read buffer size propagated to each EventLoop
 *   (see [io.github.fukusaka.keel.core.IoEngineConfig.readBufferSize]).
 * @param idleTimeoutMillis Engine-wide idle (no-progress) timeout propagated to
 *   each EventLoop (see [io.github.fukusaka.keel.core.IoEngineConfig.idleTimeoutMillis]).
 * @param syscallOps Shared across every loop in the group when given, so a test
 *   can script a sequence that spans them — which is what reaches the rollback
 *   below, since it needs one loop's construction to fail after others have
 *   succeeded. `null` (production) gives each loop its own instance, as before.
 */
internal class KqueueEventLoopGroup(
    size: Int,
    logger: Logger,
    allocator: BufferAllocator,
    readBufferSize: Int = IoTransport.DEFAULT_READ_BUFFER_SIZE,
    idleTimeoutMillis: Long = 0,
    flushCoalescing: Boolean = true,
    syscallOps: KqueueSyscallOps? = null,
) {

    private val loops: Array<KqueueEventLoop>

    init {
        // Built one at a time so a failure can give back the ones before it.
        // `Array(size) { … }` cannot: a constructor that throws on loop k
        // discards the array along with loops 0..k-1, and those are fully
        // built -- each holding a kqueue fd, a wakeup pipe, native scratch and
        // an allocator child that only its own `close()` returns. Nothing else
        // has a reference to them, so nothing ever will.
        val built = ArrayList<KqueueEventLoop>(size)
        try {
            repeat(size) {
                built +=
                    KqueueEventLoop(
                        logger,
                        allocator.createChild(),
                        readBufferSize,
                        idleTimeoutMillis,
                        flushCoalescing,
                        syscallOps ?: PosixKqueueSyscallOps(logger),
                    )
            }
        } catch (constructionFailure: Throwable) {
            closeAll(built, constructionFailure)
            throw constructionFailure
        }
        loops = built.toTypedArray()
    }

    private val index = AtomicInt(0)

    /** Number of EventLoops in this group. */
    val size: Int get() = loops.size

    /**
     * Starts all EventLoop threads.
     *
     * All or none. `pthread_create` fails with `EAGAIN` when the process is out
     * of threads, and a partial start would leave this group's earlier loops
     * running and its later ones idle, with the group reference discarded by
     * the constructor that threw — nothing left to stop them or to give back
     * what they hold. On failure every loop is closed, whether it was started
     * or not: closing a started one joins its thread, and closing an unstarted
     * one runs the teardown its thread would have.
     */
    fun start() {
        try {
            for (loop in loops) loop.start()
        } catch (startFailure: Throwable) {
            closeAll(loops.asList(), startFailure)
            throw startFailure
        }
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

    /**
     * Stops all EventLoop threads and releases resources.
     *
     * Every loop is closed whatever the ones before it did. A loop's `close()`
     * can throw — its teardown re-raises what its stages failed with — and
     * walking out on the first would leave the rest of the group holding their
     * descriptors with no second caller to try again. The first failure is what
     * the caller is told; the others are attached to it.
     */
    fun close() {
        var failure: Throwable? = null
        for (loop in loops) {
            try {
                loop.close()
            } catch (closeFailure: Throwable) {
                val first = failure
                if (first == null) failure = closeFailure else first.addSuppressed(closeFailure)
            }
        }
        failure?.let { throw it }
    }

    /**
     * Closes [toRelease], attaching any failure to [cause] rather than letting
     * it replace the reason the rollback is happening.
     *
     * Used by the two paths that build or start this group: the caller is owed
     * the failure that ended construction, not one from the cleanup after it.
     */
    private fun closeAll(toRelease: List<KqueueEventLoop>, cause: Throwable) {
        for (loop in toRelease) {
            try {
                loop.close()
            } catch (closeFailure: Throwable) {
                cause.addSuppressed(closeFailure)
            }
        }
    }

    /** Whether any loop in this group still holds a callback for [fd] + [interest]. */
    internal fun hasCallbackRegistration(fd: Int, interest: Interest): Boolean =
        loops.any { it.hasCallbackRegistration(fd, interest) }

    /** Total participants across this group's loops. */
    internal fun participants(): Int = loops.sumOf { it.participants() }
}
