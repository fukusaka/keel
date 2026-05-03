package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.error
import io.github.fukusaka.keel.native.posix.AcceptResult
import io.github.fukusaka.keel.native.posix.NativeSocket
import io.github.fukusaka.keel.native.posix.NativeSocketOps
import io.github.fukusaka.keel.native.posix.PosixNativeSocket
import io.github.fukusaka.keel.native.posix.PosixNativeSocketOps
import io.github.fukusaka.keel.native.posix.applySocketOptions
import io.github.fukusaka.keel.native.posix.closeFdSafely
import io.github.fukusaka.keel.native.posix.errnoMessage
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.pipeline.PipelinedStreamServer
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * Pipeline server channel for epoll-based connection acceptance on Linux.
 *
 * Uses the boss [EpollEventLoop] to listen for incoming connections via
 * EPOLLIN on the server fd. Accepted connections are distributed to
 * worker EventLoops in round-robin.
 *
 * Same architecture as [KqueuePipelinedStreamServer][io.github.fukusaka.keel.engine.kqueue.KqueuePipelinedStreamServer].
 */
@OptIn(ExperimentalForeignApi::class)
internal class EpollPipelinedStreamServer(
    private val serverFd: Int,
    private val bossLoop: EpollEventLoop,
    private val workerGroup: EpollEventLoopGroup,
    private val localAddr: SocketAddress,
    private val logger: Logger,
    private val config: BindConfig,
    private val pipelineInitializer: (PipelinedChannel) -> Unit,
    private val nativeSocket: NativeSocket = PosixNativeSocket,
    private val nativeSocketOps: NativeSocketOps = PosixNativeSocketOps(logger),
) : PipelinedStreamServer, EpollEventLoop.FdReadyListener {

    override val localAddress: SocketAddress get() = localAddr
    override val isActive: Boolean get() = !closed

    @kotlin.concurrent.Volatile
    private var closed = false
    private var workerIndex = 0 // Single boss thread only — no atomicity needed.

    /** Starts accepting connections on the boss EventLoop. */
    fun start() {
        armAccept()
    }

    /**
     * [EpollEventLoop.FdReadyListener] dispatch — passing `this` to
     * [EpollEventLoop.registerCallback] avoids per-call lambda allocation on
     * the accept re-arm fast path. Only `READ` is registered; `WRITE` is
     * never armed for the listening fd.
     */
    override fun onReady(interest: EpollEventLoop.Interest) {
        onAcceptable()
    }

    private fun armAccept() {
        if (closed) return
        bossLoop.registerCallback(serverFd, EpollEventLoop.Interest.READ, this)
    }

    // `internal` (was `private`) so accept-branch seam tests can drive the
    // edge-triggered accept loop directly without going through epoll
    // readiness delivery. Call site in production remains the
    // `bossLoop.registerCallback` lambda armed by [armAccept].
    internal fun onAcceptable() {
        if (closed) return
        while (true) {
            when (val result = nativeSocket.accept(serverFd)) {
                is AcceptResult.Accepted -> {
                    nativeSocketOps.setNonBlocking(result.fd)
                    nativeSocketOps.applySocketOptions(result.fd, config.childSocketOptions)
                    dispatchToWorker(result.fd)
                }
                AcceptResult.WouldBlock -> {
                    armAccept()
                    return
                }
                is AcceptResult.Failed -> {
                    logger.error { "accept() failed: ${errnoMessage(result.errno)}" }
                    armAccept()
                    return
                }
            }
        }
    }

    private fun dispatchToWorker(clientFd: Int) {
        val idx = workerIndex++ % workerGroup.size
        val workerLoop = workerGroup.at(idx)
        workerLoop.dispatch(kotlin.coroutines.EmptyCoroutineContext, kotlinx.coroutines.Runnable {
            onWorkerAccept(clientFd, workerLoop)
        })
    }

    private fun onWorkerAccept(clientFd: Int, loop: EpollEventLoop) {
        val transport = EpollIoTransport(clientFd, loop, loop.allocator, nativeSocket)
        val channel = EpollPipelinedChannel(transport, logger)
        config.initializeConnection(channel)
        pipelineInitializer(channel)
        transport.readEnabled = true
    }

    /**
     * Stops accepting and closes the server socket fd.
     * Pending accept callbacks become no-ops. Idempotent.
     */
    override fun close() {
        if (closed) return
        closed = true
        closeFdSafely(serverFd, logger, "pipelined server close")
    }
}
