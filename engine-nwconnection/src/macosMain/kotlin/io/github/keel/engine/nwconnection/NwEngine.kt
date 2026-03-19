package io.github.keel.engine.nwconnection

import io.github.keel.core.Channel
import io.github.keel.core.IoEngine
import io.github.keel.core.IoEngineConfig
import io.github.keel.core.ServerChannel
import io.github.keel.core.SocketAddress
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import nwconnection.keel_nw_create_tcp_params
import nwconnection.keel_nw_start_conn
import platform.Network.nw_connection_create
import platform.Network.nw_endpoint_create_host
import platform.Network.nw_endpoint_get_hostname
import platform.Network.nw_endpoint_get_port
import platform.Network.nw_listener_cancel
import platform.Network.nw_listener_create_with_port
import platform.Network.nw_listener_get_port
import platform.Network.nw_listener_set_new_connection_handler
import platform.Network.nw_listener_set_queue
import platform.Network.nw_listener_set_state_changed_handler
import platform.Network.nw_listener_state_failed
import platform.Network.nw_listener_state_ready
import platform.Network.nw_listener_start
import platform.Network.nw_listener_t
import platform.darwin.DISPATCH_TIME_FOREVER
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait

/**
 * macOS NWConnection-based [IoEngine] implementation.
 *
 * Uses Apple's Network.framework ([NWListener]/[NWConnection]) for TCP I/O.
 * All C-level operations (read/write/start) are handled by wrapper functions
 * in `nwconnection.def` to work around Kotlin/Native's inability to convert
 * ObjC blocks containing `bool` parameters.
 *
 * Phase (a): synchronous I/O. All suspend functions block internally via
 * dispatch semaphores.
 *
 * ```
 * NwEngine
 *   |
 *   +-- bind() --> NwServerChannel (wraps nw_listener_t)
 *   |                |
 *   |                +-- accept() --> NwChannel (wraps nw_connection_t)
 *   |
 *   +-- connect() --> NwChannel (wraps nw_connection_t)
 * ```
 *
 * @param config Engine-wide configuration (allocator, threads).
 */
@OptIn(ExperimentalForeignApi::class)
class NwEngine(
    private val config: IoEngineConfig = IoEngineConfig(),
) : IoEngine {

    private var listener: nw_listener_t = null
    private var closed = false

    override suspend fun bind(host: String, port: Int): ServerChannel {
        check(!closed) { "Engine is closed" }

        val portStr = if (port == 0) "0" else port.toString()
        val params = keel_nw_create_tcp_params()

        val lsnr = nw_listener_create_with_port(portStr, params)
            ?: error("nw_listener_create_with_port returned null")
        listener = lsnr

        val listenerQueue = dispatch_queue_create(
            "io.github.keel.nwconnection.listener", null,
        )

        val readySem = dispatch_semaphore_create(0L)
        var assignedPort = -1

        nw_listener_set_queue(lsnr, listenerQueue)

        nw_listener_set_state_changed_handler(lsnr) { state, _ ->
            if (state == nw_listener_state_ready) {
                assignedPort = nw_listener_get_port(lsnr).toInt()
                dispatch_semaphore_signal(readySem)
            } else if (state == nw_listener_state_failed) {
                dispatch_semaphore_signal(readySem)
            }
        }

        // Set a temporary no-op handler; replaced after port is assigned.
        nw_listener_set_new_connection_handler(lsnr) { _ -> }

        nw_listener_start(lsnr)
        val timeout = platform.darwin.dispatch_time(platform.darwin.DISPATCH_TIME_NOW, 5L * 1_000_000_000L)
        val waitResult = dispatch_semaphore_wait(readySem, timeout)
        check(waitResult == 0L) { "NWListener bind timed out" }

        check(assignedPort > 0) { "NWListener failed to start (port=$assignedPort)" }

        // Create ServerChannel now that the assigned port is known.
        // The listener only accepts connections after reaching the ready state,
        // so no connections are lost between start and handler registration.
        val serverChannel = NwServerChannel(
            lsnr, SocketAddress(host, assignedPort), config.allocator,
        )
        nw_listener_set_new_connection_handler(lsnr) { conn ->
            if (conn != null) {
                serverChannel.pendingConnections.add(conn)
                dispatch_semaphore_signal(serverChannel.acceptSem)
            }
        }
        return serverChannel
    }

    /**
     * Phase (a): blocking connect. Creates an NWConnection to the remote
     * host, starts it via [keel_nw_start_conn], and returns an [NwChannel].
     */
    override suspend fun connect(host: String, port: Int): Channel {
        check(!closed) { "Engine is closed" }

        val endpoint = nw_endpoint_create_host(host, port.toString())
        val params = keel_nw_create_tcp_params()
        val conn = nw_connection_create(endpoint, params)
            ?: error("nw_connection_create returned null")

        val connQueue = dispatch_queue_create(
            "io.github.keel.nwconnection.conn", null,
        )

        val rc = keel_nw_start_conn(conn, connQueue)
        check(rc == 0) { "connect to $host:$port failed" }

        val remoteAddr = SocketAddress(
            nw_endpoint_get_hostname(endpoint)?.toKString() ?: host,
            nw_endpoint_get_port(endpoint).toInt(),
        )

        return NwChannel(conn, config.allocator, remoteAddr, null)
    }

    override fun close() {
        if (!closed) {
            closed = true
            listener?.let { nw_listener_cancel(it) }
        }
    }
}
