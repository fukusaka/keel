package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.Channel
import io.github.fukusaka.keel.core.ServerChannel
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.native.posix.PosixSocketUtils
import kotlinx.cinterop.Arena
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.ptr
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.posix.EAGAIN
import platform.posix.accept
import platform.posix.close
import platform.posix.errno
import platform.posix.pthread_mutex_destroy
import platform.posix.pthread_mutex_init
import platform.posix.pthread_mutex_lock
import platform.posix.pthread_mutex_t
import platform.posix.pthread_mutex_unlock
import kotlin.concurrent.Volatile
import kotlin.coroutines.resumeWithException

/**
 * epoll-based [ServerChannel] implementation for Linux.
 *
 * Listens on [serverFd] and uses the boss [EpollEventLoop] to wait for
 * incoming connections. Accepted channels are assigned to worker EventLoops
 * from [workerGroup] in round-robin order.
 *
 * ```
 * accept() flow:
 *   bossLoop: epoll_wait() fires EPOLLIN on serverFd → resume
 *   POSIX accept(serverFd) → clientFd
 *   workerGroup.next() → assign worker EventLoop
 *   → EpollPipelinedChannel(clientFd, transport, workerLoop, allocator)
 * ```
 *
 * @param serverFd    The listening server socket fd (non-blocking).
 * @param bossLoop    The boss [EpollEventLoop] for accept readiness notification.
 * @param workerGroup Worker EventLoopGroup for accepted channels.
 * @param localAddress Bind address of this server channel.
 */
@OptIn(ExperimentalForeignApi::class)
internal class EpollServer(
    private val serverFd: Int,
    private val bossLoop: EpollEventLoop,
    private val workerGroup: EpollEventLoopGroup,
    override val localAddress: SocketAddress,
    private val bindConfig: BindConfig,
    private val logger: Logger = io.github.fukusaka.keel.logging.NoopLoggerFactory.logger("EpollServer"),
) : ServerChannel {

    // State transitions may be observed from the boss EventLoop thread
    // (epoll readiness callback) and from external dispatcher threads
    // (close() callers). Access is serialised under [mutex].
    // @Volatile on _active lets isActive read without taking the mutex.
    private val arena = Arena()
    private val mutex = arena.alloc<pthread_mutex_t>().apply {
        pthread_mutex_init(ptr, null)
    }

    @Volatile
    private var _active = true
    private var pendingAcceptCont: CancellableContinuation<Unit>? = null

    override val isActive: Boolean get() = _active

    /**
     * Suspends until an incoming connection arrives, then accepts it.
     *
     * Uses POSIX `accept()` in non-blocking mode. If no connection is
     * pending (EAGAIN), registers the server fd with the [EpollEventLoop]
     * and suspends until readiness is reported.
     */
    override suspend fun accept(): PipelinedChannel {
        check(_active) { "ServerChannel is closed" }

        while (true) {
            val clientFd = accept(serverFd, null, null)
            if (clientFd >= 0) {
                PosixSocketUtils.setNonBlocking(clientFd)
                val remoteAddr = PosixSocketUtils.getRemoteAddress(clientFd)
                val localAddr = PosixSocketUtils.getLocalAddress(clientFd)
                val (workerLoop, allocator) = workerGroup.next()
                val transport = EpollIoTransport(clientFd, workerLoop, allocator)
                val channel = EpollPipelinedChannel(
                    transport, logger, remoteAddr, localAddr,
                )
                bindConfig.initializeConnection(channel)
                return channel
            }

            val err = errno
            if (err == EAGAIN) {
                // Suspend until boss EventLoop reports serverFd is readable
                suspendCancellableCoroutine<Unit> { cont ->
                    val closedAlready = withLock {
                        if (!_active) {
                            true
                        } else {
                            pendingAcceptCont = cont
                            false
                        }
                    }
                    if (closedAlready) {
                        cont.resumeWithException(CancellationException("ServerChannel closed"))
                        return@suspendCancellableCoroutine
                    }
                    bossLoop.register(serverFd, EpollEventLoop.Interest.READ, cont)
                    cont.invokeOnCancellation {
                        withLock {
                            if (pendingAcceptCont === cont) pendingAcceptCont = null
                        }
                        bossLoop.unregister(serverFd, EpollEventLoop.Interest.READ)
                    }
                }
                withLock { pendingAcceptCont = null }
                continue
            }
            error("accept() failed: errno=$err")
        }
    }

    /**
     * Closes the server channel and stops accepting connections.
     *
     * Idempotent: subsequent calls are no-ops. If an [accept] coroutine
     * is suspended, it is cancelled with [CancellationException].
     *
     * **Thread safety**: safe to call from any thread. [_active] and
     * [pendingAcceptCont] transitions are serialised under [mutex];
     * POSIX `close(fd)` is thread-safe per the POSIX contract.
     */
    override fun close() {
        val cont = withLock {
            if (!_active) return
            _active = false
            val c = pendingAcceptCont
            pendingAcceptCont = null
            c
        }
        cont?.resumeWithException(CancellationException("ServerChannel closed"))
        close(serverFd)
        pthread_mutex_destroy(mutex.ptr)
        arena.clear()
    }

    private inline fun <T> withLock(block: () -> T): T {
        pthread_mutex_lock(mutex.ptr)
        try {
            return block()
        } finally {
            pthread_mutex_unlock(mutex.ptr)
        }
    }
}
