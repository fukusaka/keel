package io.github.fukusaka.keel.engine.epoll

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
 * epoll-based server channel for Linux.
 *
 * Delegates the edge-triggered accept loop + close-race state machine
 * to [AbstractPosixServer] and only provides the engine-specific
 * boss-loop registration and channel factory. See [AbstractPosixServer]
 * KDoc for the accept-path template and close semantics.
 *
 * ```
 * accept() flow:
 *   bossLoop: epoll_wait() fires EPOLLIN on serverFd → resume
 *   POSIX accept(serverFd) → clientFd
 *   workerGroup.next() → assign worker EventLoop
 *   → EpollPipelinedChannel(clientFd, transport, workerLoop, allocator)
 * ```
 *
 * @param serverFd    The listening server socket fd (non-blocking).
 * @param bossLoop    The boss [EpollEventLoop] for accept readiness notification.
 * @param workerGroup Worker EventLoopGroup for accepted channels.
 * @param localAddress Bind address of this server channel.
 */
@OptIn(ExperimentalForeignApi::class)
internal class EpollServer(
    serverFd: Int,
    private val bossLoop: EpollEventLoop,
    private val workerGroup: EpollEventLoopGroup,
    localAddress: SocketAddress,
    bindConfig: BindConfig,
    logger: Logger = io.github.fukusaka.keel.logging.NoopLoggerFactory.logger("EpollServer"),
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
        val transport = EpollIoTransport(clientFd, workerLoop, allocator, nativeSocket)
        return EpollPipelinedChannel(transport, logger, remoteAddr, localAddr)
    }

    override fun armReadReadiness(cont: CancellableContinuation<Unit>) {
        bossLoop.register(serverFd, EpollEventLoop.Interest.READ, cont)
    }

    override fun unregisterReadReadiness() {
        bossLoop.unregister(serverFd, EpollEventLoop.Interest.READ)
    }
}
