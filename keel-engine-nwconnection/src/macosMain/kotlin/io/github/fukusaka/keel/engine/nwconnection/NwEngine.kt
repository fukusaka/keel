package io.github.fukusaka.keel.engine.nwconnection

import io.github.fukusaka.keel.core.Channel
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.core.PipelinedServer
import io.github.fukusaka.keel.core.ServerChannel
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.core.StreamEngine
import io.github.fukusaka.keel.logging.debug
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.coroutines.suspendCancellableCoroutine
import nwconnection.keel_nw_create_tcp_params
import nwconnection.keel_nw_start_conn_async
import platform.Network.nw_connection_create
import platform.Network.nw_connection_set_queue
import platform.Network.nw_connection_start
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
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.dispatch_time

/**
 * macOS NWConnection-based [StreamEngine] implementation.
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
 *   +-- bind() --> NwServer (wraps nw_listener_t)
 *   |                |
 *   |                +-- accept() --> NwPipelinedChannel (wraps nw_connection_t)
 *   |
 *   +-- connect() --> NwPipelinedChannel (wraps nw_connection_t)
 * ```
 *
 * @param config Engine-wide configuration (allocator, threads).
 */
@OptIn(ExperimentalForeignApi::class)
class NwEngine(
    override val config: IoEngineConfig = IoEngineConfig(),
) : StreamEngine {

    private val logger = config.loggerFactory.logger("NwEngine")
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
        // arrive during startup. localAddress is updated after the
        // assigned port is known.
        val serverChannel = NwServer(
            lsnr, SocketAddress(host, 0), config.allocator, config.loggerFactory,
        )

        nw_listener_set_queue(lsnr, listenerQueue)

        // Suspend until listener reaches ready or failed state.
        // The state_changed_handler resumes the coroutine via CallbackContext.
        // CallbackContext prevents double-resume if the state handler fires
        // multiple times (e.g. ready then cancelled) or after coroutine cancel.
        val assignedPort = suspendCancellableCoroutine<Int> { cont ->
            val cbCtx = CallbackContext(cont)

            nw_listener_set_state_changed_handler(lsnr) { state, _ ->
                if (state == nw_listener_state_ready) {
                    val p = nw_listener_get_port(lsnr).toInt()
                    cbCtx.tryResume(p)
                } else if (state == nw_listener_state_failed) {
                    cbCtx.tryResume(-1)
                }
            }

            nw_listener_set_new_connection_handler(lsnr) { conn ->
                if (conn != null) {
                    serverChannel.onNewConnection(conn)
                }
            }

            nw_listener_start(lsnr)
            cont.invokeOnCancellation { cbCtx.markCancelled() }
        }

        check(assignedPort > 0) {
            "NWListener failed to start (port=$assignedPort)"
        }

        // Update the local address with the assigned port
        serverChannel.updateLocalAddress(SocketAddress(host, assignedPort))
        logger.debug { "Bound to $host:$assignedPort" }
        return serverChannel
    }

    /**
     * Binds a pipeline-based TCP listener on [host]:[port].
     *
     * Creates an NWListener with a new-connection handler that wraps each
     * accepted connection in a [NwPipelinedChannel] and feeds data through
     * the [ChannelPipeline] — no coroutine suspension on the request hot path.
     *
     * Non-suspend: blocks on dispatch_semaphore until the NWListener reaches
     * the ready state (Pipeline zero-coroutine principle). NWListener startup
     * is inherently async; the semaphore bridges it to synchronous return.
     *
     * @param pipelineInitializer Callback to configure the pipeline for each connection.
     * @return A [PipelinedServer] that cancels the listener when closed.
     */
    override fun bindPipeline(
        host: String,
        port: Int,
        pipelineInitializer: (io.github.fukusaka.keel.pipeline.ChannelPipeline) -> Unit,
    ): PipelinedServer {
        check(!closed) { "Engine is closed" }

        val portStr = if (port == 0) "0" else port.toString()
        val params = keel_nw_create_tcp_params()

        val lsnr = nw_listener_create_with_port(portStr, params)
            ?: error("nw_listener_create_with_port returned null")

        val listenerQueue = dispatch_queue_create(
            "io.github.fukusaka.keel.nwconnection.pipeline.listener", null,
        )

        nw_listener_set_queue(lsnr, listenerQueue)

        // Block until listener reaches ready state.
        val sem = dispatch_semaphore_create(0)
        var assignedPort = -1

        nw_listener_set_state_changed_handler(lsnr) { state, _ ->
            if (state == nw_listener_state_ready) {
                assignedPort = nw_listener_get_port(lsnr).toInt()
                dispatch_semaphore_signal(sem)
            } else if (state == nw_listener_state_failed) {
                dispatch_semaphore_signal(sem)
            }
        }

        nw_listener_set_new_connection_handler(lsnr) { conn ->
            if (conn != null) {
                val connQueue = dispatch_queue_create(
                    "io.github.fukusaka.keel.nwconnection.pipeline.conn", null,
                )
                nw_connection_set_queue(conn, connQueue)
                // Fire-and-forget start: nw_connection_receive can be called
                // immediately after start — NWConnection queues the receive
                // internally until the connection reaches the ready state.
                nw_connection_start(conn)

                val channel = NwPipelinedChannel(conn, config.allocator, null, null, logger)
                pipelineInitializer(channel.pipeline)
                channel.armRead()
            }
        }

        nw_listener_start(lsnr)
        // Generous timeout for listener startup, prevents permanent hang
        // if the dispatch queue or state handler is never delivered.
        val deadline = dispatch_time(
            DISPATCH_TIME_NOW, BIND_TIMEOUT_NS,
        )
        val waitResult = dispatch_semaphore_wait(sem, deadline)
        check(waitResult == 0L) {
            "NWListener startup timed out after ${BIND_TIMEOUT_NS / 1_000_000_000L}s"
        }
        check(assignedPort > 0) { "NWListener failed to start" }
        val localAddr = SocketAddress(host, assignedPort)
        logger.debug { "Pipeline bound to $host:$assignedPort" }

        return NwPipelinedServer(lsnr, localAddr)
    }

    /** Pipeline server wrapping an NWListener. */
    private class NwPipelinedServer(
        private val listener: nw_listener_t,
        private val localAddr: SocketAddress,
    ) : PipelinedServer {
        @kotlin.concurrent.Volatile
        private var closed = false

        override val localAddress: SocketAddress get() = localAddr
        override val isActive: Boolean get() = !closed

        override fun close() {
            if (!closed) {
                closed = true
                nw_listener_cancel(listener)
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
            val cbCtx = CallbackContext(cont)
            val ref = StableRef.create(cbCtx)
            keel_nw_start_conn_async(
                conn, connQueue,
                startCallback,
                ref.asCPointer(),
            )
            cont.invokeOnCancellation { cbCtx.markCancelled() }
        }
        check(rc == 0) { "connect to $host:$port failed" }

        val remoteAddr = SocketAddress(
            nw_endpoint_get_hostname(endpoint)?.toKString() ?: host,
            nw_endpoint_get_port(endpoint).toInt(),
        )

        logger.debug { "Connected to ${remoteAddr.host}:${remoteAddr.port}" }
        val channelLogger = config.loggerFactory.logger("NwPipelinedChannel")
        return NwPipelinedChannel(conn, config.allocator, remoteAddr, null, channelLogger)
    }

    override fun close() {
        if (!closed) {
            closed = true
            listener?.let { nw_listener_cancel(it) }
            logger.debug { "Engine closed" }
        }
    }

    companion object {
        // Same callback as NwServer.startCallback. Duplicated because
        // staticCFunction must be defined in the companion of the using class
        // (cannot reference another class's companion private val).
        /** C callback for [keel_nw_start_conn_async]. */
        private val startCallback = staticCFunction {
                result: Int, ctx: kotlinx.cinterop.COpaquePointer? ->
            val ref = ctx!!.asStableRef<CallbackContext<Int>>()
            ref.get().tryResume(result)
            ref.dispose()
        }

        // Generous timeout for blocking operations at server startup.
        // Not on the hot path — only used by bindPipeline.
        private const val BIND_TIMEOUT_NS = 10L * 1_000_000_000L
    }
}
