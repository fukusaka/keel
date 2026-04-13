package io.github.fukusaka.keel.engine.nwconnection

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.Channel
import io.github.fukusaka.keel.core.ServerChannel
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.logging.LoggerFactory
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
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
import kotlinx.cinterop.Arena
import kotlinx.cinterop.alloc
import kotlinx.cinterop.ptr

/**
 * NWConnection-based [ServerChannel] implementation for macOS.
 *
 * Wraps an [nw_listener_t] and accepts incoming connections via
 * [suspendCancellableCoroutine]. The listener's new-connection handler
 * (set by [NwEngine.bind]) calls [onNewConnection] for each accepted
 * connection, which either resumes a waiting [accept] coroutine or
 * buffers the connection for the next [accept] call.
 *
 * **Thread safety**: [onNewConnection] is called from the listener's
 * dispatch queue while [accept] runs on a coroutine thread. All access
 * to [pendingConnections] and [pendingAcceptCont] is protected by
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
internal class NwServer(
    private val listener: nw_listener_t,
    localAddress: SocketAddress,
    private val allocator: BufferAllocator,
    private val bindConfig: BindConfig,
    private val loggerFactory: LoggerFactory,
) : ServerChannel {

    private val arena = Arena()
    private val mutex = arena.alloc<pthread_mutex_t>().apply {
        pthread_mutex_init(ptr, null)
    }
    private val pendingConnections = ArrayDeque<nw_connection_t>()
    private var pendingAcceptCont: CancellableContinuation<nw_connection_t>? = null
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
            val cont = pendingAcceptCont
            if (cont != null) {
                pendingAcceptCont = null
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
        check(_active) { "ServerChannel is closed" }

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
                    pendingAcceptCont = cont
                    cont.invokeOnCancellation {
                        withLock { pendingAcceptCont = null }
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
        val transport = NwIoTransport(conn, allocator)
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
                pendingAcceptCont?.resumeWithException(
                    CancellationException("ServerChannel closed"),
                )
                pendingAcceptCont = null
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
        return SocketAddress(host, port)
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
