@file:OptIn(InternalReadinessEngineApi::class)

package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.core.StreamEngine
import io.github.fukusaka.keel.core.UnixSocketAddress
import io.github.fukusaka.keel.core.requireFilesystemOnly
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
 * kqueue-backed [StreamEngine] for macOS.
 *
 * Everything the engine does is [AbstractReadinessEngine]'s: the two readiness
 * engines' versions of this class were 694 and 675 lines that differed in the
 * Unix-address check below. What is here is that check, and the loops this
 * engine builds — construction stays with the concrete engine because the
 * rollback it needs runs before a base constructor could.
 */
class KqueueEngine(
    config: IoEngineConfig = IoEngineConfig(),
    nativeSocket: NativeSocket = PosixNativeSocket,
    nativeSocketOps: NativeSocketOps? = null,
    suspendRegisterOverride: ReadinessSuspendRegister? = null,
) : AbstractReadinessEngine("KqueueEngine", config, nativeSocket, nativeSocketOps, suspendRegisterOverride) {

    override val bossLoop: AbstractReadinessEventLoop
    override val workerGroup: AbstractReadinessEventLoopGroup<*>

    // Built one at a time so a failure gives back what came before it: a loop
    // holds a kqueue fd, a wakeup pipe, native scratch and an allocator child.
    //
    // Closing an unstarted loop is what makes the last stage's rollback work,
    // and it runs the teardown its thread would have.
    init {
        val loggerFactory = config.loggerFactory.guarded()
        val boss = KqueueEventLoop(loggerFactory.logger("KqueueEventLoop"))
        bossLoop = boss
        try {
            val workers = KqueueEventLoopGroup(
                resolveThreads(config),
                loggerFactory.logger("KqueueEventLoop"),
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

    /**
     * macOS has no abstract namespace, so an abstract-namespace Unix address
     * names nothing this kernel can bind or connect to.
     */
    override fun validateUnixAddress(address: UnixSocketAddress) {
        address.requireFilesystemOnly(
            "KqueueEngine does not support abstract-namespace Unix sockets (macOS kernel has no abstract namespace)",
        )
    }
}
