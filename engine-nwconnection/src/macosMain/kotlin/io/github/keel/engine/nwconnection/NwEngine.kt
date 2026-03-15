package io.github.keel.engine.nwconnection

import kotlinx.cinterop.ExperimentalForeignApi
import nwconnection.keel_nw_create_tcp_params
import nwconnection.keel_nw_start_connection
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

@OptIn(ExperimentalForeignApi::class)
class NwEngine : AutoCloseable {

    private val listenerQueue = dispatch_queue_create("io.github.keel.nwconnection.listener", null)

    private var listener: nw_listener_t? = null
    private var _isListening = false

    val isListening: Boolean get() = _isListening

    /**
     * Starts a TCP listener on [port] (0 = OS-assigned). Blocks until the listener is ready.
     * Returns the assigned port number.
     */
    fun bind(port: Int): Int {
        val portStr = if (port == 0) "0" else port.toString()
        val params = keel_nw_create_tcp_params()

        val lsnr = nw_listener_create_with_port(portStr, params)
            ?: error("nw_listener_create_with_port returned null")
        listener = lsnr

        val readySem = dispatch_semaphore_create(0L)
        var assignedPort = -1

        nw_listener_set_queue(lsnr, listenerQueue)

        nw_listener_set_state_changed_handler(lsnr) { state, _ ->
            if (state == nw_listener_state_ready) {
                assignedPort = nw_listener_get_port(lsnr).toInt()
                _isListening = true
                dispatch_semaphore_signal(readySem)
            } else if (state == nw_listener_state_failed) {
                dispatch_semaphore_signal(readySem)
            }
        }

        // keel_nw_start_connection handles queue/state-handler/start/receive in C,
        // avoiding Kotlin/Native ObjC block limitations with bool-typed parameters.
        nw_listener_set_new_connection_handler(lsnr) { conn ->
            if (conn != null) keel_nw_start_connection(conn)
        }

        nw_listener_start(lsnr)
        dispatch_semaphore_wait(readySem, DISPATCH_TIME_FOREVER)

        check(assignedPort > 0) { "NWListener failed to start (port=$assignedPort)" }
        return assignedPort
    }

    override fun close() {
        _isListening = false
        listener?.let { nw_listener_cancel(it) }
    }
}
