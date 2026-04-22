package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.native.posix.AbstractPosixPipelinedServerChannel
import io.github.fukusaka.keel.native.posix.NativeSocket
import io.github.fukusaka.keel.native.posix.NativeSocketOps
import io.github.fukusaka.keel.native.posix.PosixNativeSocket
import io.github.fukusaka.keel.native.posix.PosixNativeSocketOps
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * Pipeline server channel for epoll-based connection acceptance.
 *
 * Delegates the edge-triggered accept loop + `@Volatile closed` state
 * machine to [AbstractPosixPipelinedServerChannel] and only provides
 * the engine-specific arm hook and worker-side dispatch. See
 * [AbstractPosixPipelinedServerChannel] KDoc for the accept-path
 * template and close semantics.
 *
 * ```
 * Boss EventLoop:
 *   epoll_wait(EPOLLIN on serverFd) → accept() → clientFd
 *     → dispatch to worker EventLoop
 *
 * Worker EventLoop:
 *   EpollPipelinedChannel(clientFd) → pipelineInitializer → armRead()
 * ```
 */
@OptIn(ExperimentalForeignApi::class)
internal class EpollPipelinedServerChannel(
    serverFd: Int,
    private val bossLoop: EpollEventLoop,
    private val workerGroup: EpollEventLoopGroup,
    localAddr: SocketAddress,
    logger: Logger,
    config: BindConfig,
    pipelineInitializer: (PipelinedChannel) -> Unit,
    nativeSocket: NativeSocket = PosixNativeSocket,
    nativeSocketOps: NativeSocketOps = PosixNativeSocketOps,
) : AbstractPosixPipelinedServerChannel(
    serverFd = serverFd,
    localAddr = localAddr,
    config = config,
    logger = logger,
    pipelineInitializer = pipelineInitializer,
    nativeSocket = nativeSocket,
    nativeSocketOps = nativeSocketOps,
) {

    private var workerIndex = 0

    override fun registerReadyCallback(callback: () -> Unit) {
        bossLoop.registerCallback(serverFd, EpollEventLoop.Interest.READ, callback)
    }

    override fun dispatchToWorker(clientFd: Int) {
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
}
