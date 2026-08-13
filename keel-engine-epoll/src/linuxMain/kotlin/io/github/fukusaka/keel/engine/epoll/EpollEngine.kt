@file:OptIn(InternalPosixEventLoopApi::class)

package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.core.StreamEngine
import io.github.fukusaka.keel.logging.guarded
import io.github.fukusaka.keel.native.posix.AbstractPosixEngine
import io.github.fukusaka.keel.native.posix.AbstractPosixEventLoopGroup
import io.github.fukusaka.keel.native.posix.AbstractPosixReadinessEventLoop
import io.github.fukusaka.keel.native.posix.InternalPosixEventLoopApi
import io.github.fukusaka.keel.native.posix.NativeSocket
import io.github.fukusaka.keel.native.posix.NativeSocketOps
import io.github.fukusaka.keel.native.posix.PosixNativeSocket
import io.github.fukusaka.keel.native.posix.PosixSuspendRegister

/**
 * epoll-backed [StreamEngine] for Linux.
 *
 * Everything the engine does is [AbstractPosixEngine]'s: the two readiness
 * engines' versions of this class were 675 and 694 lines that differed in one
 * platform check, which Linux does not need — it has an abstract namespace.
 * What is here is the loops this engine builds, because the rollback their
 * construction needs runs before a base constructor could.
 */
class EpollEngine(
    config: IoEngineConfig = IoEngineConfig(),
    nativeSocket: NativeSocket = PosixNativeSocket,
    nativeSocketOps: NativeSocketOps? = null,
    suspendRegisterOverride: PosixSuspendRegister? = null,
) : AbstractPosixEngine(config, nativeSocket, nativeSocketOps, suspendRegisterOverride) {

    override val bossLoop: AbstractPosixReadinessEventLoop
    override val workerGroup: AbstractPosixEventLoopGroup<*>

    // Built one at a time so a failure gives back what came before it: a loop
    // holds an epoll fd, a wakeup eventfd, native scratch and an allocator
    // child.
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
