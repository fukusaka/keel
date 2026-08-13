@file:OptIn(InternalReadinessEngineApi::class)

package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.native.readiness.AbstractPosixEventLoopGroup
import io.github.fukusaka.keel.native.readiness.InternalReadinessEngineApi
import io.github.fukusaka.keel.pipeline.IoTransport

/**
 * A group of [KqueueEventLoop] instances for distributing I/O across
 * multiple threads.
 *
 * New channels are assigned to EventLoops in round-robin order via `next()`.
 * Each EventLoop runs on its own pthread with an independent kqueue fd,
 * so channels on different EventLoops never contend for the same lock.
 *
 * Each EventLoop has its own [BufferAllocator] instance created via
 * [BufferAllocator.createChild], enabling lock-free pooling.
 *
 * Everything a group does once its loops exist — round-robin, all-or-none
 * start, close, and the rollback both build paths need — is
 * [AbstractPosixEventLoopGroup]'s. What is here is what a kqueue loop needs to
 * be built with.
 *
 * @param size Number of EventLoop threads. Must be >= 1.
 * @param logger Logger for each EventLoop in the group.
 * @param allocator Base allocator; [BufferAllocator.createChild] is called per EventLoop.
 * @param readBufferSize Per-read buffer size propagated to each EventLoop
 *   (see [io.github.fukusaka.keel.core.IoEngineConfig.readBufferSize]).
 * @param idleTimeoutMillis Engine-wide idle (no-progress) timeout propagated to
 *   each EventLoop (see [io.github.fukusaka.keel.core.IoEngineConfig.idleTimeoutMillis]).
 * @param syscallOps Shared across every loop in the group when given, so a test
 *   can script a sequence that spans them — which is what reaches the rollback,
 *   since it needs one loop's construction to fail after others have
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
) : AbstractPosixEventLoopGroup<KqueueEventLoop>(
    buildLoops(size) {
        KqueueEventLoop(
            logger,
            allocator.createChild(),
            readBufferSize,
            idleTimeoutMillis,
            flushCoalescing,
            syscallOps ?: PosixKqueueSyscallOps(logger),
        )
    },
)
