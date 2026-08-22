@file:OptIn(InternalReadinessEngineApi::class)

package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.core.StreamEngine
import io.github.fukusaka.keel.logging.guarded
import io.github.fukusaka.keel.native.posix.NativeSocket
import io.github.fukusaka.keel.native.posix.NativeSocketOps
import io.github.fukusaka.keel.native.posix.PosixNativeSocket
import io.github.fukusaka.keel.native.readiness.AbstractReadinessEngine
import io.github.fukusaka.keel.native.readiness.AbstractReadinessEventLoop
import io.github.fukusaka.keel.native.readiness.AbstractReadinessEventLoopGroup
import io.github.fukusaka.keel.native.readiness.InternalReadinessEngineApi
import io.github.fukusaka.keel.native.readiness.ReadinessSuspendRegister

/**
 * epoll-backed [StreamEngine] for Linux.
 *
 * Everything the engine does is [AbstractReadinessEngine]'s: the two readiness
 * engines' versions of this class were 675 and 694 lines that differed in one
 * platform check, which Linux does not need — it has an abstract namespace.
 * What is here is the loops this engine builds, because the rollback their
 * construction needs runs before a base constructor could.
 *
 * **Allocator requirement**: enforced at construction. This engine passes
 * read-buffer memory straight to syscalls, so the configured `BufferAllocator`
 * must hand out buffers implementing `NativePointerAccess`; one that does not
 * is refused here rather than failing once per connection.
 */
class EpollEngine(
    config: IoEngineConfig = IoEngineConfig(),
    nativeSocket: NativeSocket = PosixNativeSocket,
    nativeSocketOps: NativeSocketOps? = null,
    suspendRegisterOverride: ReadinessSuspendRegister? = null,
) : AbstractReadinessEngine("EpollEngine", config, nativeSocket, nativeSocketOps, suspendRegisterOverride) {

    override val bossLoop: AbstractReadinessEventLoop
    override val workerGroup: AbstractReadinessEventLoopGroup<*>

    // Built one at a time so a failure gives back what came before it: a loop
    // holds an epoll fd, a wakeup eventfd, native scratch and an allocator
    // child.
    //
    // `pthread_create` answering `EAGAIN` -- a process out of threads -- is the
    // condition this is for, and it lands in the middle of building an engine
    // where nothing else can clean up: the reference never leaves this
    // constructor, so `close` is unreachable for the rest of the process.
    //
    // Closing an unstarted loop is what makes the last stage's rollback work,
    // and it runs the teardown its thread would have.
    init {
        val loggerFactory = config.loggerFactory.guarded()
        val boss = EpollEventLoop(loggerFactory.logger("EpollEventLoop"))
        bossLoop = boss
        try {
            val workers = EpollEventLoopGroup(
                resolveThreads(config),
                loggerFactory.logger("EpollEventLoop"),
                config.allocator,
                config.readBufferSize,
                config.idleTimeoutMillis,
                config.flushCoalescing,
            )
            workerGroup = workers
            try {
                boss.start()
                workers.start()
            } catch (startFailure: Throwable) {
                // The group rolls its own loops back, and closing it again is a
                // no-op; what this adds is the case where the boss failed to
                // start and the group had not been asked yet.
                closeQuietly(startFailure) { workers.close() }
                throw startFailure
            }
        } catch (constructionFailure: Throwable) {
            closeQuietly(constructionFailure) { boss.close() }
            throw constructionFailure
        }
    }
}
