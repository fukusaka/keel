package io.github.fukusaka.keel.engine.nwconnection

import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.Channel
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.core.PipelinedServer
import io.github.fukusaka.keel.core.ServerChannel
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.core.StreamEngine
import io.github.fukusaka.keel.core.UnixSocketAddress
import io.github.fukusaka.keel.core.requireFilesystemOnly
import io.github.fukusaka.keel.core.requireIpLiteral
import io.github.fukusaka.keel.core.resolveFirst
import io.github.fukusaka.keel.logging.debug
import io.github.fukusaka.keel.tls.Pkcs8KeyUnwrapper
import io.github.fukusaka.keel.tls.TlsCodecFactory
import io.github.fukusaka.keel.tls.TlsConnectorConfig
import io.github.fukusaka.keel.tls.asDer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.suspendCancellableCoroutine
import nwconnection.keel_nw_create_tcp_params
import nwconnection.keel_nw_create_tcp_params_unix_listener
import nwconnection.keel_nw_endpoint_create_unix
import nwconnection.keel_nw_start_conn_async
import nwconnection.keel_nw_unix_path_max
import platform.Network.nw_connection_create
import platform.Network.nw_connection_set_queue
import platform.Network.nw_connection_start
import platform.Network.nw_endpoint_create_host
import platform.Network.nw_endpoint_get_hostname
import platform.Network.nw_endpoint_get_port
import platform.Network.nw_listener_cancel
import platform.Network.nw_listener_create
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
import kotlin.coroutines.CoroutineContext

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

    override val coroutineContext: CoroutineContext = SupervisorJob()

    private val logger = config.loggerFactory.logger("NwEngine")
    private var listener: nw_listener_t = null
    private var closed = false

    /**
     * Binds a TCP listener on the given host and port.
     *
     * Creates an NWListener, starts it, and suspends until the listener
     * reaches the ready state. The listener's state_changed_handler
     * resumes the coroutine with the assigned port.
     *
     * Note: [BindConfig.backlog] is ignored. NWListener does not expose
     * a configurable listen backlog; the OS manages it internally.
     */
    override suspend fun bind(address: SocketAddress, bindConfig: BindConfig): ServerChannel = when (address) {
        is InetSocketAddress -> bindInet(address, bindConfig)
        is UnixSocketAddress -> bindUnix(address, bindConfig)
    }

    private suspend fun bindInet(address: InetSocketAddress, bindConfig: BindConfig): ServerChannel {
        check(!closed) { "Engine is closed" }

        val host = address.resolveFirst(config.resolver).toCanonicalString()
        val port = address.port
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
            lsnr, InetSocketAddress(host, 0), config.allocator, bindConfig, config.loggerFactory,
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
        serverChannel.updateLocalAddress(InetSocketAddress(host, assignedPort))
        logger.debug { "Bound to $host:$assignedPort" }
        return serverChannel
    }

    /**
     * Binds a pipeline-based TCP listener on [host]:[port].
     *
     * Creates an NWListener with a new-connection handler that wraps each
     * accepted connection in a [NwPipelinedChannel] and feeds data through
     * the [Pipeline] — no coroutine suspension on the request hot path.
     *
     * Non-suspend: blocks on dispatch_semaphore until the NWListener reaches
     * the ready state (Pipeline zero-coroutine principle). NWListener startup
     * is inherently async; the semaphore bridges it to synchronous return.
     *
     * Note: [BindConfig.backlog] is ignored. NWListener does not expose
     * a configurable listen backlog; the OS manages it internally.
     *
     * @param pipelineInitializer Callback to configure the pipeline for each connection.
     * @return A [PipelinedServer] that cancels the listener when closed.
     */
    override fun bindPipeline(
        address: SocketAddress,
        config: BindConfig,
        pipelineInitializer: (io.github.fukusaka.keel.pipeline.PipelinedChannel) -> Unit,
    ): PipelinedServer = when (address) {
        is InetSocketAddress -> bindPipelineInet(address, config, pipelineInitializer)
        is UnixSocketAddress -> bindPipelineUnix(address, config, pipelineInitializer)
    }

    private fun bindPipelineInet(
        address: InetSocketAddress,
        config: BindConfig,
        pipelineInitializer: (io.github.fukusaka.keel.pipeline.PipelinedChannel) -> Unit,
    ): PipelinedServer {
        check(!closed) { "Engine is closed" }

        val host = address.requireIpLiteral()
        val port = address.port
        val portStr = if (port == 0) "0" else port.toString()
        val listenerLevelTls = isListenerLevelTls(config)
        val params = if (listenerLevelTls) {
            createTlsParams(config as TlsConnectorConfig)
        } else {
            keel_nw_create_tcp_params()
        }

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

                val transport = NwIoTransport(conn, connQueue, this@NwEngine.config.allocator)
                val channel = NwPipelinedChannel(transport, logger)
                // Listener-level TLS: connections arrive already TLS-encrypted,
                // so skip per-connection TLS initialization.
                if (!listenerLevelTls) {
                    config.initializeConnection(channel)
                }
                pipelineInitializer(channel)
                transport.readEnabled = true
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
        val localAddr = InetSocketAddress(host, assignedPort)
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
    override suspend fun connect(address: SocketAddress): Channel = when (address) {
        is InetSocketAddress -> connectInet(address)
        is UnixSocketAddress -> connectUnix(address)
    }

    private suspend fun connectInet(address: InetSocketAddress): Channel {
        check(!closed) { "Engine is closed" }

        val host = address.resolveFirst(config.resolver).toCanonicalString()
        val port = address.port
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

        val remoteAddr = InetSocketAddress(
            nw_endpoint_get_hostname(endpoint)?.toKString() ?: host,
            nw_endpoint_get_port(endpoint).toInt(),
        )

        logger.debug { "Connected to $remoteAddr" }
        val channelLogger = config.loggerFactory.logger("NwPipelinedChannel")
        val transport = NwIoTransport(conn, connQueue, config.allocator)
        return NwPipelinedChannel(transport, channelLogger, remoteAddr, null)
    }

    /**
     * Binds a filesystem Unix-domain listener.
     *
     * Builds an NWEndpoint from a `sockaddr_un` via the public
     * `nw_endpoint_create_address(const struct sockaddr *)` API, attaches it
     * as `requiredLocalEndpoint` on plain TCP parameters, and creates the
     * listener with the no-port `nw_listener_create(parameters)` variant.
     * This is the pattern documented by Apple DTS
     * (developer.apple.com/forums/thread/756756); the SPI
     * `nw_endpoint_create_unix` symbol is intentionally avoided to keep the
     * engine App Store reviewable.
     *
     * macOS / iOS do not support Linux abstract-namespace sockets; such
     * addresses are rejected up front. [BindConfig.backlog] is ignored
     * (NWListener does not expose a configurable backlog).
     */
    private suspend fun bindUnix(address: UnixSocketAddress, bindConfig: BindConfig): ServerChannel {
        check(!closed) { "Engine is closed" }
        address.requireFilesystemOnly("NwEngine does not support abstract-namespace Unix sockets")
        validateUnixPath(address.path)

        val params = keel_nw_create_tcp_params_unix_listener(address.path)
            ?: error("nw_endpoint_create_address(sockaddr_un) failed for UDS path ${address.path}")
        val lsnr = nw_listener_create(params)
            ?: error("nw_listener_create returned null for ${address.path}")
        listener = lsnr

        val listenerQueue = dispatch_queue_create(
            "io.github.fukusaka.keel.nwconnection.listener.unix", null,
        )
        val serverChannel = NwServer(
            lsnr, address, config.allocator, bindConfig, config.loggerFactory,
        )
        nw_listener_set_queue(lsnr, listenerQueue)

        val rc = suspendCancellableCoroutine<Int> { cont ->
            val cbCtx = CallbackContext(cont)
            nw_listener_set_state_changed_handler(lsnr) { state, _ ->
                if (state == nw_listener_state_ready) {
                    cbCtx.tryResume(0)
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
        check(rc == 0) { "NWListener failed to start on ${address.path}" }

        logger.debug { "Bound UDS ${address.path}" }
        return serverChannel
    }

    /**
     * Creates a client connection to a filesystem Unix-domain socket path.
     */
    private suspend fun connectUnix(address: UnixSocketAddress): Channel {
        check(!closed) { "Engine is closed" }
        address.requireFilesystemOnly("NwEngine does not support abstract-namespace Unix sockets")
        validateUnixPath(address.path)

        val endpoint = keel_nw_endpoint_create_unix(address.path)
            ?: error("nw_endpoint_create_address(sockaddr_un) failed for UDS path ${address.path}")
        val params = keel_nw_create_tcp_params()
        val conn = nw_connection_create(endpoint, params)
            ?: error("nw_connection_create returned null")

        val connQueue = dispatch_queue_create(
            "io.github.fukusaka.keel.nwconnection.conn.unix", null,
        )

        val rc = suspendCancellableCoroutine<Int> { cont ->
            val cbCtx = CallbackContext(cont)
            val ref = StableRef.create(cbCtx)
            keel_nw_start_conn_async(conn, connQueue, startCallback, ref.asCPointer())
            cont.invokeOnCancellation { cbCtx.markCancelled() }
        }
        check(rc == 0) { "connect to UDS ${address.path} failed" }

        logger.debug { "Connected to UDS ${address.path}" }
        val channelLogger = config.loggerFactory.logger("NwPipelinedChannel")
        val transport = NwIoTransport(conn, connQueue, config.allocator)
        return NwPipelinedChannel(transport, channelLogger, address, address)
    }

    /**
     * Pipeline-mode UDS listener. Mirrors [bindPipelineInet] but binds via
     * `nw_parameters_set_local_endpoint` with a `sockaddr_un`-backed endpoint
     * (public API — see [bindUnix]) instead of a TCP port.
     * Listener-level TLS is rejected for UDS (does not fit the UDS threat model).
     */
    private fun bindPipelineUnix(
        address: UnixSocketAddress,
        config: BindConfig,
        pipelineInitializer: (io.github.fukusaka.keel.pipeline.PipelinedChannel) -> Unit,
    ): PipelinedServer {
        check(!closed) { "Engine is closed" }
        address.requireFilesystemOnly("NwEngine does not support abstract-namespace Unix sockets")
        validateUnixPath(address.path)
        require(!isListenerLevelTls(config)) {
            "NwEngine does not support listener-level TLS over UDS"
        }

        val params = keel_nw_create_tcp_params_unix_listener(address.path)
            ?: error("nw_endpoint_create_address(sockaddr_un) failed for UDS path ${address.path}")
        val lsnr = nw_listener_create(params)
            ?: error("nw_listener_create returned null for ${address.path}")

        val listenerQueue = dispatch_queue_create(
            "io.github.fukusaka.keel.nwconnection.pipeline.listener.unix", null,
        )
        nw_listener_set_queue(lsnr, listenerQueue)

        val sem = dispatch_semaphore_create(0)
        var ready = false
        nw_listener_set_state_changed_handler(lsnr) { state, _ ->
            if (state == nw_listener_state_ready) {
                ready = true
                dispatch_semaphore_signal(sem)
            } else if (state == nw_listener_state_failed) {
                dispatch_semaphore_signal(sem)
            }
        }
        nw_listener_set_new_connection_handler(lsnr) { conn ->
            if (conn != null) {
                val connQueue = dispatch_queue_create(
                    "io.github.fukusaka.keel.nwconnection.pipeline.conn.unix", null,
                )
                nw_connection_set_queue(conn, connQueue)
                nw_connection_start(conn)

                val transport = NwIoTransport(conn, connQueue, this@NwEngine.config.allocator)
                val channel = NwPipelinedChannel(transport, logger)
                config.initializeConnection(channel)
                pipelineInitializer(channel)
                transport.readEnabled = true
            }
        }

        nw_listener_start(lsnr)
        val deadline = dispatch_time(DISPATCH_TIME_NOW, BIND_TIMEOUT_NS)
        val waitResult = dispatch_semaphore_wait(sem, deadline)
        check(waitResult == 0L) {
            "NWListener startup timed out after ${BIND_TIMEOUT_NS / 1_000_000_000L}s"
        }
        check(ready) { "NWListener failed to start on ${address.path}" }
        logger.debug { "Pipeline bound UDS ${address.path}" }
        return NwPipelinedServer(lsnr, address)
    }

    /**
     * Validates filesystem UDS path fits Darwin's `sun_path[104]` limit
     * (including NUL terminator). Fails fast rather than letting the
     * kernel return EINVAL / ENAMETOOLONG deep in Network.framework.
     */
    private fun validateUnixPath(path: String) {
        val maxLen = keel_nw_unix_path_max().toInt()
        val byteLen = path.encodeToByteArray().size + 1 // +1 for NUL
        require(byteLen <= maxLen) {
            "UDS path exceeds Darwin sun_path limit ($byteLen > $maxLen bytes incl. NUL): $path"
        }
    }

    /**
     * Closes the engine: cancels every child coroutine launched on this
     * engine's scope, joins their completion, then cancels the NWListener.
     *
     * The `job.cancelAndJoin()` step runs first so children suspended on
     * GCD-backed dispatchers observe cancellation and unwind before the
     * listener is torn down. Idempotent.
     */
    override suspend fun close() {
        if (!closed) {
            closed = true
            coroutineContext.job.cancelAndJoin()
            listener?.let { nw_listener_cancel(it) }
            logger.debug { "Engine closed" }
        }
    }

    /**
     * Detects if the config requests engine-native (listener-level) TLS.
     *
     * [TlsConnectorConfig] with `installer == null` means the engine should
     * handle TLS at the listener level. Non-null installer means per-connection
     * TLS via [initializeConnection].
     */
    private fun isListenerLevelTls(config: BindConfig): Boolean {
        return config is TlsConnectorConfig && config.installer == null
    }

    /**
     * Creates TLS-enabled NWConnection parameters from [TlsConnectorConfig].
     *
     * Converts certificates to DER, unwraps PKCS#8 if needed, and delegates
     * to [NwTlsParams.createTlsParameters] for SecIdentity creation.
     */
    private fun createTlsParams(tlsConfig: TlsConnectorConfig): platform.Network.nw_parameters_t {
        val certs = requireNotNull(tlsConfig.config.certificates) {
            "NWConnection listener-level TLS requires certificates"
        }.asDer()
        val keyDer = certs.privateKey
        val (innerKey, algorithm) = if (Pkcs8KeyUnwrapper.isPkcs8(keyDer)) {
            Pkcs8KeyUnwrapper.unwrap(keyDer)
        } else {
            // Already inner key format (PKCS#1/SEC1)
            Pkcs8KeyUnwrapper.UnwrapResult(keyDer, Pkcs8KeyUnwrapper.KeyAlgorithm.UNKNOWN)
        }
        return NwTlsParams.createTlsParameters(certs.certificate, innerKey, algorithm)
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
