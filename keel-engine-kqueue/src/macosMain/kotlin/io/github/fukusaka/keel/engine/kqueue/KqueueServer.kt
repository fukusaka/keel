package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.native.posix.AbstractPosixServer
import io.github.fukusaka.keel.native.posix.NativeSocket
import io.github.fukusaka.keel.native.posix.NativeSocketOps
import io.github.fukusaka.keel.native.posix.PosixNativeSocket
import io.github.fukusaka.keel.native.posix.PosixNativeSocketOps
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellableContinuation

/**
 * kqueue-based server channel for macOS.
 *
 * Delegates the edge-triggered accept loop + close-race state machine
 * to [AbstractPosixServer] and only provides the engine-specific
 * boss-loop registration and channel factory. See [AbstractPosixServer]
 * KDoc for the accept-path template and close semantics.
 *
 * ```
 * accept() flow:
 *   bossLoop: kevent() fires EVFILT_READ on serverFd → resume
 *   POSIX accept(serverFd) → clientFd
 *   workerGroup.next() → assign worker EventLoop
 *   → KqueuePipelinedChannel(clientFd, transport, workerLoop, allocator)
 * ```
 *
 * @param serverFd    The listening server socket fd (non-blocking).
 * @param bossLoop    The boss [KqueueEventLoop] for accept readiness notification.
 * @param workerGroup Worker EventLoopGroup for accepted channels.
 * @param localAddress Bind address of this server channel.
 */
@OptIn(ExperimentalForeignApi::class)
internal class KqueueServer(
    serverFd: Int,
    private val bossLoop: KqueueEventLoop,
    private val workerGroup: KqueueEventLoopGroup,
    localAddress: SocketAddress,
    bindConfig: BindConfig,
    logger: Logger = io.github.fukusaka.keel.logging.NoopLoggerFactory.logger("KqueueServer"),
    nativeSocket: NativeSocket = PosixNativeSocket,
    nativeSocketOps: NativeSocketOps = PosixNativeSocketOps,
) : AbstractPosixServer(
    serverFd = serverFd,
    localAddress = localAddress,
    bindConfig = bindConfig,
    logger = logger,
    nativeSocket = nativeSocket,
    nativeSocketOps = nativeSocketOps,
) {

    override fun buildChannel(
        clientFd: Int,
        remoteAddr: SocketAddress,
        localAddr: SocketAddress,
    ): PipelinedChannel {
        val (workerLoop, allocator) = workerGroup.next()
        val transport = KqueueIoTransport(clientFd, workerLoop, allocator, nativeSocket)
        return KqueuePipelinedChannel(transport, logger, remoteAddr, localAddr)
    }

    override fun armReadReadiness(cont: CancellableContinuation<Unit>) {
        bossLoop.register(serverFd, KqueueEventLoop.Interest.READ, cont)
    }

    override fun unregisterReadReadiness() {
        bossLoop.unregister(serverFd, KqueueEventLoop.Interest.READ)
    }
}
