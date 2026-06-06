package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.Channel
import io.github.fukusaka.keel.core.ConnectConfig
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.core.IpAddress
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.core.SocketOptions
import io.github.fukusaka.keel.core.StreamEngine
import io.github.fukusaka.keel.core.StreamServer
import io.github.fukusaka.keel.core.UnixSocketAddress
import io.github.fukusaka.keel.core.connectWithFallback
import io.github.fukusaka.keel.core.requireIp
import io.github.fukusaka.keel.core.resolveFirst
import io.github.fukusaka.keel.logging.debug
import io.github.fukusaka.keel.native.posix.ConnectResult
import io.github.fukusaka.keel.native.posix.NativeSocket
import io.github.fukusaka.keel.native.posix.NativeSocketOps
import io.github.fukusaka.keel.native.posix.PosixNativeSocket
import io.github.fukusaka.keel.native.posix.PosixNativeSocketOps
import io.github.fukusaka.keel.native.posix.applySocketOptions
import io.github.fukusaka.keel.native.posix.closeFdSafely
import io.github.fukusaka.keel.native.posix.errnoMessage
import io.github.fukusaka.keel.pipeline.PipelinedStreamServer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import platform.linux.EPOLLIN
import platform.linux.EPOLL_CTL_ADD
import platform.linux.epoll_ctl
import platform.linux.epoll_event
import platform.posix.errno
import kotlin.coroutines.CoroutineContext

/**
 * Linux epoll-based [StreamEngine] implementation with multi-threaded EventLoop.
 *
 * Uses a boss/worker EventLoop model (same as NIO and Netty):
 * - **Boss EventLoop**: handles `accept()` readiness on server fds
 * - **Worker EventLoopGroup**: handles `read`/`write`/`flush` on accepted channels
 *
 * New connections are assigned to worker EventLoops in round-robin order.
 * Each worker thread runs its own epoll fd and acts as a
 * [CoroutineDispatcher][kotlinx.coroutines.CoroutineDispatcher], so all
 * I/O + request processing for a channel runs on a single thread without
 * cross-thread dispatch.
 *
 * ```
 * EpollEngine
 *   |
 *   +-- bossLoop (accept EventLoop)
 *   |     |
 *   |     +-- bind() → EpollStreamServer
 *   |           |
 *   |           +-- accept() → assign to workerGroup.next()
 *   |
 *   +-- workerGroup (N worker EventLoops, round-robin)
 *         |
 *         +-- worker[0]: Channel A, D, ...
 *         +-- worker[1]: Channel B, E, ...
 *         +-- worker[N]: ...
 * ```
 *
 * @param config Engine-wide configuration. [IoEngineConfig.threads] controls
 *               the number of worker EventLoop threads. 0 (default) resolves
 *               to `availableProcessors()`.
 * @param nativeSocket POSIX syscall seam. Defaults to [PosixNativeSocket]
 *                     (the production impl that delegates to `keel_*`
 *                     C wrappers). Tests inject a fake implementation to
 *                     drive specific errno branches without real fds.
 * @param nativeSocketOps Cold-path POSIX lifecycle seam (socket / bind /
 *                       listen / setsockopt / getsockname / getpeername /
 *                       getsockopt(SO_ERROR) + composite `bindListener`
 *                       and `bindUnixListener`). Defaults to
 *                       [PosixNativeSocketOps]. Tests inject a fake to
 *                       drive `ConnectResult.Failed` / `SO_ERROR`
 *                       non-zero / address-read branches without a real
 *                       kernel.
 */
@OptIn(ExperimentalForeignApi::class)
class EpollEngine(
    override val config: IoEngineConfig = IoEngineConfig(),
    private val nativeSocket: NativeSocket = PosixNativeSocket,
    nativeSocketOps: NativeSocketOps? = null,
    private val suspendRegisterOverride: EpollSuspendRegister? = null,
) : StreamEngine {

    override val coroutineContext: CoroutineContext = SupervisorJob()

    private val logger = config.loggerFactory.logger("EpollEngine")
    private val nativeSocketOps: NativeSocketOps = nativeSocketOps ?: PosixNativeSocketOps(logger)
    private val bossLoop = EpollEventLoop(config.loggerFactory.logger("EpollEventLoop"))
    private val workerGroup = EpollEventLoopGroup(
        resolveThreads(config),
        config.loggerFactory.logger("EpollEventLoop"),
        config.allocator,
        config.readBufferSize,
    )
    private var closed = false

    init {
        bossLoop.start()
        workerGroup.start()
    }

    /**
     * Binds a suspend-based server on [host]:[port].
     *
     * Creates a server socket, registers it with the boss EventLoop's epoll,
     * and returns an [EpollStreamServer] whose [accept][EpollStreamServer.accept]
     * returns [EpollPipelinedChannel] instances.
     *
     * @throws IllegalStateException if the engine is closed.
     */
    override suspend fun bind(address: SocketAddress, bindConfig: BindConfig): StreamServer = when (address) {
        is InetSocketAddress -> bindInet(address, bindConfig)
        is UnixSocketAddress -> bindUnix(address, bindConfig)
    }

    private suspend fun bindUnix(address: UnixSocketAddress, bindConfig: BindConfig): StreamServer {
        check(!closed) { "Engine is closed" }

        val serverFd = nativeSocketOps.bindUnixListener(address, bindConfig.backlog)

        try {
            memScoped {
                val ev = alloc<epoll_event>()
                ev.events = EPOLLIN.toUInt()
                ev.data.fd = serverFd
                val result = epoll_ctl(bossLoop.epFd, EPOLL_CTL_ADD, serverFd, ev.ptr)
                check(result >= 0) { "epoll_ctl(ADD server) failed: ${errnoMessage(errno)}" }
            }

            logger.debug { "Bound to $address" }
            return EpollStreamServer(serverFd, bossLoop, workerGroup, address, bindConfig, logger, nativeSocket, nativeSocketOps)
        } catch (t: Throwable) {
            closeFdSafely(serverFd, logger, "bindUnix cleanup")
            throw t
        }
    }

    private suspend fun bindInet(address: InetSocketAddress, bindConfig: BindConfig): StreamServer {
        check(!closed) { "Engine is closed" }

        val ip = address.resolveFirst(config.resolver)
        val port = address.port
        val serverFd = nativeSocketOps.bindListener(ip, port, bindConfig.backlog)

        try {
            // Register server fd with the boss EventLoop's epoll so that
            // accept() readiness is notified on the boss thread.
            memScoped {
                val ev = alloc<epoll_event>()
                ev.events = EPOLLIN.toUInt()
                ev.data.fd = serverFd
                val result = epoll_ctl(bossLoop.epFd, EPOLL_CTL_ADD, serverFd, ev.ptr)
                check(result >= 0) { "epoll_ctl(ADD server) failed: ${errnoMessage(errno)}" }
            }

            val localAddr = nativeSocketOps.getLocalAddress(serverFd)
            logger.debug { "Bound to $localAddr" }
            return EpollStreamServer(serverFd, bossLoop, workerGroup, localAddr, bindConfig, logger, nativeSocket, nativeSocketOps)
        } catch (t: Throwable) {
            closeFdSafely(serverFd, logger, "bindInet cleanup")
            throw t
        }
    }

    /**
     * Creates a TCP client connection (non-blocking).
     *
     * The socket is created in non-blocking mode so `connect()` returns
     * immediately with `EINPROGRESS`. The coroutine then suspends on
     * `EPOLLOUT` via the EventLoop until the connection is established
     * (or fails). On loopback, `connect()` may succeed immediately
     * (returns 0) without needing to suspend.
     *
     * After connection, `getsockopt(SO_ERROR)` verifies success.
     * The connected channel is assigned to the next worker EventLoop
     * in round-robin order.
     */
    override suspend fun connect(address: SocketAddress): Channel = connect(address, ConnectConfig.DEFAULT)

    override suspend fun connect(address: SocketAddress, config: ConnectConfig): Channel = when (address) {
        is InetSocketAddress -> connectInet(address, config.socketOptions, config.readBufferSize)
        is UnixSocketAddress -> connectUnix(address, config.socketOptions, config.readBufferSize)
    }

    private suspend fun connectUnix(
        address: UnixSocketAddress,
        socketOptions: SocketOptions,
        readBufferSizeOverride: Int?,
    ): Channel {
        check(!closed) { "Engine is closed" }

        val fd = nativeSocketOps.openUnixClientSocket()
        nativeSocketOps.applySocketOptions(fd, socketOptions)
        val workerLoop = workerGroup.next()

        when (val result = nativeSocketOps.connectUnixNonBlocking(fd, address)) {
            ConnectResult.Connected -> Unit
            ConnectResult.InProgress -> {
                (suspendRegisterOverride ?: workerLoop).awaitWriteReady(fd, logger)
                val error = nativeSocketOps.getSocketError(fd)
                if (error != 0) {
                    closeFdSafely(fd, logger, "connect cleanup")
                    error("connect($address) failed: ${errnoMessage(error)}")
                }
            }
            is ConnectResult.Failed -> {
                closeFdSafely(fd, logger, "connect cleanup")
                error("connect($address) failed: ${errnoMessage(result.errno)}")
            }
        }

        logger.debug { "Connected to $address" }
        val rbs = readBufferSizeOverride ?: workerLoop.readBufferSize
        val transport = EpollIoTransport(fd, workerLoop, nativeSocket, rbs)
        return EpollPipelinedChannel(transport, logger, address, null)
    }

    private suspend fun connectInet(
        address: InetSocketAddress,
        socketOptions: SocketOptions,
        readBufferSizeOverride: Int?,
    ): Channel {
        check(!closed) { "Engine is closed" }
        return address.connectWithFallback(config.resolver) { ip ->
            connectToIp(ip, address.port, socketOptions, readBufferSizeOverride)
        }
    }

    private suspend fun connectToIp(
        ip: IpAddress,
        port: Int,
        socketOptions: SocketOptions,
        readBufferSizeOverride: Int?,
    ): Channel {
        val fd = nativeSocketOps.openClientSocket(ip)
        nativeSocketOps.applySocketOptions(fd, socketOptions)
        val workerLoop = workerGroup.next()

        when (val result = nativeSocketOps.connectNonBlocking(fd, ip, port)) {
            ConnectResult.Connected -> Unit
            ConnectResult.InProgress -> {
                // Connection in progress — suspend until fd is writable.
                (suspendRegisterOverride ?: workerLoop).awaitWriteReady(fd, logger)
                // Verify connection succeeded via SO_ERROR.
                val error = nativeSocketOps.getSocketError(fd)
                if (error != 0) {
                    closeFdSafely(fd, logger, "connect cleanup")
                    error("connect() failed: ${errnoMessage(error)}")
                }
            }
            is ConnectResult.Failed -> {
                closeFdSafely(fd, logger, "connect cleanup")
                error("connect() failed: ${errnoMessage(result.errno)}")
            }
        }

        val remoteAddr = nativeSocketOps.getRemoteAddress(fd)
        val localAddr = nativeSocketOps.getLocalAddress(fd)
        logger.debug { "Connected to $remoteAddr" }
        val rbs = readBufferSizeOverride ?: workerLoop.readBufferSize
        val transport = EpollIoTransport(fd, workerLoop, nativeSocket, rbs)
        return EpollPipelinedChannel(transport, logger, remoteAddr, localAddr)
    }

    /**
     * Binds a pipeline-based server on [host]:[port].
     *
     * Creates a callback-driven server that processes connections entirely
     * through [Pipeline] handlers — no coroutine suspension on the hot path.
     *
     * @param pipelineInitializer Callback to configure the pipeline for each connection.
     * @return A [PipelinedStreamServer] for lifecycle management.
     */
    override fun bindPipeline(
        address: SocketAddress,
        config: BindConfig,
        pipelineInitializer: (io.github.fukusaka.keel.pipeline.PipelinedChannel) -> Unit,
    ): PipelinedStreamServer = when (address) {
        is InetSocketAddress -> bindPipelineInet(address, config, pipelineInitializer)
        is UnixSocketAddress -> bindPipelineUnix(address, config, pipelineInitializer)
    }

    private fun bindPipelineUnix(
        address: UnixSocketAddress,
        config: BindConfig,
        pipelineInitializer: (io.github.fukusaka.keel.pipeline.PipelinedChannel) -> Unit,
    ): PipelinedStreamServer {
        check(!closed) { "Engine is closed" }

        val serverFd = nativeSocketOps.bindUnixListener(address, config.backlog)

        try {
            logger.debug { "Pipeline bound to $address" }
            val serverChannel = EpollPipelinedStreamServer(
                serverFd = serverFd,
                bossLoop = bossLoop,
                workerGroup = workerGroup,
                localAddr = address,
                logger = logger,
                config = config,
                pipelineInitializer = pipelineInitializer,
                nativeSocket = nativeSocket,
                nativeSocketOps = nativeSocketOps,
            )
            serverChannel.start()
            return serverChannel
        } catch (t: Throwable) {
            closeFdSafely(serverFd, logger, "bindPipelineUnix cleanup")
            throw t
        }
    }

    private fun bindPipelineInet(
        address: InetSocketAddress,
        config: BindConfig,
        pipelineInitializer: (io.github.fukusaka.keel.pipeline.PipelinedChannel) -> Unit,
    ): PipelinedStreamServer {
        check(!closed) { "Engine is closed" }

        val ip = address.requireIp()
        val port = address.port
        val serverFd = nativeSocketOps.bindListener(ip, port, config.backlog)

        try {
            val localAddr = nativeSocketOps.getLocalAddress(serverFd)
            logger.debug { "Pipeline bound to $localAddr" }
            val serverChannel = EpollPipelinedStreamServer(
                serverFd = serverFd,
                bossLoop = bossLoop,
                workerGroup = workerGroup,
                localAddr = localAddr,
                logger = logger,
                config = config,
                pipelineInitializer = pipelineInitializer,
                nativeSocket = nativeSocket,
                nativeSocketOps = nativeSocketOps,
            )
            serverChannel.start()
            return serverChannel
        } catch (t: Throwable) {
            closeFdSafely(serverFd, logger, "bindPipelineInet cleanup")
            throw t
        }
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
