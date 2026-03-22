package io.github.fukusaka.keel.engine.nwconnection

import io.github.fukusaka.keel.core.Channel
import io.github.fukusaka.keel.core.IoEngine
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.core.ServerChannel
import io.github.fukusaka.keel.core.SocketAddress
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import nwconnection.keel_nw_create_tcp_params
import nwconnection.keel_nw_start_conn_async
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
import platform.darwin.dispatch_queue_create
import kotlin.coroutines.resume

/**
 * macOS NWConnection-based [IoEngine] implementation.
 *
 * Uses Apple's Network.framework ([NWListener]/[NWConnection]) for TCP I/O.
 * All C-level operations (read/write/start) are handled by async wrapper
 * functions in `nwconnection.def` with callback function pointers, bridged
 * to coroutine continuations via [staticCFunction] + [StableRef].
 *
 * No thread blocking occurs — all I/O operations suspend via
 * [suspendCancellableCoroutine] and are resumed by dispatch queue callbacks.
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

    /**
     * Binds a TCP listener on the given host and port.
     *
     * Creates an NWListener, starts it, and suspends until the listener
     * reaches the ready state. The listener's state_changed_handler
     * resumes the coroutine with the assigned port.
     */
    override suspend fun bind(host: String, port: Int): ServerChannel {
        check(!closed) { "Engine is closed" }

        val portStr = if (port == 0) "0" else port.toString()
        val params = keel_nw_create_tcp_params()

        val lsnr = nw_listener_create_with_port(portStr, params)
            ?: error("nw_listener_create_with_port returned null")
        listener = lsnr

        val listenerQueue = dispatch_queue_create(
            "io.github.fukusaka.keel.nwconnection.listener", null,
        )

        // Create ServerChannel before starting the listener so
        // onNewConnection can be called immediately if connections
        // arrive during startup.
        val serverChannel = NwServerChannel(
            lsnr, SocketAddress(host, 0), config.allocator,
        )

        nw_listener_set_queue(lsnr, listenerQueue)

        // Suspend until listener reaches ready or failed state.
        // The state_changed_handler resumes the coroutine.
        val assignedPort = suspendCancellableCoroutine { cont ->
            var failureReason: String? = null
            nw_listener_set_state_changed_handler(lsnr) { state, error ->
                if (state == nw_listener_state_ready) {
                    val p = nw_listener_get_port(lsnr).toInt()
                    cont.resume(p)
                } else if (state == nw_listener_state_failed) {
                    if (error != null) {
                        val code = platform.Network.nw_error_get_error_code(error)
                        val domain = platform.Network.nw_error_get_error_domain(error)
                        failureReason = "domain=$domain, code=$code"
                    }
                    cont.resume(-1)
                }
            }

            nw_listener_set_new_connection_handler(lsnr) { conn ->
                if (conn != null) {
                    serverChannel.onNewConnection(conn)
                }
            }

            nw_listener_start(lsnr)
        }

        check(assignedPort > 0) {
            "NWListener failed to start (port=$assignedPort)"
        }

        // Update the local address with the assigned port
        return NwServerChannel(
            lsnr, SocketAddress(host, assignedPort), config.allocator,
        ).also { newChannel ->
            // Re-wire the connection handler to the new ServerChannel
            nw_listener_set_new_connection_handler(lsnr) { conn ->
                if (conn != null) {
                    newChannel.onNewConnection(conn)
                }
            }
        }
    }

    /**
     * Creates a TCP client connection.
     *
     * Starts the NWConnection asynchronously via [keel_nw_start_conn_async]
     * and suspends until it reaches the ready state.
     */
    override suspend fun connect(host: String, port: Int): Channel {
        check(!closed) { "Engine is closed" }

        val endpoint = nw_endpoint_create_host(host, port.toString())
        val params = keel_nw_create_tcp_params()
        val conn = nw_connection_create(endpoint, params)
            ?: error("nw_connection_create returned null")

        val connQueue = dispatch_queue_create(
            "io.github.fukusaka.keel.nwconnection.conn", null,
        )

        val rc = suspendCancellableCoroutine<Int> { cont ->
            val ref = StableRef.create(cont)
            keel_nw_start_conn_async(
                conn, connQueue,
                startCallback,
                ref.asCPointer(),
            )
            cont.invokeOnCancellation { ref.dispose() }
        }
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

    companion object {
        /** C callback for [keel_nw_start_conn_async]. */
        private val startCallback = staticCFunction {
                result: Int, ctx: kotlinx.cinterop.COpaquePointer? ->
            val ref = ctx!!.asStableRef<CancellableContinuation<Int>>()
            ref.get().resume(result)
            ref.dispose()
        }
    }
}
