package io.github.fukusaka.keel.native.posix

import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.Server
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import kotlinx.cinterop.Arena
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.ptr
import kotlinx.coroutines.CancellableContinuation
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
 * Common skeleton for POSIX coroutine-based server channels (epoll / kqueue).
 *
 * Concrete subclasses provide only the engine-specific boss-loop
 * registration ([armReadReadiness] / [unregisterReadReadiness]) and the
 * channel factory ([buildChannel]); everything else — the edge-triggered
 * accept loop, the `setNonBlocking` + `applySocketOptions` + address
 * lookup chain, `pendingAcceptCont` close-race protection, and
 * `pthread_mutex` teardown — lives here.
 *
 * ## Why this class exists
 *
 * `EpollServer` and `KqueueServer` had near-identical implementations
 * (only the `EpollEventLoop.Interest` / `KqueueEventLoop.Interest`
 * constants and the `Transport` / `Channel` types differed). With the
 * [NativeSocket] + [NativeSocketOps] seams in place, all the accept-
 * path syscalls route through interfaces, leaving the engine-specific
 * surface narrow enough to abstract without hurting the hot path.
 *
 * ## Hot path
 *
 * `accept()` is not a hot path — once per connection, not once per
 * packet. The single virtual dispatch per accepted connection
 * (`buildChannel` / `armReadReadiness`) is negligible next to the
 * syscalls it guards. io_uring is intentionally NOT a subclass: its
 * multishot accept uses a completion-queue model that does not fit
 * this edge-triggered skeleton.
 *
 * ## Close-race contract
 *
 * [accept] and [close] may be invoked from different threads. The
 * mutex serialises `_active` ↔ `pendingAcceptCont` transitions so a
 * `close()` arriving while a coroutine is suspended deterministically
 * cancels the continuation with [CancellationException]. See
 * [Thread safety] in [close].
 */
@OptIn(ExperimentalForeignApi::class)
public abstract class AbstractPosixServer(
    protected val serverFd: Int,
    final override val localAddress: SocketAddress,
    protected val bindConfig: BindConfig,
    protected val logger: Logger,
    protected val nativeSocket: NativeSocket = PosixNativeSocket,
    protected val nativeSocketOps: NativeSocketOps = PosixNativeSocketOps,
) : Server {

    private val arena = Arena()
    private val mutex = arena.alloc<pthread_mutex_t>().apply {
        pthread_mutex_init(ptr, null)
    }

    @Volatile
    private var _active = true
    private var pendingAcceptCont: CancellableContinuation<Unit>? = null

    final override val isActive: Boolean get() = _active

    /**
     * Standard edge-triggered accept loop with [NativeSocket] seam.
     *
     * Not overridable — engine specifics enter through [buildChannel]
     * and [armReadReadiness] / [unregisterReadReadiness].
     */
    final override suspend fun accept(): PipelinedChannel {
        check(_active) { "ServerChannel is closed" }

        while (true) {
            when (val result = nativeSocket.accept(serverFd)) {
                is AcceptResult.Accepted -> {
                    val clientFd = result.fd
                    nativeSocketOps.setNonBlocking(clientFd)
                    nativeSocketOps.applySocketOptions(clientFd, bindConfig.childSocketOptions)
                    val remoteAddr = nativeSocketOps.getRemoteAddress(clientFd)
                    val localAddr = nativeSocketOps.getLocalAddress(clientFd)
                    val channel = buildChannel(clientFd, remoteAddr, localAddr)
                    bindConfig.initializeConnection(channel)
                    return channel
                }
                AcceptResult.WouldBlock -> suspendUntilAcceptable()
                is AcceptResult.Failed -> error("accept() failed: ${errnoMessage(result.errno)}")
            }
        }
    }

    /**
     * Suspends the calling coroutine until the boss event loop reports
     * [serverFd] as readable. Handles the close-race guard, continuation
     * storage for [close]-driven cancellation, and the `invokeOnCancellation`
     * cleanup. Delegates only the engine-specific registration calls to
     * [armReadReadiness] / [unregisterReadReadiness].
     */
    private suspend fun suspendUntilAcceptable() {
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
            armReadReadiness(cont)
            cont.invokeOnCancellation {
                withLock {
                    if (pendingAcceptCont === cont) pendingAcceptCont = null
                }
                unregisterReadReadiness()
            }
        }
        withLock { pendingAcceptCont = null }
    }

    /**
     * Builds the engine-specific [PipelinedChannel] for an accepted
     * connection. Implementations are expected to:
     *
     * 1. Select a worker event loop (round-robin).
     * 2. Construct the engine's `IoTransport(clientFd, workerLoop, ...)`.
     * 3. Wrap in the engine's `PipelinedChannel(transport, logger, remote, local)`.
     *
     * The base class handles `setNonBlocking` / `applySocketOptions` /
     * address lookups before this is called, and
     * `bindConfig.initializeConnection(channel)` after.
     */
    protected abstract fun buildChannel(
        clientFd: Int,
        remoteAddr: SocketAddress,
        localAddr: SocketAddress,
    ): PipelinedChannel

    /**
     * Registers [serverFd]'s READ interest with the boss event loop and
     * hands it [cont] to resume when the fd becomes readable. Invoked
     * from the suspend body (base class has already committed the
     * continuation to [pendingAcceptCont] under the state mutex).
     */
    protected abstract fun armReadReadiness(cont: CancellableContinuation<Unit>)

    /**
     * Removes [serverFd]'s READ interest from the boss event loop on
     * coroutine cancellation. Must be idempotent — the base calls it
     * from `invokeOnCancellation` which may fire after the loop already
     * removed the registration.
     */
    protected abstract fun unregisterReadReadiness()

    /**
     * Closes the server channel and stops accepting connections.
     *
     * Idempotent: subsequent calls are no-ops. If an [accept] coroutine
     * is suspended, it is cancelled with [CancellationException].
     *
     * **Thread safety**: safe to call from any thread. `_active` /
     * [pendingAcceptCont] transitions are serialised under the mutex;
     * POSIX `close(fd)` is thread-safe per the POSIX contract, and
     * [CancellableContinuation.resumeWithException] is thread-safe by
     * kotlinx.coroutines contract.
     */
    final override fun close() {
        val cont = withLock {
            if (!_active) return
            _active = false
            val c = pendingAcceptCont
            pendingAcceptCont = null
            c
        }
        cont?.resumeWithException(CancellationException("ServerChannel closed"))
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
