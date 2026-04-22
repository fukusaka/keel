package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.PipelinedServer
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.error
import io.github.fukusaka.keel.native.posix.AcceptResult
import io.github.fukusaka.keel.native.posix.NativeSocket
import io.github.fukusaka.keel.native.posix.PosixNativeSocket
import io.github.fukusaka.keel.native.posix.PosixSocketUtils
import io.github.fukusaka.keel.native.posix.closeFdSafely
import io.github.fukusaka.keel.native.posix.errnoMessage
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * Pipeline server channel for epoll-based connection acceptance on Linux.
 *
 * Uses the boss [EpollEventLoop] to listen for incoming connections via
 * EPOLLIN on the server fd. Accepted connections are distributed to
 * worker EventLoops in round-robin.
 *
 * Same architecture as [KqueuePipelinedServerChannel][io.github.fukusaka.keel.engine.kqueue.KqueuePipelinedServerChannel].
 */
@OptIn(ExperimentalForeignApi::class)
internal class EpollPipelinedServerChannel(
    private val serverFd: Int,
    private val bossLoop: EpollEventLoop,
    private val workerGroup: EpollEventLoopGroup,
    private val localAddr: SocketAddress,
    private val logger: Logger,
    private val config: BindConfig,
    private val pipelineInitializer: (PipelinedChannel) -> Unit,
    private val nativeSocket: NativeSocket = PosixNativeSocket,
) : PipelinedServer {

    override val localAddress: SocketAddress get() = localAddr
    override val isActive: Boolean get() = !closed

    @kotlin.concurrent.Volatile
    private var closed = false
    private var workerIndex = 0 // Single boss thread only — no atomicity needed.

    /** Starts accepting connections on the boss EventLoop. */
    fun start() {
        armAccept()
    }

    private fun armAccept() {
        if (closed) return
        bossLoop.registerCallback(serverFd, EpollEventLoop.Interest.READ) {
            onAcceptable()
        }
    }

    private fun onAcceptable() {
        if (closed) return
        while (true) {
            when (val result = nativeSocket.accept(serverFd)) {
                is AcceptResult.Accepted -> {
                    PosixSocketUtils.setNonBlocking(result.fd)
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
        val (workerLoop, allocator) = workerGroup.at(idx)
        workerLoop.dispatch(kotlin.coroutines.EmptyCoroutineContext, kotlinx.coroutines.Runnable {
            onWorkerAccept(clientFd, workerLoop, allocator)
        })
    }

    private fun onWorkerAccept(clientFd: Int, loop: EpollEventLoop, allocator: BufferAllocator) {
        val transport = EpollIoTransport(clientFd, loop, allocator, nativeSocket)
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
