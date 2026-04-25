package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.Channel
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.core.StreamServer
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.native.posix.AcceptResult
import io.github.fukusaka.keel.native.posix.NativeSocket
import io.github.fukusaka.keel.native.posix.NativeSocketOps
import io.github.fukusaka.keel.native.posix.PosixNativeSocket
import io.github.fukusaka.keel.native.posix.PosixNativeSocketOps
import io.github.fukusaka.keel.native.posix.applySocketOptions
import io.github.fukusaka.keel.native.posix.closeFdSafely
import io.github.fukusaka.keel.native.posix.errnoMessage
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import kotlinx.cinterop.Arena
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.ptr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.posix.pthread_mutex_destroy
import platform.posix.pthread_mutex_init
import platform.posix.pthread_mutex_lock
import platform.posix.pthread_mutex_t
import platform.posix.pthread_mutex_unlock
import kotlin.concurrent.Volatile
import kotlin.coroutines.resumeWithException

/**
 * epoll-based [StreamServer] implementation for Linux.
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
internal class EpollStreamServer(
    private val serverFd: Int,
    private val bossLoop: EpollEventLoop,
    private val workerGroup: EpollEventLoopGroup,
    override val localAddress: SocketAddress,
    private val bindConfig: BindConfig,
    private val logger: Logger = io.github.fukusaka.keel.logging.NoopLoggerFactory.logger("EpollStreamServer"),
    private val nativeSocket: NativeSocket = PosixNativeSocket,
    private val nativeSocketOps: NativeSocketOps = PosixNativeSocketOps,
) : StreamServer {

    // [_active] flips false on the first close() and is checked atomically
    // with the EventLoop register() call in accept()'s WouldBlock branch
    // (both inside [withLock]). Without that atomicity, an accept could
    // register with [bossLoop] after close() ran [bossLoop.cancelAll],
    // leaving the continuation stranded in the registration chain.
    // @Volatile on [_active] lets [isActive] read without taking the mutex.
    private val arena = Arena()
    private val mutex = arena.alloc<pthread_mutex_t>().apply {
        pthread_mutex_init(ptr, null)
    }

    @Volatile
    private var _active = true

    override val isActive: Boolean get() = _active

    /**
     * Suspends until an incoming connection arrives, then accepts it.
     *
     * Uses POSIX `accept()` in non-blocking mode. If no connection is
     * pending (EAGAIN), registers the server fd with the [EpollEventLoop]
     * and suspends until readiness is reported. The EventLoop maintains
     * a FIFO chain of waiters per `(fd, interest)` key, so multiple
     * coroutines may call [accept] concurrently — each gets its own
     * registration in the chain, epoll's level-triggered fire cascades
     * through them as connections arrive, and POSIX `accept` is itself
     * thread-safe (kernel disperses queued connections among callers).
     */
    override suspend fun accept(): PipelinedChannel {
        check(_active) { "StreamServer is closed" }

        while (true) {
            when (val result = nativeSocket.accept(serverFd)) {
                is AcceptResult.Accepted -> {
                    val clientFd = result.fd
                    nativeSocketOps.setNonBlocking(clientFd)
                    nativeSocketOps.applySocketOptions(clientFd, bindConfig.childSocketOptions)
                    val remoteAddr = nativeSocketOps.getRemoteAddress(clientFd)
                    val localAddr = nativeSocketOps.getLocalAddress(clientFd)
                    val workerLoop = workerGroup.next()
                    val transport = EpollIoTransport(clientFd, workerLoop, workerLoop.allocator, nativeSocket)
                    val channel = EpollPipelinedChannel(
                        transport, logger, remoteAddr, localAddr,
                    )
                    bindConfig.initializeConnection(channel)
                    return channel
                }
                AcceptResult.WouldBlock -> {
                    suspendCancellableCoroutine<Unit> { cont ->
                        // Atomically check _active and register: a concurrent
                        // close() that flipped _active to false would also
                        // have run [bossLoop.cancelAll], so registering after
                        // that point would strand the continuation. Lock
                        // order is StreamServer.mutex (outer) -> EventLoop.
                        // regMutex (inner via register); close() uses the
                        // same order (mutex briefly, then cancelAll
                        // separately) so no deadlock is possible.
                        val reg = withLock {
                            if (!_active) {
                                null
                            } else {
                                bossLoop.register(serverFd, EpollEventLoop.Interest.READ, cont)
                            }
                        }
                        if (reg == null) {
                            cont.resumeWithException(CancellationException("StreamServer closed"))
                            return@suspendCancellableCoroutine
                        }
                        cont.invokeOnCancellation {
                            // Remove only this waiter from the chain;
                            // siblings remain. If close() already ran
                            // cancelAll, this is a no-op (reg already
                            // detached).
                            bossLoop.unregister(reg)
                        }
                    }
                    // Loop back and retry accept.
                }
                is AcceptResult.Failed -> error("accept() failed: ${errnoMessage(result.errno)}")
            }
        }
    }

    /**
     * Closes the server channel and stops accepting connections.
     *
     * Idempotent: subsequent calls are no-ops. Every suspended [accept]
     * coroutine — there may be many, queued in [bossLoop]'s registration
     * chain for this fd — is resumed with [CancellationException] via
     * [EpollEventLoop.cancelAll].
     *
     * **Thread safety**: safe to call from any thread. The [_active] flip
     * is serialised under [mutex]; [bossLoop.cancelAll] takes the
     * EventLoop's own `regMutex` separately (lock order matches accept(),
     * so no deadlock). POSIX `close(fd)` is thread-safe per the POSIX
     * contract.
     */
    override fun close() {
        val shouldClose = withLock {
            if (!_active) return
            _active = false
            true
        }
        if (!shouldClose) return
        bossLoop.cancelAll(serverFd, EpollEventLoop.Interest.READ, CancellationException("StreamServer closed"))
        closeFdSafely(serverFd, logger, "server close")
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
