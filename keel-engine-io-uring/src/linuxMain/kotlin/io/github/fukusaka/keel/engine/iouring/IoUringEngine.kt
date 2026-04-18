package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.Channel
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.core.PipelinedServer
import io.github.fukusaka.keel.core.ServerChannel
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.core.StreamEngine
import io.github.fukusaka.keel.core.UnixSocketAddress
import io.github.fukusaka.keel.core.requireIpLiteral
import io.github.fukusaka.keel.logging.debug
import io.github.fukusaka.keel.native.posix.PosixSocketUtils
import io.github.fukusaka.keel.native.posix.closeFdSafely
import io.github.fukusaka.keel.native.posix.errnoMessage
import io.github.fukusaka.keel.native.posix.resolveForPosixSocket
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io_uring.io_uring_prep_connect
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import platform.posix.AF_INET
import platform.posix.sockaddr_in
import posix_socket.keel_inet_pton
import posix_socket.keel_init_sockaddr_in

/**
 * Linux io_uring-based [StreamEngine] implementation with multi-threaded EventLoop.
 *
 * Supports two server modes:
 *
 * **Coroutine mode** ([bind]/[connect]): boss EventLoop accepts via `IORING_OP_ACCEPT`
 * (multishot on Linux 5.19+) and distributes connections to workers in round-robin.
 * App drives I/O via suspend `read()`/`write()`/`flush()`.
 *
 * **Pipeline mode** ([bindPipeline]): each worker owns a private server socket
 * with `SO_REUSEPORT`. The kernel distributes connections by 4-tuple hash —
 * no boss EventLoop bottleneck. Handlers process data synchronously via callbacks.
 *
 * Each worker thread runs its own io_uring ring and acts as a
 * [CoroutineDispatcher][kotlinx.coroutines.CoroutineDispatcher], so all
 * I/O + request processing for a channel runs on a single thread.
 *
 * **Minimum kernel requirement**: Linux 5.1+ for io_uring basic support.
 * Optimal performance requires 5.19+ (multishot accept, provided buffer ring).
 *
 * @param config Engine-wide configuration. [IoEngineConfig.threads] controls
 *               the number of worker EventLoop threads. 0 (default) resolves
 *               to `availableProcessors()`.
 */
@OptIn(ExperimentalForeignApi::class)
class IoUringEngine(
    override val config: IoEngineConfig = IoEngineConfig(),
    private val writeModeSelector: IoModeSelector = IoModeSelectors.eagainThreshold(),
    capabilities: IoUringCapabilities? = null,
) : StreamEngine {

    override val coroutineContext: CoroutineContext = SupervisorJob()

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

    /**
     * Binds a suspend-based server on [host]:[port].
     *
     * Creates a server socket and returns an [IoUringServer] whose
     * [accept][IoUringServer.accept] returns [IoUringPipelinedChannel] instances.
     *
     * @throws IllegalStateException if the engine is closed.
     */
    override suspend fun bind(address: SocketAddress, bindConfig: BindConfig): ServerChannel = when (address) {
        is InetSocketAddress -> bindInet(address, bindConfig)
        is UnixSocketAddress -> throw UnsupportedOperationException(
            "IoUringEngine does not yet support UnixSocketAddress (Phase 11 PR C)",
        )
    }

    private suspend fun bindInet(address: InetSocketAddress, bindConfig: BindConfig): ServerChannel {
        check(!closed) { "Engine is closed" }

        val host = address.resolveForPosixSocket(config.resolver)
        val port = address.port
        val serverFd = PosixSocketUtils.createServerSocket(host, port, bindConfig.backlog)
        val localAddr = PosixSocketUtils.getLocalAddress(serverFd)
        logger.debug { "Bound to $localAddr" }
        return IoUringServer(
            serverFd, bossLoop, workerGroup, localAddr, bindConfig, writeModeSelector, resolvedCapabilities, logger,
        )
    }

    /**
     * Creates a TCP client connection via `IORING_OP_CONNECT`.
     *
     * Unlike epoll (which uses non-blocking `connect()` + EPOLLOUT), io_uring
     * handles the async connect natively. The SQE carries the full sockaddr
     * and completes when the connection is established (CQE.res=0) or fails.
     *
     * @throws IllegalStateException if the engine is closed or the address is invalid.
     */
    override suspend fun connect(address: SocketAddress): Channel = when (address) {
        is InetSocketAddress -> connectInet(address)
        is UnixSocketAddress -> throw UnsupportedOperationException(
            "IoUringEngine does not yet support UnixSocketAddress (Phase 11 PR C)",
        )
    }

    private suspend fun connectInet(address: InetSocketAddress): Channel {
        check(!closed) { "Engine is closed" }

        val host = address.resolveForPosixSocket(config.resolver)
        val port = address.port
        val fd = PosixSocketUtils.createUnconnectedSocket()
        val wi = workerGroup.nextIndex()
        val workerLoop = workerGroup.loopAt(wi)
        val allocator = workerGroup.allocatorAt(wi)

        val res: Int
        try {
            res = memScoped {
                val addr = alloc<sockaddr_in>()
                keel_init_sockaddr_in(addr.ptr, port.toUShort())
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
            closeFdSafely(fd, logger, "connect cleanup")
            throw e
        }

        if (res < 0) {
            closeFdSafely(fd, logger, "connect cleanup")
            error("connect() failed: ${errnoMessage(-res)}")
        }

        val remoteAddr = PosixSocketUtils.getRemoteAddress(fd)
        val localAddr = PosixSocketUtils.getLocalAddress(fd)
        val bufferRing = workerGroup.bufferRingAt(wi)
        val fileRegistry = workerGroup.fileRegistryAt(wi)
        val bufferTable = workerGroup.bufferTableAt(wi)
        // Construct the transport on the worker EventLoop pthread so
        // `FixedFileRegistry.register(fd)` (invoked from the transport
        // constructor's property initialiser) runs on the submitter task.
        val transport = withContext(workerLoop) {
            IoUringIoTransport(fd, workerLoop, resolvedCapabilities, writeModeSelector, allocator, bufferRing, fileRegistry, bufferTable)
        }
        logger.debug { "Connected to $remoteAddr" }
        return IoUringPipelinedChannel(transport, logger, remoteAddr, localAddr)
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
     * @return A [PipelinedServer] for lifecycle management.
     * @throws IllegalStateException if the engine is closed.
     */
    override fun bindPipeline(
        address: SocketAddress,
        config: BindConfig,
        pipelineInitializer: (PipelinedChannel) -> Unit,
    ): PipelinedServer = when (address) {
        is InetSocketAddress -> bindPipelineInet(address, config, pipelineInitializer)
        is UnixSocketAddress -> throw UnsupportedOperationException(
            "IoUringEngine does not yet support UnixSocketAddress (Phase 11 PR C)",
        )
    }

    private fun bindPipelineInet(
        address: InetSocketAddress,
        config: BindConfig,
        pipelineInitializer: (PipelinedChannel) -> Unit,
    ): PipelinedServer {
        check(!closed) { "Engine is closed" }

        val host = address.requireIpLiteral()
        val port = address.port
        val serverFds = IntArray(workerGroup.size) {
            PosixSocketUtils.createReusePortServerSocket(host, port, config.backlog)
        }
        // All fds bind to the same address (SO_REUSEPORT); [0] is representative.
        val localAddr = PosixSocketUtils.getLocalAddress(serverFds[0])
        val server = IoUringPipelinedServerChannel(
            workerGroup, serverFds, localAddr, config, pipelineInitializer, resolvedCapabilities, logger,
        )
        server.start()
        logger.debug { "Pipeline server bound to $host:$port (${workerGroup.size} workers)" }
        return server
    }

    /**
     * Closes the engine, stopping both boss and worker EventLoops.
     *
     * Does NOT close existing channels — caller is responsible for closing
     * active connections before shutting down the engine. Idempotent.
     */
    override suspend fun close() {
        if (!closed) {
            closed = true
            coroutineContext.job.cancelAndJoin()
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
