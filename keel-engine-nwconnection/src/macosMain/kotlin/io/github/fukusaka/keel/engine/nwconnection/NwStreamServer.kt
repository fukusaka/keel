package io.github.fukusaka.keel.engine.nwconnection

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.Channel
import io.github.fukusaka.keel.core.IdleReadPolicy
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.core.StreamServer
import io.github.fukusaka.keel.logging.LoggerFactory
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import kotlinx.cinterop.Arena
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.ptr
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import nwconnection.keel_nw_start_conn_async
import platform.Network.nw_connection_copy_endpoint
import platform.Network.nw_connection_t
import platform.Network.nw_endpoint_get_hostname
import platform.Network.nw_endpoint_get_port
import platform.Network.nw_listener_cancel
import platform.Network.nw_listener_t
import platform.darwin.dispatch_queue_create
import platform.posix.pthread_mutex_destroy
import platform.posix.pthread_mutex_init
import platform.posix.pthread_mutex_lock
import platform.posix.pthread_mutex_t
import platform.posix.pthread_mutex_unlock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * NWConnection-based [StreamServer] implementation for macOS.
 *
 * Wraps an [nw_listener_t] and accepts incoming connections via
 * [suspendCancellableCoroutine]. The listener's new-connection handler
 * (set by [NwEngine.bind]) calls [onNewConnection] for each accepted
 * connection, which either resumes a waiting [accept] coroutine or
 * buffers the connection for the next [accept] call.
 *
 * **Thread safety**: [onNewConnection] is called from the listener's
 * dispatch queue while [accept] runs on a coroutine thread. All access
 * to [pendingConnections] and [pendingAcceptConts] is protected by
 * [mutex] (pthread_mutex_t). Kotlin/Native lacks JVM's `synchronized`;
 * coroutine `Mutex` cannot be used from the dispatch callback.
 *
 * ```
 * accept() flow:
 *   suspendCancellableCoroutine
 *     --> listener callback: onNewConnection(conn) --> resume
 *   keel_nw_start_conn_async(conn, queue, callback, ctx)
 *     --> suspendCancellableCoroutine until ready
 *   --> NwPipelinedChannel(transport, logger, remoteAddr, localAddr)
 * ```
 *
 * @param listener    The NWListener handle.
 * @param localAddress Bind address of this server channel.
 * @param allocator   Passed to accepted [NwPipelinedChannel]s.
 */
@OptIn(ExperimentalForeignApi::class)
internal class NwStreamServer(
    private val listener: nw_listener_t,
    localAddress: SocketAddress,
    private val allocator: BufferAllocator,
    private val bindConfig: BindConfig,
    private val loggerFactory: LoggerFactory,
    private val idleReadPolicy: IdleReadPolicy,
) : StreamServer {

    private val arena = Arena()
    private val mutex = arena.alloc<pthread_mutex_t>().apply {
        pthread_mutex_init(ptr, null)
    }
    private val pendingConnections = ArrayDeque<nw_connection_t>()

    // FIFO queue of suspended accept() callers. The previous single-slot
    // design (`pendingAcceptCont: CancellableContinuation<...>?`)
    // silently overwrote earlier waiters when two `accept()` calls both
    // passed the empty-`pendingConnections` check inside [withLock] and
    // both assigned the slot — the lost continuation never resumed and
    // the corresponding `accept()` hung forever. Counterpart of the
    // POSIX engines' chain (PR #367), the io-uring queue (PR #368), the
    // Netty queue (PR #369), and the Node.js queue (PR #370).
    // Identity-based `ArrayDeque.remove(cont)` works because
    // `CancellableContinuation` inherits `Object.equals`
    // (reference identity).
    private val pendingAcceptConts = ArrayDeque<CancellableContinuation<nw_connection_t>>()
    private var _active = true
    private var _localAddress: SocketAddress = localAddress

    override val localAddress: SocketAddress get() = _localAddress
    override val isActive: Boolean get() = _active

    /**
     * Updates the local address after the listener's assigned port is known.
     * Called by [NwEngine.bind] after the listener reaches the ready state.
     */
    internal fun updateLocalAddress(addr: SocketAddress) {
        _localAddress = addr
    }

    /**
     * Called by [NwEngine.bind]'s new-connection handler when an incoming
     * connection arrives. If [accept] is already waiting, resumes the
     * coroutine directly. Otherwise, buffers the connection.
     *
     * Thread safety: called from the listener's dispatch queue.
     * Protected by [mutex] to synchronize with [accept].
     */
    internal fun onNewConnection(conn: nw_connection_t) {
        withLock {
            val cont = pendingAcceptConts.removeFirstOrNull()
            if (cont != null) {
                cont.resume(conn)
            } else {
                pendingConnections.addLast(conn)
            }
        }
    }

    /**
     * Suspends until an incoming connection arrives, starts it, and
     * returns a [NwPipelinedChannel].
     *
     * The connection is started via [keel_nw_start_conn_async] on a
     * per-connection serial dispatch queue, suspending until the
     * connection reaches the ready state.
     */
    override suspend fun accept(): PipelinedChannel {
        check(_active) { "StreamServer is closed" }

        // Get a connection: fast path (buffered) or slow path (suspend)
        val conn: nw_connection_t = withLock {
            if (pendingConnections.isNotEmpty()) {
                return@withLock pendingConnections.removeFirst()
            }
            null
        } ?: suspendCancellableCoroutine { cont ->
            withLock {
                if (pendingConnections.isNotEmpty()) {
                    cont.resume(pendingConnections.removeFirst())
                } else {
                    pendingAcceptConts.addLast(cont)
                    cont.invokeOnCancellation {
                        // Identity-based remove via CancellableContinuation's
                        // default Object.equals (reference equality).
                        withLock { pendingAcceptConts.remove(cont) }
                    }
                }
            }
        }

        // Per-connection serial queue for NWConnection callbacks
        val connQueue = dispatch_queue_create(
            "io.github.fukusaka.keel.nwconnection.conn", null,
        )

        // Start connection asynchronously and wait for ready state
        val rc = suspendCancellableCoroutine<Int> { cont ->
            val cbCtx = CallbackContext(cont)
            val ref = StableRef.create(cbCtx)
            keel_nw_start_conn_async(
                conn, connQueue,
                startCallback,
                ref.asCPointer(),
            )
            cont.invokeOnCancellation { cbCtx.markCancelled() }
        }
        check(rc == 0) { "keel_nw_start_conn_async failed" }

        val remoteAddr = extractAddress(conn)
        val logger = loggerFactory.logger("NwPipelinedChannel")
        val transport = NwIoTransport(conn, connQueue, allocator, idleReadPolicy)
        val channel = NwPipelinedChannel(transport, logger, remoteAddr, localAddress)
        bindConfig.initializeConnection(channel)
        return channel
    }

    /**
     * Closes the server channel and stops accepting connections.
     *
     * Idempotent: subsequent calls are no-ops for the accept
     * cancellation, but `nw_listener_cancel` / `pthread_mutex_destroy` /
     * `arena.clear` are called unconditionally (safe to call multiple
     * times on already-cancelled/destroyed resources).
     *
     * If an [accept] coroutine is suspended, it is cancelled with
     * [CancellationException]. Uses [withLock] because [onNewConnection]
     * may run concurrently on the listener's dispatch queue thread.
     */
    override fun close() {
        withLock {
            if (_active) {
                _active = false
                while (pendingAcceptConts.isNotEmpty()) {
                    pendingAcceptConts.removeFirst()
                        .resumeWithException(CancellationException("StreamServer closed"))
                }
            }
        }
        nw_listener_cancel(listener)
        pthread_mutex_destroy(mutex.ptr)
        arena.clear()
    }

    /**
     * Extracts the remote [SocketAddress] from an NWConnection's endpoint.
     */
    private fun extractAddress(conn: nw_connection_t): SocketAddress? {
        val endpoint = nw_connection_copy_endpoint(conn) ?: return null
        val host = nw_endpoint_get_hostname(endpoint)?.toKString() ?: return null
        val port = nw_endpoint_get_port(endpoint).toInt()
        return InetSocketAddress(host, port)
    }

    /** Runs [block] under the pthread mutex. */
    private inline fun <T> withLock(block: () -> T): T {
        pthread_mutex_lock(mutex.ptr)
        try {
            return block()
        } finally {
            pthread_mutex_unlock(mutex.ptr)
        }
    }

    companion object {
        /**
         * C callback for [keel_nw_start_conn_async].
         * Resumes the suspended coroutine with 0 (ready) or -1 (failed).
         *
         * The [StableRef] is always disposed here. If the coroutine was
         * cancelled, [CallbackContext.tryResume] skips the resume.
         */
        private val startCallback = staticCFunction {
                result: Int, ctx: kotlinx.cinterop.COpaquePointer? ->
            val ref = ctx!!.asStableRef<CallbackContext<Int>>()
            ref.get().tryResume(result)
            ref.dispose()
        }
    }
}
