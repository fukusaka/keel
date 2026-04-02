package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.core.Channel
import io.github.fukusaka.keel.core.IoEngine
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.core.ServerChannel
import io.github.fukusaka.keel.logging.debug
import io.github.fukusaka.keel.pipeline.ChannelPipeline
import io_uring.io_uring_prep_connect
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import platform.posix.AF_INET
import platform.posix.errno
import platform.posix.sockaddr_in
import platform.posix.strerror
import io_uring.keel_htons
import io_uring.keel_inet_pton

/**
 * Linux io_uring-based [IoEngine] implementation with multi-threaded EventLoop.
 *
 * Uses a boss/worker EventLoop model (same as [EpollEngine][io.github.fukusaka.keel.engine.epoll.EpollEngine]):
 * - **Boss EventLoop**: handles `accept()` operations on server fds via `IORING_OP_ACCEPT`
 * - **Worker EventLoopGroup**: handles `read`/`write`/`flush` on accepted channels
 *
 * New connections are assigned to worker EventLoops in round-robin order.
 * Each worker thread runs its own io_uring ring and acts as a
 * [CoroutineDispatcher][kotlinx.coroutines.CoroutineDispatcher], so all
 * I/O + request processing for a channel runs on a single thread.
 *
 * **Minimum kernel requirement**: Linux 5.1+ for io_uring basic support.
 * Optimal performance requires 5.19+ (multishot accept, deferred accept).
 *
 * ```
 * IoUringEngine
 *   |
 *   +-- bossLoop (accept EventLoop, dedicated io_uring ring)
 *   |     |
 *   |     +-- bind() → IoUringServerChannel
 *   |           |
 *   |           +-- accept() → IORING_OP_ACCEPT → assign to workerGroup[nextIndex]
 *   |
 *   +-- workerGroup (N worker EventLoops, round-robin)
 *         |
 *         +-- worker[0]: Channel A, D, ...
 *         +-- worker[1]: Channel B, E, ...
 * ```
 *
 * @param config Engine-wide configuration. [IoEngineConfig.threads] controls
 *               the number of worker EventLoop threads. 0 (default) resolves
 *               to `availableProcessors()`.
 */
@OptIn(ExperimentalForeignApi::class)
class IoUringEngine(
    private val config: IoEngineConfig = IoEngineConfig(),
    private val writeModeSelector: IoModeSelector = IoModeSelectors.eagainThreshold(),
    capabilities: IoUringCapabilities? = null,
) : IoEngine {

    private val logger = config.loggerFactory.logger("IoUringEngine")
    private val resolvedCapabilities: IoUringCapabilities
    private val bossLoop: IoUringEventLoop
    private val workerGroup: IoUringEventLoopGroup
    private var closed = false

    init {
        // Detect capabilities using kernel version. Opcode probe (SEND_ZC)
        // requires a ring, so create boss loop first with default capabilities,
        // then probe from its ring.
        val defaultCaps = capabilities ?: run {
            val kv = KernelVersion.current()
            IoUringCapabilities(
                multishotAccept = kv >= KernelVersion(5, 19),
                multishotRecv = kv >= KernelVersion(6, 0),
                providedBufferRing = kv >= KernelVersion(5, 19),
                // Preliminary: will be refined by opcode probe below.
                sendZc = kv >= KernelVersion(6, 0),
            )
        }

        bossLoop = IoUringEventLoop(config.loggerFactory.logger("IoUringEventLoop"), defaultCaps)

        // Refine sendZc via opcode probe if auto-detecting.
        resolvedCapabilities = if (capabilities != null) {
            defaultCaps
        } else {
            val probed = IoUringCapabilities.detect(bossLoop.ringPtr)
            defaultCaps.copy(sendZc = probed.sendZc)
        }

        workerGroup = IoUringEventLoopGroup(
            size = resolveThreads(config),
            logger = config.loggerFactory.logger("IoUringEventLoop"),
            allocator = config.allocator,
            capabilities = resolvedCapabilities,
        )

        bossLoop.start()
        workerGroup.start()
    }

    override suspend fun bind(host: String, port: Int): ServerChannel {
        check(!closed) { "Engine is closed" }

        val serverFd = SocketUtils.createServerSocket(host, port)
        val localAddr = SocketUtils.getLocalAddress(serverFd)
        logger.debug { "Bound to ${localAddr.host}:${localAddr.port}" }
        return IoUringServerChannel(
            serverFd, bossLoop, workerGroup, localAddr, writeModeSelector, resolvedCapabilities,
        )
    }

    /**
     * Creates a TCP client connection via `IORING_OP_CONNECT`.
     *
     * Unlike epoll (which uses non-blocking `connect()` + EPOLLOUT), io_uring
     * handles the async connect natively. The SQE carries the full sockaddr
     * and completes when the connection is established (CQE.res=0) or fails.
     */
    override suspend fun connect(host: String, port: Int): Channel {
        check(!closed) { "Engine is closed" }

        val fd = SocketUtils.createUnconnectedSocket()
        val wi = workerGroup.nextIndex()
        val workerLoop = workerGroup.loopAt(wi)
        val allocator = workerGroup.allocatorAt(wi)

        val res: Int
        try {
            res = memScoped {
                val addr = alloc<sockaddr_in>()
                addr.sin_family = AF_INET.convert()
                addr.sin_port = keel_htons(port.toUShort())
                val rc = keel_inet_pton(AF_INET, host, addr.sin_addr.ptr)
                check(rc == 1) { "Invalid address: $host" }

                workerLoop.submitAndAwait { sqe ->
                    io_uring_prep_connect(
                        sqe, fd,
                        addr.ptr.reinterpret(),
                        sizeOf<sockaddr_in>().convert(),
                    )
                }
            }
        } catch (e: Throwable) {
            platform.posix.close(fd)
            throw e
        }

        if (res < 0) {
            platform.posix.close(fd)
            error("connect() failed: ${strerror(-res)?.toKString()} (errno=${-res})")
        }

        val remoteAddr = SocketUtils.getRemoteAddress(fd)
        val localAddr = SocketUtils.getLocalAddress(fd)
        val bufferRing = workerGroup.bufferRingAt(wi)
        val transport = IoUringIoTransport(fd, workerLoop, resolvedCapabilities, writeModeSelector)
        logger.debug { "Connected to ${remoteAddr.host}:${remoteAddr.port}" }
        return IoUringChannel(
            fd, workerLoop, transport, allocator, bufferRing, remoteAddr, localAddr,
            resolvedCapabilities,
        )
    }

    /**
     * Creates a pipeline-based server with SO_REUSEPORT multi-thread accept.
     *
     * Each worker EventLoop owns a private server socket. The kernel
     * distributes incoming connections across workers by 4-tuple hash.
     * For each connection, [pipelineInitializer] is called to set up the
     * handler chain, then multishot recv is armed for zero-suspend I/O.
     *
     * Unlike [bind] (which returns a suspend-based [ServerChannel]), this
     * method creates a fully callback-driven server with no coroutine overhead.
     *
     * @param host Bind address (e.g., "0.0.0.0").
     * @param port Port number.
     * @param pipelineInitializer Called per accepted connection to add handlers.
     * @return Server handle for lifecycle management.
     */
    fun bindPipeline(
        host: String,
        port: Int,
        pipelineInitializer: (ChannelPipeline) -> Unit,
    ): AutoCloseable {
        check(!closed) { "Engine is closed" }

        val serverFds = IntArray(workerGroup.size) {
            SocketUtils.createReusePortServerSocket(host, port)
        }
        val server = IoUringPipelinedServerChannel(
            workerGroup, serverFds, pipelineInitializer, resolvedCapabilities, logger,
        )
        server.start()
        logger.debug { "Pipeline server bound to $host:$port (${workerGroup.size} workers)" }
        return server
    }

    override fun close() {
        if (!closed) {
            closed = true
            bossLoop.close()
            workerGroup.close()
            logger.debug { "Engine closed" }
        }
    }

    companion object {
        /** Resolves threads=0 to available CPU cores. */
        @OptIn(kotlin.experimental.ExperimentalNativeApi::class)
        private fun resolveThreads(config: IoEngineConfig): Int =
            if (config.threads > 0) config.threads
            else kotlin.native.Platform.getAvailableProcessors()
    }
}
