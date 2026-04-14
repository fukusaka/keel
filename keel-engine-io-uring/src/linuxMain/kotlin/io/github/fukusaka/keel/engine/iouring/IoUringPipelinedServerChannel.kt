package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.PipelinedServer
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.native.posix.PosixSocketUtils
import io.github.fukusaka.keel.native.posix.closeFdSafely
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io_uring.io_uring_prep_multishot_accept
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Pipeline-based server that distributes connections across [IoUringEventLoopGroup]
 * workers using `SO_REUSEPORT`.
 *
 * Each worker EventLoop owns a private server socket (all bound to the same
 * address via `SO_REUSEPORT`). The kernel distributes incoming connections
 * across these sockets by hashing the connection 4-tuple, eliminating
 * cross-thread accept coordination.
 *
 * For each accepted connection, the server creates an [IoUringPipelinedChannel],
 * applies the user-provided [pipelineInitializer] to set up the handler chain,
 * and arms multishot recv. All subsequent I/O is zero-suspend: CQE callbacks
 * drive the pipeline directly on the EventLoop thread.
 *
 * ```
 * Worker[0]: ServerSocket[0] → multishot accept → PipelinedChannel → pipeline
 * Worker[1]: ServerSocket[1] → multishot accept → PipelinedChannel → pipeline
 * Worker[N]: ServerSocket[N] → multishot accept → PipelinedChannel → pipeline
 * ```
 *
 * @param workerGroup      The EventLoop group providing per-worker resources.
 * @param serverFds        One server socket fd per worker (SO_REUSEPORT).
 * @param pipelineInitializer Called for each accepted connection to add handlers.
 * @param capabilities     Runtime kernel feature flags.
 * @param logger           Logger for pipeline error reporting.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IoUringPipelinedServerChannel(
    private val workerGroup: IoUringEventLoopGroup,
    private val serverFds: IntArray,
    private val localAddr: SocketAddress,
    private val config: BindConfig,
    private val pipelineInitializer: (PipelinedChannel) -> Unit,
    private val capabilities: IoUringCapabilities,
    private val logger: Logger,
) : PipelinedServer {

    override val localAddress: SocketAddress get() = localAddr
    override val isActive: Boolean get() = !closed

    private var closed = false

    /**
     * Arms multishot accept on each worker EventLoop.
     *
     * Must be called after construction. Each worker starts accepting
     * connections on its own SO_REUSEPORT server socket.
     */
    fun start() {
        for (i in 0 until workerGroup.size) {
            val loop = workerGroup.loopAt(i)
            val serverFd = serverFds[i]
            loop.dispatch(EmptyCoroutineContext, kotlinx.coroutines.Runnable {
                loop.submitMultishot(
                    prepare = { sqe ->
                        io_uring_prep_multishot_accept(sqe, serverFd, null, null, 0)
                    },
                    onCqe = { res, _ ->
                        if (res >= 0 && !closed) {
                            onAccept(res, i)
                        }
                    },
                )
            })
        }
    }

    /**
     * Called on the worker EventLoop thread when a new connection is accepted.
     *
     * Creates [IoUringPipelinedChannel], applies the pipeline initializer
     * to set up handlers, and arms multishot recv.
     */
    private fun onAccept(clientFd: Int, workerIndex: Int) {
        PosixSocketUtils.setNonBlocking(clientFd)
        val loop = workerGroup.loopAt(workerIndex)
        val bufferRing = workerGroup.bufferRingAt(workerIndex)
            ?: error("Pipeline requires provided buffer ring (kernel 5.19+)")
        val allocator = workerGroup.allocatorAt(workerIndex)
        val fileRegistry = workerGroup.fileRegistryAt(workerIndex)
        val bufferTable = workerGroup.bufferTableAt(workerIndex)
        val transport = IoUringIoTransport(clientFd, loop, capabilities, allocator = allocator, bufferRing = bufferRing, fixedFileRegistry = fileRegistry, registeredBufferTable = bufferTable)
        val channel = IoUringPipelinedChannel(transport, logger)
        config.initializeConnection(channel)
        pipelineInitializer(channel)
        transport.readEnabled = true
    }

    /**
     * Closes all server sockets.
     *
     * Active connections continue until they close naturally.
     * Idempotent.
     */
    override fun close() {
        if (!closed) {
            closed = true
            for (fd in serverFds) {
                closeFdSafely(fd, logger, "pipelined server close")
            }
        }
    }
}
