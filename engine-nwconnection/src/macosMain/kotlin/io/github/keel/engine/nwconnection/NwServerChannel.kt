package io.github.keel.engine.nwconnection

import io.github.keel.core.BufferAllocator
import io.github.keel.core.Channel
import io.github.keel.core.ServerChannel
import io.github.keel.core.SocketAddress
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import nwconnection.keel_nw_start_conn
import platform.Network.nw_connection_copy_endpoint
import platform.Network.nw_connection_t
import platform.Network.nw_endpoint_get_hostname
import platform.Network.nw_endpoint_get_port
import platform.Network.nw_listener_cancel
import platform.Network.nw_listener_t
import platform.darwin.DISPATCH_TIME_FOREVER
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait

/**
 * NWConnection-based [ServerChannel] implementation for macOS.
 *
 * Wraps an [nw_listener_t] and accepts incoming connections via a
 * semaphore-synchronised queue. The listener's new-connection handler
 * (set by [NwEngine.bind]) pushes [nw_connection_t] instances into
 * [pendingConnections] and signals [acceptSem].
 *
 * Phase (a): [accept] blocks on the semaphore until a connection arrives.
 *
 * ```
 * accept() flow:
 *   dispatch_semaphore_wait(acceptSem)  -- blocks until new connection
 *     --> take nw_connection_t from pendingConnections
 *   keel_nw_start_conn(conn, queue)     -- wait for ready state
 *     --> extract remote/local address from endpoint
 *   --> NwChannel(conn, allocator, remoteAddr, localAddr)
 * ```
 *
 * Thread safety: [pendingConnections] is written by the listener's
 * dispatch queue callback and read by the caller's thread.
 * The semaphore serialises access (signal after add, wait before take).
 *
 * @param listener    The NWListener handle.
 * @param localAddress Bind address of this server channel.
 * @param allocator   Passed to accepted [NwChannel]s.
 */
@OptIn(ExperimentalForeignApi::class)
internal class NwServerChannel(
    private val listener: nw_listener_t,
    override val localAddress: SocketAddress,
    private val allocator: BufferAllocator,
) : ServerChannel {

    private var _active = true

    /** Queue of connections awaiting accept(). Written by listener callback. */
    internal val pendingConnections = mutableListOf<nw_connection_t>()

    /** Signalled when a new connection is added to pendingConnections. */
    internal val acceptSem = dispatch_semaphore_create(0L)!!

    override val isActive: Boolean get() = _active

    /**
     * Waits for an incoming connection, starts it, and returns a [NwChannel].
     *
     * Blocks on [acceptSem] until the listener's new-connection handler
     * pushes a connection. The connection is then started via
     * [keel_nw_start_conn] on a per-connection serial dispatch queue.
     */
    override suspend fun accept(): Channel {
        check(_active) { "ServerChannel is closed" }

        // 5-second timeout to prevent indefinite blocking in tests.
        // dispatch_time(DISPATCH_TIME_NOW, 5 * NSEC_PER_SEC)
        val timeout = platform.darwin.dispatch_time(platform.darwin.DISPATCH_TIME_NOW, 5L * 1_000_000_000L)
        val result = dispatch_semaphore_wait(acceptSem, timeout)
        if (result != 0L) error("accept() timed out — no connection within 5 seconds")
        check(_active) { "ServerChannel closed while waiting for accept" }

        // Thread safety: the semaphore guarantees at least one element exists.
        // Phase (a) is single-threaded on the accept() side; the listener
        // callback only adds (never removes), so no lock is needed.
        val conn = pendingConnections.removeFirst()

        // Per-connection serial queue for NWConnection callbacks
        val connQueue = dispatch_queue_create(
            "io.github.keel.nwconnection.conn", null,
        )

        val rc = keel_nw_start_conn(conn, connQueue)
        check(rc == 0) { "keel_nw_start_conn failed" }

        val remoteAddr = extractAddress(conn)
        // Accepted connections share the listener's local address
        return NwChannel(conn, allocator, remoteAddr, localAddress)
    }

    override fun close() {
        if (_active) {
            _active = false
            nw_listener_cancel(listener)
            // Unblock any pending accept() call
            dispatch_semaphore_signal(acceptSem)
        }
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
}
