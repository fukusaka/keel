package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.Channel
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.core.IpAddress
import io.github.fukusaka.keel.core.PipelinedServer
import io.github.fukusaka.keel.core.ServerChannel
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.core.StreamEngine
import io.github.fukusaka.keel.core.UnixSocketAddress
import io.github.fukusaka.keel.core.connectWithFallback
import io.github.fukusaka.keel.core.requireFilesystemOnly
import io.github.fukusaka.keel.core.requireIp
import io.github.fukusaka.keel.core.resolveFirst
import io.github.fukusaka.keel.logging.debug
import io.github.fukusaka.keel.native.posix.ConnectResult
import io.github.fukusaka.keel.native.posix.NativeSocket
import io.github.fukusaka.keel.native.posix.PosixNativeSocket
import io.github.fukusaka.keel.native.posix.PosixSocketOps
import io.github.fukusaka.keel.native.posix.PosixSocketUtils
import io.github.fukusaka.keel.native.posix.closeFdSafely
import io.github.fukusaka.keel.native.posix.errnoMessage
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.CoroutineContext
import kqueue.keel_ev_set
import platform.darwin.EV_ADD
import platform.darwin.EVFILT_READ
import platform.darwin.kevent
import platform.posix.errno

/**
 * macOS kqueue-based [StreamEngine] implementation with multi-threaded EventLoop.
 *
 * Uses a boss/worker EventLoop model (same as NIO and Netty):
 * - **Boss EventLoop**: handles `accept()` readiness on server fds
 * - **Worker EventLoopGroup**: handles `read`/`write`/`flush` on accepted channels
 *
 * New connections are assigned to worker EventLoops in round-robin order.
 * Each worker thread runs its own kqueue fd and acts as a
 * [CoroutineDispatcher][kotlinx.coroutines.CoroutineDispatcher], so all
 * I/O + request processing for a channel runs on a single thread without
 * cross-thread dispatch.
 *
 * ```
 * KqueueEngine
 *   |
 *   +-- bossLoop (accept EventLoop)
 *   |     |
 *   |     +-- bind() → KqueueServer
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
 * @param posixSocketOps Cold-path POSIX lifecycle seam (socket / bind /
 *                       listen / setsockopt / getsockname / getpeername /
 *                       getsockopt(SO_ERROR) + composite `acceptClient`
 *                       and `create*ServerSocket`). Defaults to
 *                       [PosixSocketUtils]. Tests inject a fake to
 *                       drive `ConnectResult.Failed` / `SO_ERROR`
 *                       non-zero / address-read branches without a real
 *                       kernel.
 */
@OptIn(ExperimentalForeignApi::class)
class KqueueEngine(
    override val config: IoEngineConfig = IoEngineConfig(),
    private val nativeSocket: NativeSocket = PosixNativeSocket,
    private val posixSocketOps: PosixSocketOps = PosixSocketUtils,
) : StreamEngine {

    override val coroutineContext: CoroutineContext = SupervisorJob()

    private val logger = config.loggerFactory.logger("KqueueEngine")
    private val bossLoop = KqueueEventLoop(config.loggerFactory.logger("KqueueEventLoop"))
    private val workerGroup = KqueueEventLoopGroup(resolveThreads(config), config.loggerFactory.logger("KqueueEventLoop"), config.allocator)
    private var closed = false

    init {
        bossLoop.start()
        workerGroup.start()
    }

    /**
     * Binds a TCP server on [host]:[port] and returns a [ServerChannel].
     *
     * Creates a server socket, registers it with the boss EventLoop's kqueue,
     * and returns a [KqueueServer] whose [accept][ServerChannel.accept]
     * distributes connections to worker EventLoops in round-robin.
     *
     * @throws IllegalStateException if the engine is already closed.
     */
    override suspend fun bind(address: SocketAddress, bindConfig: BindConfig): ServerChannel = when (address) {
        is InetSocketAddress -> bindInet(address, bindConfig)
        is UnixSocketAddress -> bindUnix(address, bindConfig)
    }

    private suspend fun bindUnix(address: UnixSocketAddress, bindConfig: BindConfig): ServerChannel {
        check(!closed) { "Engine is closed" }
        address.requireFilesystemOnly("KqueueEngine does not support abstract-namespace Unix sockets (macOS kernel has no abstract namespace)")

        val serverFd = posixSocketOps.createUnixServerSocket(address, bindConfig.backlog, logger)

        try {
            memScoped {
                val kev = alloc<kevent>()
                keel_ev_set(
                    kev.ptr,
                    serverFd.convert(),
                    EVFILT_READ.convert(),
                    EV_ADD.convert(),
                    0u,
                    0,
                    null,
                )
                val result = kevent(bossLoop.kqFd, kev.ptr, 1, null, 0, null)
                check(result >= 0) { "kevent(EV_ADD server) failed: ${errnoMessage(errno)}" }
            }

            logger.debug { "Bound to $address" }
            return KqueueServer(serverFd, bossLoop, workerGroup, address, bindConfig, logger, nativeSocket, posixSocketOps)
        } catch (t: Throwable) {
            closeFdSafely(serverFd, logger, "bindUnix cleanup")
            throw t
        }
    }

    private suspend fun bindInet(address: InetSocketAddress, bindConfig: BindConfig): ServerChannel {
        check(!closed) { "Engine is closed" }

        val ip = address.resolveFirst(config.resolver)
        val port = address.port
        val serverFd = posixSocketOps.createServerSocket(ip, port, bindConfig.backlog, logger)

        try {
            // Register server fd with the boss EventLoop's kqueue so that
            // accept() readiness is notified on the boss thread.
            memScoped {
                val kev = alloc<kevent>()
                keel_ev_set(
                    kev.ptr,
                    serverFd.convert(),
                    EVFILT_READ.convert(),
                    EV_ADD.convert(),
                    0u,
                    0,
                    null,
                )
                val result = kevent(bossLoop.kqFd, kev.ptr, 1, null, 0, null)
                check(result >= 0) { "kevent(EV_ADD server) failed: ${errnoMessage(errno)}" }
            }

            val localAddr = posixSocketOps.getLocalAddress(serverFd)
            logger.debug { "Bound to $localAddr" }
            return KqueueServer(serverFd, bossLoop, workerGroup, localAddr, bindConfig, logger, nativeSocket, posixSocketOps)
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
     * `EVFILT_WRITE` via the EventLoop until the connection is established
     * (or fails). On loopback, `connect()` may succeed immediately
     * (returns 0) without needing to suspend.
     *
     * After connection, `getsockopt(SO_ERROR)` verifies success.
     * The connected channel is assigned to the next worker EventLoop
     * in round-robin order.
     *
     * @throws IllegalStateException if the engine is already closed.
     * @throws IllegalStateException if connect fails (SO_ERROR non-zero).
     */
    override suspend fun connect(address: SocketAddress): Channel = when (address) {
        is InetSocketAddress -> connectInet(address)
        is UnixSocketAddress -> connectUnix(address)
    }

    private suspend fun connectUnix(address: UnixSocketAddress): Channel {
        check(!closed) { "Engine is closed" }
        address.requireFilesystemOnly("KqueueEngine does not support abstract-namespace Unix sockets (macOS kernel has no abstract namespace)")

        val fd = posixSocketOps.createUnixUnconnectedSocket()
        val (workerLoop, allocator) = workerGroup.next()

        when (val result = posixSocketOps.connectUnixNonBlocking(fd, address)) {
            ConnectResult.Connected -> Unit
            ConnectResult.InProgress -> {
                suspendCancellableCoroutine<Unit> { cont ->
                    workerLoop.register(fd, KqueueEventLoop.Interest.WRITE, cont)
                    cont.invokeOnCancellation {
                        workerLoop.unregister(fd, KqueueEventLoop.Interest.WRITE)
                        closeFdSafely(fd, logger, "connect cancellation")
                    }
                }
                val error = posixSocketOps.getSocketError(fd)
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
        val transport = KqueueIoTransport(fd, workerLoop, allocator, nativeSocket)
        return KqueuePipelinedChannel(transport, logger, address, null)
    }

    private suspend fun connectInet(address: InetSocketAddress): Channel {
        check(!closed) { "Engine is closed" }
        return address.connectWithFallback(config.resolver) { ip ->
            connectToIp(ip, address.port)
        }
    }

    private suspend fun connectToIp(ip: IpAddress, port: Int): Channel {
        val fd = posixSocketOps.createUnconnectedSocket(ip)
        val (workerLoop, allocator) = workerGroup.next()

        when (val result = posixSocketOps.connectNonBlocking(fd, ip, port)) {
            ConnectResult.Connected -> Unit
            ConnectResult.InProgress -> {
                // Connection in progress — suspend until fd is writable
                suspendCancellableCoroutine<Unit> { cont ->
                    workerLoop.register(fd, KqueueEventLoop.Interest.WRITE, cont)
                    cont.invokeOnCancellation {
                        workerLoop.unregister(fd, KqueueEventLoop.Interest.WRITE)
                        closeFdSafely(fd, logger, "connect cancellation")
                    }
                }
                // Verify connection succeeded via SO_ERROR
                val error = posixSocketOps.getSocketError(fd)
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

        val remoteAddr = posixSocketOps.getRemoteAddress(fd)
        val localAddr = posixSocketOps.getLocalAddress(fd)
        logger.debug { "Connected to $remoteAddr" }
        val transport = KqueueIoTransport(fd, workerLoop, allocator, nativeSocket)
        return KqueuePipelinedChannel(transport, logger, remoteAddr, localAddr)
    }

    /**
     * Binds a pipeline-based server on [host]:[port].
     *
     * Unlike [bind] which returns a suspend-based [ServerChannel], this creates
     * a callback-driven server that processes connections entirely through
     * [Pipeline] handlers — no coroutine suspension on the hot path.
     *
     * The boss EventLoop accepts connections and distributes them to worker
     * EventLoops in round-robin order. Each worker creates a
     * [KqueuePipelinedChannel] and arms read callbacks.
     *
     * @param host Bind address (e.g. "0.0.0.0").
     * @param port Bind port.
     * @param pipelineInitializer Callback to configure the pipeline for each
     *        accepted connection (add handlers via addLast).
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
        address.requireFilesystemOnly("KqueueEngine does not support abstract-namespace Unix sockets (macOS kernel has no abstract namespace)")

        val serverFd = posixSocketOps.createUnixServerSocket(address, config.backlog, logger)

        try {
            logger.debug { "Pipeline bound to $address" }
            val serverChannel = KqueuePipelinedServerChannel(
                serverFd = serverFd,
                bossLoop = bossLoop,
                workerGroup = workerGroup,
                localAddr = address,
                logger = logger,
                config = config,
                pipelineInitializer = pipelineInitializer,
                nativeSocket = nativeSocket,
                posixSocketOps = posixSocketOps,
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
    ): PipelinedServer {
        check(!closed) { "Engine is closed" }

        val ip = address.requireIp()
        val port = address.port
        val serverFd = posixSocketOps.createServerSocket(ip, port, config.backlog, logger)

        try {
            val localAddr = posixSocketOps.getLocalAddress(serverFd)
            logger.debug { "Pipeline bound to $localAddr" }
            val serverChannel = KqueuePipelinedServerChannel(
                serverFd = serverFd,
                bossLoop = bossLoop,
                workerGroup = workerGroup,
                localAddr = localAddr,
                logger = logger,
                config = config,
                pipelineInitializer = pipelineInitializer,
                nativeSocket = nativeSocket,
                posixSocketOps = posixSocketOps,
            )
            serverChannel.start()
            return serverChannel
        } catch (t: Throwable) {
            closeFdSafely(serverFd, logger, "bindPipelineInet cleanup")
            throw t
        }
    }

    /**
     * Stops the boss EventLoop and all worker EventLoops, then releases resources.
     *
     * Pending registrations on the boss/worker loops are abandoned (continuations
     * are not resumed). Idempotent — safe to call multiple times.
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
