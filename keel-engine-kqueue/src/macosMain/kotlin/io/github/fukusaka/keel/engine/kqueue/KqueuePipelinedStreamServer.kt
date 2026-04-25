package io.github.fukusaka.keel.engine.kqueue

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
 * Pipeline server channel for kqueue-based connection acceptance.
 *
 * Uses the boss [KqueueEventLoop] to listen for incoming connections via
 * EVFILT_READ on the server fd. Accepted connections are distributed to
 * worker EventLoops in round-robin, where each creates a
 * [KqueuePipelinedChannel] and arms read callbacks.
 *
 * Unlike [KqueueStreamServer] (suspend-based), this server channel uses
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
internal class KqueuePipelinedStreamServer(
    private val serverFd: Int,
    private val bossLoop: KqueueEventLoop,
    private val workerGroup: KqueueEventLoopGroup,
    private val localAddr: SocketAddress,
    private val logger: Logger,
    private val config: BindConfig,
    private val pipelineInitializer: (PipelinedChannel) -> Unit,
    private val nativeSocket: NativeSocket = PosixNativeSocket,
    private val nativeSocketOps: NativeSocketOps = PosixNativeSocketOps,
) : PipelinedStreamServer, KqueueEventLoop.FdReadyListener {

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

    /**
     * [KqueueEventLoop.FdReadyListener] dispatch — passing `this` to
     * [KqueueEventLoop.registerCallback] avoids per-call lambda allocation
     * on the accept re-arm fast path. Only `READ` is registered; `WRITE` is
     * never armed for the listening fd.
     */
    override fun onReady(interest: KqueueEventLoop.Interest) {
        onAcceptable()
    }

    private fun armAccept() {
        if (closed) return
        bossLoop.registerCallback(serverFd, KqueueEventLoop.Interest.READ, this)
    }

    // `internal` (was `private`) so accept-branch seam tests can drive the
    // edge-triggered accept loop directly without going through kqueue
    // readiness delivery. Call site in production remains the
    // `bossLoop.registerCallback` lambda armed by [armAccept].
    internal fun onAcceptable() {
        if (closed) return
        // Accept all pending connections in a loop (edge-triggered behavior).
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
                    // Transient error — log and continue accepting.
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

    private fun onWorkerAccept(clientFd: Int, loop: KqueueEventLoop) {
        val transport = KqueueIoTransport(clientFd, loop, loop.allocator, nativeSocket)
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
        closeFdSafely(serverFd, logger, "pipelined server close")
    }
}
