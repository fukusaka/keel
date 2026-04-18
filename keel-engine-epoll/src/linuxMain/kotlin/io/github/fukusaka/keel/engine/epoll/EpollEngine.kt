package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.Channel
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.core.PipelinedServer
import io.github.fukusaka.keel.core.ServerChannel
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.core.StreamEngine
import io.github.fukusaka.keel.core.UnixSocketAddress
import io.github.fukusaka.keel.core.connectWithFallback
import io.github.fukusaka.keel.core.requireIpLiteral
import io.github.fukusaka.keel.logging.debug
import io.github.fukusaka.keel.native.posix.POSIX_IPV4_RESOLVE_HINTS
import io.github.fukusaka.keel.native.posix.PosixSocketUtils
import io.github.fukusaka.keel.native.posix.errnoMessage
import io.github.fukusaka.keel.native.posix.resolveForPosixSocket
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.CoroutineContext
import platform.linux.EPOLLIN
import platform.linux.EPOLL_CTL_ADD
import platform.linux.epoll_ctl
import platform.linux.epoll_event
import platform.posix.EINPROGRESS
import platform.posix.close
import platform.posix.errno

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
 *   |     +-- bind() → EpollServer
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
 */
@OptIn(ExperimentalForeignApi::class)
class EpollEngine(
    override val config: IoEngineConfig = IoEngineConfig(),
) : StreamEngine {

    override val coroutineContext: CoroutineContext = SupervisorJob()

    private val logger = config.loggerFactory.logger("EpollEngine")
    private val bossLoop = EpollEventLoop(config.loggerFactory.logger("EpollEventLoop"))
    private val workerGroup = EpollEventLoopGroup(resolveThreads(config), config.loggerFactory.logger("EpollEventLoop"), config.allocator)
    private var closed = false

    init {
        bossLoop.start()
        workerGroup.start()
    }

    /**
     * Binds a suspend-based server on [host]:[port].
     *
     * Creates a server socket, registers it with the boss EventLoop's epoll,
     * and returns an [EpollServer] whose [accept][EpollServer.accept]
     * returns [EpollPipelinedChannel] instances.
     *
     * @throws IllegalStateException if the engine is closed.
     */
    override suspend fun bind(address: SocketAddress, bindConfig: BindConfig): ServerChannel = when (address) {
        is InetSocketAddress -> bindInet(address, bindConfig)
        is UnixSocketAddress -> bindUnix(address, bindConfig)
    }

    private suspend fun bindUnix(address: UnixSocketAddress, bindConfig: BindConfig): ServerChannel {
        check(!closed) { "Engine is closed" }

        val serverFd = PosixSocketUtils.createUnixServerSocket(address, bindConfig.backlog)

        memScoped {
            val ev = alloc<epoll_event>()
            ev.events = EPOLLIN.toUInt()
            ev.data.fd = serverFd
            val result = epoll_ctl(bossLoop.epFd, EPOLL_CTL_ADD, serverFd, ev.ptr)
            check(result >= 0) { "epoll_ctl(ADD server) failed: ${errnoMessage(errno)}" }
        }

        logger.debug { "Bound to $address" }
        return EpollServer(serverFd, bossLoop, workerGroup, address, bindConfig, logger)
    }

    private suspend fun bindInet(address: InetSocketAddress, bindConfig: BindConfig): ServerChannel {
        check(!closed) { "Engine is closed" }

        val host = address.resolveForPosixSocket(config.resolver)
        val port = address.port
        val serverFd = PosixSocketUtils.createServerSocket(host, port, bindConfig.backlog)

        // Register server fd with the boss EventLoop's epoll so that
        // accept() readiness is notified on the boss thread.
        memScoped {
            val ev = alloc<epoll_event>()
            ev.events = EPOLLIN.toUInt()
            ev.data.fd = serverFd
            val result = epoll_ctl(bossLoop.epFd, EPOLL_CTL_ADD, serverFd, ev.ptr)
            check(result >= 0) { "epoll_ctl(ADD server) failed: ${errnoMessage(errno)}" }
        }

        val localAddr = PosixSocketUtils.getLocalAddress(serverFd)
        logger.debug { "Bound to $localAddr" }
        return EpollServer(serverFd, bossLoop, workerGroup, localAddr, bindConfig, logger)
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
    override suspend fun connect(address: SocketAddress): Channel = when (address) {
        is InetSocketAddress -> connectInet(address)
        is UnixSocketAddress -> connectUnix(address)
    }

    private suspend fun connectUnix(address: UnixSocketAddress): Channel {
        check(!closed) { "Engine is closed" }

        val fd = PosixSocketUtils.createUnixUnconnectedSocket()
        val (workerLoop, allocator) = workerGroup.next()

        val result = PosixSocketUtils.connectUnixNonBlocking(fd, address)
        if (result < 0) {
            val err = errno
            if (err == EINPROGRESS) {
                suspendCancellableCoroutine<Unit> { cont ->
                    workerLoop.register(fd, EpollEventLoop.Interest.WRITE, cont)
                    cont.invokeOnCancellation {
                        workerLoop.unregister(fd, EpollEventLoop.Interest.WRITE)
                        close(fd)
                    }
                }
                val error = PosixSocketUtils.getSocketError(fd)
                if (error != 0) {
                    close(fd)
                    error("connect($address) failed: ${errnoMessage(error)}")
                }
            } else {
                close(fd)
                error("connect($address) failed: ${errnoMessage(err)}")
            }
        }

        logger.debug { "Connected to $address" }
        val transport = EpollIoTransport(fd, workerLoop, allocator)
        return EpollPipelinedChannel(transport, logger, address, null)
    }

    private suspend fun connectInet(address: InetSocketAddress): Channel {
        check(!closed) { "Engine is closed" }
        return address.connectWithFallback(config.resolver, POSIX_IPV4_RESOLVE_HINTS) { ip ->
            connectToIp(ip.toCanonicalString(), address.port)
        }
    }

    private suspend fun connectToIp(host: String, port: Int): Channel {
        val fd = PosixSocketUtils.createUnconnectedSocket()
        val (workerLoop, allocator) = workerGroup.next()

        val result = PosixSocketUtils.connectNonBlocking(fd, host, port)
        if (result < 0) {
            val err = errno
            if (err == EINPROGRESS) {
                // Connection in progress — suspend until fd is writable
                suspendCancellableCoroutine<Unit> { cont ->
                    workerLoop.register(fd, EpollEventLoop.Interest.WRITE, cont)
                    cont.invokeOnCancellation {
                        workerLoop.unregister(fd, EpollEventLoop.Interest.WRITE)
                        close(fd)
                    }
                }
                // Verify connection succeeded via SO_ERROR
                val error = PosixSocketUtils.getSocketError(fd)
                if (error != 0) {
                    close(fd)
                    error("connect() failed: ${errnoMessage(error)}")
                }
            } else {
                close(fd)
                error("connect() failed: ${errnoMessage(err)}")
            }
        }

        val remoteAddr = PosixSocketUtils.getRemoteAddress(fd)
        val localAddr = PosixSocketUtils.getLocalAddress(fd)
        logger.debug { "Connected to $remoteAddr" }
        val transport = EpollIoTransport(fd, workerLoop, allocator)
        return EpollPipelinedChannel(transport, logger, remoteAddr, localAddr)
    }

    /**
     * Binds a pipeline-based server on [host]:[port].
     *
     * Creates a callback-driven server that processes connections entirely
     * through [Pipeline] handlers — no coroutine suspension on the hot path.
     *
     * @param pipelineInitializer Callback to configure the pipeline for each connection.
     * @return A [PipelinedServer] for lifecycle management.
     */
    override fun bindPipeline(
        address: SocketAddress,
        config: BindConfig,
        pipelineInitializer: (io.github.fukusaka.keel.pipeline.PipelinedChannel) -> Unit,
    ): PipelinedServer = when (address) {
        is InetSocketAddress -> bindPipelineInet(address, config, pipelineInitializer)
        is UnixSocketAddress -> bindPipelineUnix(address, config, pipelineInitializer)
    }

    private fun bindPipelineUnix(
        address: UnixSocketAddress,
        config: BindConfig,
        pipelineInitializer: (io.github.fukusaka.keel.pipeline.PipelinedChannel) -> Unit,
    ): PipelinedServer {
        check(!closed) { "Engine is closed" }

        val serverFd = PosixSocketUtils.createUnixServerSocket(address, config.backlog)

        logger.debug { "Pipeline bound to $address" }

        val serverChannel = EpollPipelinedServerChannel(
            serverFd = serverFd,
            bossLoop = bossLoop,
            workerGroup = workerGroup,
            localAddr = address,
            logger = logger,
            config = config,
            pipelineInitializer = pipelineInitializer,
        )
        serverChannel.start()
        return serverChannel
    }

    private fun bindPipelineInet(
        address: InetSocketAddress,
        config: BindConfig,
        pipelineInitializer: (io.github.fukusaka.keel.pipeline.PipelinedChannel) -> Unit,
    ): PipelinedServer {
        check(!closed) { "Engine is closed" }

        val host = address.requireIpLiteral()
        val port = address.port
        val serverFd = PosixSocketUtils.createServerSocket(host, port, config.backlog)

        val localAddr = PosixSocketUtils.getLocalAddress(serverFd)
        logger.debug { "Pipeline bound to $localAddr" }

        val serverChannel = EpollPipelinedServerChannel(
            serverFd = serverFd,
            bossLoop = bossLoop,
            workerGroup = workerGroup,
            localAddr = localAddr,
            logger = logger,
            config = config,
            pipelineInitializer = pipelineInitializer,
        )
        serverChannel.start()
        return serverChannel
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
