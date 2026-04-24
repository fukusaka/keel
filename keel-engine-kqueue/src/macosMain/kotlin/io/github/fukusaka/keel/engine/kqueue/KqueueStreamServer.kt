package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.Channel
import io.github.fukusaka.keel.core.StreamServer
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.native.posix.AcceptResult
import io.github.fukusaka.keel.native.posix.NativeSocket
import io.github.fukusaka.keel.native.posix.PosixNativeSocket
import io.github.fukusaka.keel.native.posix.NativeSocketOps
import io.github.fukusaka.keel.native.posix.PosixNativeSocketOps
import io.github.fukusaka.keel.native.posix.applySocketOptions
import io.github.fukusaka.keel.native.posix.closeFdSafely
import io.github.fukusaka.keel.native.posix.errnoMessage
import kotlinx.cinterop.Arena
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.ptr
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.concurrent.Volatile
import kotlin.coroutines.resumeWithException
import platform.posix.pthread_mutex_destroy
import platform.posix.pthread_mutex_init
import platform.posix.pthread_mutex_lock
import platform.posix.pthread_mutex_t
import platform.posix.pthread_mutex_unlock

/**
 * kqueue-based [StreamServer] implementation for macOS.
 *
 * Listens on [serverFd] and uses the boss [KqueueEventLoop] to wait for
 * incoming connections. Accepted channels are assigned to worker EventLoops
 * from [workerGroup] in round-robin order.
 *
 * ```
 * accept() flow:
 *   bossLoop: kevent() fires EVFILT_READ on serverFd → resume
 *   POSIX accept(serverFd) → clientFd
 *   workerGroup.next() → assign worker EventLoop
 *   → KqueuePipelinedChannel(clientFd, transport, workerLoop, allocator)
 * ```
 *
 * @param serverFd    The listening server socket fd (non-blocking).
 * @param bossLoop    The boss [KqueueEventLoop] for accept readiness notification.
 * @param workerGroup Worker EventLoopGroup for accepted channels (provides per-EventLoop allocator).
 * @param localAddress Bind address of this server channel.
 */
@OptIn(ExperimentalForeignApi::class)
internal class KqueueStreamServer(
    private val serverFd: Int,
    private val bossLoop: KqueueEventLoop,
    private val workerGroup: KqueueEventLoopGroup,
    override val localAddress: SocketAddress,
    private val bindConfig: BindConfig,
    private val logger: io.github.fukusaka.keel.logging.Logger = io.github.fukusaka.keel.logging.NoopLoggerFactory.logger("KqueueStreamServer"),
    private val nativeSocket: NativeSocket = PosixNativeSocket,
    private val nativeSocketOps: NativeSocketOps = PosixNativeSocketOps,
) : StreamServer {

    // State transitions may be observed from the boss EventLoop thread
    // (accept readiness callback) and from external dispatcher threads
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
     * pending (EAGAIN), registers the server fd with the [KqueueEventLoop]
     * and suspends until readiness is reported.
     *
     * The accepted connection is assigned to the next worker EventLoop
     * in round-robin order and returned as a [KqueuePipelinedChannel]
     * supporting both Pipeline mode and Coroutine mode.
     *
     * @throws IllegalStateException if the server channel is already closed.
     * @throws IllegalStateException if `accept()` fails with a non-EAGAIN error.
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
                    val (workerLoop, allocator) = workerGroup.next()
                    val transport = KqueueIoTransport(clientFd, workerLoop, allocator, nativeSocket)
                    val channel = KqueuePipelinedChannel(
                        transport, logger, remoteAddr, localAddr,
                    )
                    bindConfig.initializeConnection(channel)
                    return channel
                }
                AcceptResult.WouldBlock -> {
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
                            cont.resumeWithException(CancellationException("StreamServer closed"))
                            return@suspendCancellableCoroutine
                        }
                        bossLoop.register(serverFd, KqueueEventLoop.Interest.READ, cont)
                        cont.invokeOnCancellation {
                            withLock {
                                if (pendingAcceptCont === cont) pendingAcceptCont = null
                            }
                            bossLoop.unregister(serverFd, KqueueEventLoop.Interest.READ)
                        }
                    }
                    withLock { pendingAcceptCont = null }
                    // Loop back and retry accept.
                }
                is AcceptResult.Failed -> error("accept() failed: ${errnoMessage(result.errno)}")
            }
        }
    }

    /**
     * Stops accepting and closes the server socket.
     *
     * Idempotent: subsequent calls are no-ops. If an [accept] coroutine
     * is suspended, it is cancelled with [CancellationException].
     *
     * **Thread safety**: safe to call from any thread. [_active] and
     * [pendingAcceptCont] transitions are serialised under [mutex];
     * POSIX `close(fd)` is thread-safe per the POSIX contract, and
     * [CancellableContinuation.resumeWithException] is thread-safe by
     * kotlinx.coroutines contract.
     */
    override fun close() {
        val cont = withLock {
            if (!_active) return
            _active = false
            val c = pendingAcceptCont
            pendingAcceptCont = null
            c
        }
        cont?.resumeWithException(CancellationException("StreamServer closed"))
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
