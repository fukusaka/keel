package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.Channel
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.core.PipelinedServer
import io.github.fukusaka.keel.core.ServerChannel
import io.github.fukusaka.keel.core.StreamEngine
import io.github.fukusaka.keel.logging.debug
import io.github.fukusaka.keel.native.posix.PosixSocketUtils
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kqueue.keel_ev_set
import platform.darwin.EV_ADD
import platform.darwin.EVFILT_READ
import platform.darwin.kevent
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.posix.EINPROGRESS
import platform.posix.close
import platform.posix.errno
import platform.posix.strerror

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
 */
@OptIn(ExperimentalForeignApi::class)
class KqueueEngine(
    override val config: IoEngineConfig = IoEngineConfig(),
) : StreamEngine {

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
    override suspend fun bind(host: String, port: Int, bindConfig: BindConfig): ServerChannel {
        check(!closed) { "Engine is closed" }

        val serverFd = PosixSocketUtils.createServerSocket(host, port, bindConfig.backlog)

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
            check(result >= 0) { "kevent(EV_ADD server) failed: ${strerror(errno)?.toKString()}" }
        }

        val localAddr = PosixSocketUtils.getLocalAddress(serverFd)
        logger.debug { "Bound to ${localAddr.host}:${localAddr.port}" }
        return KqueueServer(serverFd, bossLoop, workerGroup, localAddr, bindConfig, logger)
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
    override suspend fun connect(host: String, port: Int): Channel {
        check(!closed) { "Engine is closed" }

        val fd = PosixSocketUtils.createUnconnectedSocket()
        val (workerLoop, allocator) = workerGroup.next()

        val result = PosixSocketUtils.connectNonBlocking(fd, host, port)
        if (result < 0) {
            val err = errno
            if (err == EINPROGRESS) {
                // Connection in progress — suspend until fd is writable
                suspendCancellableCoroutine<Unit> { cont ->
                    workerLoop.register(fd, KqueueEventLoop.Interest.WRITE, cont)
                    cont.invokeOnCancellation {
                        workerLoop.unregister(fd, KqueueEventLoop.Interest.WRITE)
                        close(fd)
                    }
                }
                // Verify connection succeeded via SO_ERROR
                val error = PosixSocketUtils.getSocketError(fd)
                if (error != 0) {
                    close(fd)
                    error("connect() failed: ${strerror(error)?.toKString()}")
                }
            } else {
                close(fd)
                error("connect() failed: ${strerror(err)?.toKString()}")
            }
        }

        val remoteAddr = PosixSocketUtils.getRemoteAddress(fd)
        val localAddr = PosixSocketUtils.getLocalAddress(fd)
        logger.debug { "Connected to ${remoteAddr.host}:${remoteAddr.port}" }
        val transport = KqueueIoTransport(fd, workerLoop)
        return KqueuePipelinedChannel(fd, transport, workerLoop, allocator, logger, remoteAddr, localAddr)
    }

    /**
     * Binds a pipeline-based server on [host]:[port].
     *
     * Unlike [bind] which returns a suspend-based [ServerChannel], this creates
     * a callback-driven server that processes connections entirely through
     * [ChannelPipeline] handlers — no coroutine suspension on the hot path.
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
        host: String,
        port: Int,
        config: BindConfig,
        pipelineInitializer: (io.github.fukusaka.keel.pipeline.PipelinedChannel) -> Unit,
    ): PipelinedServer {
        check(!closed) { "Engine is closed" }

        val serverFd = PosixSocketUtils.createServerSocket(host, port, config.backlog)

        val localAddr = PosixSocketUtils.getLocalAddress(serverFd)
        logger.debug { "Pipeline bound to ${localAddr.host}:${localAddr.port}" }

        val serverChannel = KqueuePipelinedServerChannel(
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
     * Stops the boss EventLoop and all worker EventLoops, then releases resources.
     *
     * Pending registrations on the boss/worker loops are abandoned (continuations
     * are not resumed). Idempotent — safe to call multiple times.
     */
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
