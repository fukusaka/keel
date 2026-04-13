package io.github.fukusaka.keel.engine.kqueue

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
 * Pipeline server channel for kqueue-based connection acceptance.
 *
 * Uses the boss [KqueueEventLoop] to listen for incoming connections via
 * EVFILT_READ on the server fd. Accepted connections are distributed to
 * worker EventLoops in round-robin, where each creates a
 * [KqueuePipelinedChannel] and arms read callbacks.
 *
 * Unlike [KqueueServer] (suspend-based), this server channel uses
 * callback-based registration for non-suspend pipeline processing.
 *
 * ```
 * Boss EventLoop:
 *   kevent(EVFILT_READ on serverFd) → accept() → clientFd
 *     → dispatch to worker EventLoop
 *
 * Worker EventLoop:
 *   KqueuePipelinedChannel(clientFd) → pipelineInitializer → armRead()
 * ```
 */
@OptIn(ExperimentalForeignApi::class)
internal class KqueuePipelinedServerChannel(
    private val serverFd: Int,
    private val bossLoop: KqueueEventLoop,
    private val workerGroup: KqueueEventLoopGroup,
    private val localAddr: SocketAddress,
    private val logger: Logger,
    private val config: BindConfig,
    private val pipelineInitializer: (PipelinedChannel) -> Unit,
) : PipelinedServer {

    override val localAddress: SocketAddress get() = localAddr
    override val isActive: Boolean get() = !closed

    @kotlin.concurrent.Volatile
    private var closed = false
    private var workerIndex = 0

    /**
     * Starts accepting connections on the boss EventLoop.
     *
     * Must be called after the boss EventLoop is started. Each accepted
     * connection is dispatched to the next worker in round-robin order.
     */
    fun start() {
        armAccept()
    }

    private fun armAccept() {
        if (closed) return
        bossLoop.registerCallback(serverFd, KqueueEventLoop.Interest.READ) {
            onAcceptable()
        }
    }

    private fun onAcceptable() {
        if (closed) return
        // Accept all pending connections in a loop (edge-triggered behavior).
        while (true) {
            val clientFd = accept(serverFd, null, null)
            if (clientFd < 0) {
                val err = errno
                if (err == EAGAIN || err == EWOULDBLOCK) break
                // Transient error — log and continue accepting.
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

    private fun onWorkerAccept(clientFd: Int, loop: KqueueEventLoop, allocator: BufferAllocator) {
        val transport = KqueueIoTransport(clientFd, loop, allocator)
        val channel = KqueuePipelinedChannel(transport, logger)
        config.initializeConnection(channel)
        pipelineInitializer(channel)
        transport.readEnabled = true
    }

    /**
     * Stops accepting and closes the server socket fd.
     *
     * Pending accept callbacks become no-ops (closed flag check).
     * Does NOT close worker EventLoops or existing client channels —
     * caller (typically [KqueueEngine.close]) is responsible. Idempotent.
     */
    override fun close() {
        if (closed) return
        closed = true
        close(serverFd)
    }
}
