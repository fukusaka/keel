package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.core.Channel
import io.github.fukusaka.keel.core.ServerChannel
import io.github.fukusaka.keel.core.SocketAddress
import io_uring.io_uring_prep_accept
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException

/**
 * io_uring-based [ServerChannel] implementation for Linux.
 *
 * Each [accept] call submits a single `IORING_OP_ACCEPT` SQE and suspends
 * until the CQE delivers the accepted client fd. The fd is returned directly
 * in `CQE.res`, so no subsequent POSIX `accept()` syscall is needed.
 *
 * ```
 * accept() flow:
 *   bossLoop: submitAndAwait { sqe -> io_uring_prep_accept(sqe, serverFd) }
 *   CQE arrives with res = clientFd
 *   workerGroup.nextIndex() → assign worker EventLoop
 *   → IoUringChannel(clientFd, workerLoop, allocator)
 * ```
 *
 * **Single-shot vs multishot accept**: This implementation uses single-shot
 * `IORING_OP_ACCEPT` (one SQE per accepted connection). Multishot accept
 * (`IORING_ACCEPT_MULTISHOT`, Linux 5.19+) is deferred to a follow-up
 * optimisation because it requires delivering client fds through a channel
 * rather than resuming a single continuation.
 *
 * @param serverFd    The listening server socket fd (non-blocking).
 * @param bossLoop    The boss [IoUringEventLoop] for accept operations.
 * @param workerGroup Worker EventLoopGroup for accepted channels.
 * @param localAddress Bind address of this server channel.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IoUringServerChannel(
    private val serverFd: Int,
    private val bossLoop: IoUringEventLoop,
    private val workerGroup: IoUringEventLoopGroup,
    override val localAddress: SocketAddress,
) : ServerChannel {

    private var _active = true

    override val isActive: Boolean get() = _active

    /**
     * Suspends until an incoming connection arrives, then returns the channel.
     *
     * Submits `IORING_OP_ACCEPT` and waits for the CQE. The CQE result is
     * the accepted client fd (>= 0) or a negative errno on error.
     */
    override suspend fun accept(): Channel {
        check(_active) { "ServerChannel is closed" }

        val clientFd = bossLoop.submitAndAwait { sqe ->
            // null addr/addrlen: we retrieve the remote address via getpeername
            // after accept, avoiding the need for a sockaddr allocation per accept.
            io_uring_prep_accept(sqe, serverFd, null, null, 0)
        }

        if (clientFd < 0) {
            if (!_active) throw CancellationException("ServerChannel closed")
            error("io_uring accept failed: errno=${-clientFd}")
        }

        try {
            SocketUtils.setNonBlocking(clientFd)
            val remoteAddr = SocketUtils.getRemoteAddress(clientFd)
            val localAddr = SocketUtils.getLocalAddress(clientFd)
            val wi = workerGroup.nextIndex()
            return IoUringChannel(clientFd, workerGroup.loopAt(wi), workerGroup.allocatorAt(wi), remoteAddr, localAddr)
        } catch (e: Throwable) {
            platform.posix.close(clientFd)
            throw e
        }
    }

    /**
     * Closes the server channel and stops accepting connections.
     * Idempotent; subsequent calls are no-ops.
     */
    override fun close() {
        if (_active) {
            _active = false
            platform.posix.close(serverFd)
        }
    }
}
