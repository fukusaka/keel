package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.PipelinedServer
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.error
import io.github.fukusaka.keel.native.posix.PosixSocketUtils
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.EAGAIN
import platform.posix.EWOULDBLOCK
import platform.posix.accept
import platform.posix.close
import platform.posix.errno

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
            val clientFd = accept(serverFd, null, null)
            if (clientFd < 0) {
                val err = errno
                if (err == EAGAIN || err == EWOULDBLOCK) break
                logger.error { "accept() failed: errno=$err" }
                break
            }
            PosixSocketUtils.setNonBlocking(clientFd)
            dispatchToWorker(clientFd)
        }
        armAccept()
    }

    private fun dispatchToWorker(clientFd: Int) {
        val idx = workerIndex++ % workerGroup.size
        val (workerLoop, allocator) = workerGroup.at(idx)
        workerLoop.dispatch(kotlin.coroutines.EmptyCoroutineContext, kotlinx.coroutines.Runnable {
            onWorkerAccept(clientFd, workerLoop, allocator)
        })
    }

    private fun onWorkerAccept(clientFd: Int, loop: EpollEventLoop, allocator: BufferAllocator) {
        val transport = EpollIoTransport(clientFd, loop)
        val channel = EpollPipelinedChannel(clientFd, transport, loop, allocator, logger)
        config.initializeConnection(channel)
        pipelineInitializer(channel)
        channel.armRead()
    }

    /**
     * Stops accepting and closes the server socket fd.
     * Pending accept callbacks become no-ops. Idempotent.
     */
    override fun close() {
        if (closed) return
        closed = true
        close(serverFd)
    }
}
