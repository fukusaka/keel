package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.core.Channel
import io.github.fukusaka.keel.core.IoEngine
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.core.ServerChannel
import io.github.fukusaka.keel.logging.debug
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
) : IoEngine {

    private val logger = config.loggerFactory.logger("IoUringEngine")
    private val bossLoop = IoUringEventLoop(config.loggerFactory.logger("IoUringEventLoop"))
    private val workerGroup = IoUringEventLoopGroup(
        size = resolveThreads(config),
        logger = config.loggerFactory.logger("IoUringEventLoop"),
        allocator = config.allocator,
    )
    private var closed = false

    init {
        bossLoop.start()
        workerGroup.start()
    }

    override suspend fun bind(host: String, port: Int): ServerChannel {
        check(!closed) { "Engine is closed" }

        val serverFd = SocketUtils.createServerSocket(host, port)
        val localAddr = SocketUtils.getLocalAddress(serverFd)
        logger.debug { "Bound to ${localAddr.host}:${localAddr.port}" }
        return IoUringServerChannel(serverFd, bossLoop, workerGroup, localAddr, writeModeSelector)
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
        logger.debug { "Connected to ${remoteAddr.host}:${remoteAddr.port}" }
        return IoUringChannel(fd, workerLoop, allocator, bufferRing, remoteAddr, localAddr, writeModeSelector)
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
