package io.github.fukusaka.keel.engine.nwconnection

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.BindSpec
import io.github.fukusaka.keel.core.Channel
import io.github.fukusaka.keel.core.ConnectConfig
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.core.SocketOptions
import io.github.fukusaka.keel.core.StreamEngine
import io.github.fukusaka.keel.core.StreamServer
import io.github.fukusaka.keel.core.UnixSocketAddress
import io.github.fukusaka.keel.core.bindAllOrRollback
import io.github.fukusaka.keel.core.requireFilesystemOnly
import io.github.fukusaka.keel.core.requireIpLiteral
import io.github.fukusaka.keel.core.resolveFirst
import io.github.fukusaka.keel.logging.debug
import io.github.fukusaka.keel.logging.guarded
import io.github.fukusaka.keel.logging.warn
import io.github.fukusaka.keel.pipeline.PipelinedStreamServer
import io.github.fukusaka.keel.server.ServerTlsProvider
import io.github.fukusaka.keel.server.TlsServerConfig
import io.github.fukusaka.keel.tls.TlsConfig
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import nwconnection.keel_nw_create_tcp_params
import nwconnection.keel_nw_create_tcp_params_unix_listener
import nwconnection.keel_nw_create_tcp_params_unix_listener_with_options
import nwconnection.keel_nw_create_tcp_params_with_options
import nwconnection.keel_nw_endpoint_create_unix
import nwconnection.keel_nw_error_posix_code
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
import platform.Network.nw_listener_start
import platform.Network.nw_listener_state_failed
import platform.Network.nw_listener_state_ready
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
 * **I/O ownership invariant**: each NWConnection owns a per-connection
 * serial dispatch queue. All read / write / state-change callbacks plus
 * every coroutine resumption that uses `NwIoTransport.ioDispatcher` are
 * serialised on that queue in FIFO order. This matches the "strict
 * single-thread per loop + cross-thread funnel" contract of the POSIX
 * engines (`engine-kqueue` / `engine-epoll` / `engine-nio`) but the
 * enforcement is upstream-delegated: GCD (Apple's libdispatch)
 * guarantees the serial semantics at the runtime level, so this engine
 * does not need an application-level funnel. Callback entry points
 * declare the invariant inline via
 * [NwConnectionQueueDispatcher.assertInConnectionQueue], the same
 * fail-fast contract that `assertInEventLoop` provides on the POSIX
 * engines. See `IoEngine` KDoc for the cross-engine contract.
 *
 * ```
 * NwEngine
 *   |
 *   +-- bind() --> NwStreamServer (wraps nw_listener_t)
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
) : StreamEngine, ServerTlsProvider {

    override val coroutineContext: CoroutineContext = SupervisorJob()

    /**
     * The configured factory, wrapped so no log statement in this engine can
     * throw. Read once here rather than at each use; see `guarded`.
     */
    private val guardedLoggerFactory = config.loggerFactory.guarded()

    private val logger = guardedLoggerFactory.logger("NwEngine")
    private var listener: nw_listener_t = null
    private var closed = false

    /**
     * Engine-owned allocator child. NwEngine has no per-thread split
     * (NWConnection dispatches connections across GCD workers), so the
     * engine takes a single [BufferAllocator.createChild] off the
     * user-passed [config].allocator and routes every connection's
     * buffers through it. The parent stays borrowed — multiple engines
     * may share one parent allocator — but every per-connection use
     * goes through this child so [close] can release the child's pool
     * resources without touching the parent.
     */
    private val allocator: BufferAllocator =
        // Pure parent: every per-connection transport takes its own createChild() off
        // this (NwStreamServer / connect() pass it as parentAllocator only), so this
        // allocator's own freelist is never directly allocated from or released to —
        // its confinement is never exercised. The per-connection children install a
        // queue-identity NwQueueConfinement (see NwIoTransport); this one is left on
        // the default ThreadIdConfinement.
        config.allocator.createChild()

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
    override suspend fun bind(address: SocketAddress, bindConfig: BindConfig): StreamServer = when (address) {
        is InetSocketAddress -> bindInet(address, bindConfig)
        is UnixSocketAddress -> bindUnix(address, bindConfig)
    }

    private suspend fun bindInet(address: InetSocketAddress, bindConfig: BindConfig): StreamServer {
        check(!closed) { "Engine is closed" }

        val host = address.resolveFirst(config.resolver).toCanonicalString()
        val port = address.port
        val portStr = if (port == 0) "0" else port.toString()
        val params = createTcpParams(bindConfig.childSocketOptions)

        val lsnr = nw_listener_create_with_port(portStr, params)
            ?: error("nw_listener_create_with_port returned null")

        try {
            listener = lsnr

            val listenerQueue = dispatch_queue_create(
                "io.github.fukusaka.keel.nwconnection.listener",
                null,
            )

            // Create StreamServer before starting the listener so
            // onNewConnection can be called immediately if connections
            // arrive during startup. localAddress is updated after the
            // assigned port is known.
            val serverChannel = NwStreamServer(
                lsnr,
                InetSocketAddress(host, 0),
                allocator,
                bindConfig,
                guardedLoggerFactory,
                config.idleReadPolicy,
                config.idleTimeoutMillis,
                config.flushCoalescing,
                ::trackConnection,
            )

            nw_listener_set_queue(lsnr, listenerQueue)

            // Suspend until listener reaches ready or failed state.
            // The state_changed_handler resumes the coroutine via CallbackContext.
            // CallbackContext prevents double-resume if the state handler fires
            // multiple times (e.g. ready then cancelled) or after coroutine cancel.
            var listenerErrno = 0
            val assignedPort = suspendCancellableCoroutine<Int> { cont ->
                val cbCtx = CallbackContext(cont)

                nw_listener_set_state_changed_handler(lsnr) { state, error ->
                    if (state == nw_listener_state_ready) {
                        val p = nw_listener_get_port(lsnr).toInt()
                        cbCtx.tryResume(p)
                    } else if (state == nw_listener_state_failed) {
                        // Capture the POSIX errno (e.g. EADDRINUSE) before
                        // resuming so the failure carries the real kernel
                        // reason rather than an opaque port=-1.
                        listenerErrno = keel_nw_error_posix_code(error)
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

            // errno 48 (EADDRINUSE) on a port that should be free is usually
            // the Network.framework reuse limitation: NWListener does not
            // honour reuse_local_address over a local-port TIME_WAIT, so an
            // immediate same-port restart fails. See keel_nw_create_tcp_params
            // (Apple Radar FB8658821); use the kqueue engine if reliable
            // same-port restart is required.
            check(assignedPort > 0) {
                "NWListener failed to start (port=$assignedPort, errno=$listenerErrno)"
            }

            // Update the local address with the assigned port
            serverChannel.updateLocalAddress(InetSocketAddress(host, assignedPort))
            logger.debug { "Bound to $host:$assignedPort" }
            return serverChannel
        } catch (t: Throwable) {
            cancelListenerQuietly(lsnr, "bindInet cleanup")
            // Release the engine-field reference so engine.close() does not
            // attempt a second cancel on this already-cancelled listener.
            if (listener == lsnr) listener = null
            throw t
        }
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
     * @return A [PipelinedStreamServer] that cancels the listener when closed.
     */
    override fun bindPipeline(
        address: SocketAddress,
        config: BindConfig,
        pipelineInitializer: (io.github.fukusaka.keel.pipeline.PipelinedChannel) -> Unit,
    ): PipelinedStreamServer = bindPipeline(listOf(BindSpec(address, config)), pipelineInitializer)

    /**
     * Multi-address pipeline bind: every entry of [binds] becomes one
     * NWListener of a single [NwPipelinedServer]. Listener startups are
     * awaited sequentially (each blocks until its ready state, as the
     * single-address path always has). All-or-nothing: a failing bind
     * cancels the listeners bound so far and rethrows.
     */
    override fun bindPipeline(
        binds: List<BindSpec>,
        pipelineInitializer: (io.github.fukusaka.keel.pipeline.PipelinedChannel) -> Unit,
    ): PipelinedStreamServer {
        check(!closed) { "Engine is closed" }
        val listeners = bindAllOrRollback(
            binds = binds,
            logger = logger,
            closeOne = { listener: NwPipelinedServer.Listener ->
                cancelListenerQuietly(listener.listener, "multi-address bind rollback")
            },
        ) { spec ->
            when (val address = spec.address) {
                is InetSocketAddress -> openPipelineInetListener(address, spec.config, pipelineInitializer)
                is UnixSocketAddress -> openPipelineUnixListener(address, spec.config, pipelineInitializer)
            }
        }
        return NwPipelinedServer(listeners)
    }

    /**
     * Builds a [BindConfig] that terminates TLS at the NWListener level.
     *
     * Backs the `connector { tls { } }` `EngineNative` strategy: the
     * returned [TlsServerConfig] has a `null` installer, which
     * [bindPipeline] detects as the request to configure TLS on the
     * listener's `nw_parameters_t` rather than per connection.
     */
    override fun nativeTlsBindConfig(
        tls: TlsConfig,
        backlog: Int,
        socketOptions: SocketOptions,
    ): BindConfig = TlsServerConfig(tls, installer = null, backlog = backlog, childSocketOptions = socketOptions)

    private fun openPipelineInetListener(
        address: InetSocketAddress,
        config: BindConfig,
        pipelineInitializer: (io.github.fukusaka.keel.pipeline.PipelinedChannel) -> Unit,
    ): NwPipelinedServer.Listener {
        val host = address.requireIpLiteral()
        val port = address.port
        val portStr = if (port == 0) "0" else port.toString()
        val listenerLevelTls = isListenerLevelTls(config)
        val params = if (listenerLevelTls) {
            createTlsParams(config as TlsServerConfig, config.childSocketOptions)
        } else {
            createTcpParams(config.childSocketOptions)
        }

        val lsnr = nw_listener_create_with_port(portStr, params)
            ?: error("nw_listener_create_with_port returned null")

        try {
            val listenerQueue = dispatch_queue_create(
                "io.github.fukusaka.keel.nwconnection.pipeline.listener",
                null,
            )

            nw_listener_set_queue(lsnr, listenerQueue)

            // The listener's resolved bind address, published to the accept
            // handler from the ready-state handler (both run on the listener
            // queue, so accepts serialize after the publication). Network
            // .framework exposes no cheap per-connection local-endpoint query
            // on this path, so the listener address is the accepted channel's
            // localAddress (exact for the specific-address binds keel
            // requires here) — the same fallback value the other engines use
            // when the socket query is unavailable.
            val boundLocal = kotlin.concurrent.AtomicReference<SocketAddress>(address)

            // Block until listener reaches ready state.
            val sem = dispatch_semaphore_create(0)
            var assignedPort = -1
            var listenerErrno = 0

            nw_listener_set_state_changed_handler(lsnr) { state, error ->
                if (state == nw_listener_state_ready) {
                    assignedPort = nw_listener_get_port(lsnr).toInt()
                    // Publish the resolved address before signalling, on the
                    // listener queue itself: accepts are serialized on the
                    // same queue, so no accept can observe the pre-ready
                    // placeholder (relevant for port-0 binds, where the
                    // requested address lacks the assigned port).
                    boundLocal.value = InetSocketAddress(host, assignedPort)
                    dispatch_semaphore_signal(sem)
                } else if (state == nw_listener_state_failed) {
                    // Surface the POSIX errno (e.g. EADDRINUSE) instead of an
                    // opaque failure — see bindInet for the rationale.
                    listenerErrno = keel_nw_error_posix_code(error)
                    dispatch_semaphore_signal(sem)
                }
            }

            nw_listener_set_new_connection_handler(lsnr) { conn ->
                if (conn != null) {
                    val connQueue = dispatch_queue_create(
                        "io.github.fukusaka.keel.nwconnection.pipeline.conn",
                        null,
                    )
                    nw_connection_set_queue(conn, connQueue)
                    // Fire-and-forget start: nw_connection_receive can be called
                    // immediately after start — NWConnection queues the receive
                    // internally until the connection reaches the ready state.
                    nw_connection_start(conn)

                    val transport = NwIoTransport(
                        conn,
                        connQueue,
                        this@NwEngine.allocator,
                        this@NwEngine.config.idleReadPolicy,
                        logger,
                        idleTimeoutMillis = effectiveIdleTimeout(config.idleTimeoutMillis),
                        flushCoalescing = this@NwEngine.config.flushCoalescing,
                    )
                    trackConnection(transport)
                    val channel = NwPipelinedChannel(transport, logger, localAddress = boundLocal.value)
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
                DISPATCH_TIME_NOW,
                BIND_TIMEOUT_NS,
            )
            val waitResult = dispatch_semaphore_wait(sem, deadline)
            check(waitResult == 0L) {
                "NWListener startup timed out after ${BIND_TIMEOUT_NS / 1_000_000_000L}s"
            }
            // errno 48 (EADDRINUSE) here is usually the Network.framework
            // reuse limitation over a TIME_WAIT port — see bindInet.
            check(assignedPort > 0) { "NWListener failed to start (port=$assignedPort, errno=$listenerErrno)" }
            val localAddr = InetSocketAddress(host, assignedPort)
            logger.debug { "Pipeline bound to $host:$assignedPort" }

            return NwPipelinedServer.Listener(lsnr, localAddr)
        } catch (t: Throwable) {
            cancelListenerQuietly(lsnr, "bindPipeline listener cleanup")
            throw t
        }
    }

    /**
     * Pipeline server wrapping one NWListener per bound address.
     *
     * [close] cancels every listener; Network.framework tears each down
     * asynchronously on its own dispatch queue, so the port release is
     * prompt but not synchronous with close() returning.
     */
    internal class NwPipelinedServer(
        private val listeners: List<Listener>,
    ) : PipelinedStreamServer {

        init {
            require(listeners.isNotEmpty()) { "listeners must not be empty" }
        }

        @kotlin.concurrent.Volatile
        private var closed = false

        override val localAddress: SocketAddress get() = listeners.first().localAddress
        override val localAddresses: List<SocketAddress> get() = listeners.map { it.localAddress }
        override val isActive: Boolean get() = !closed

        override fun close() {
            if (closed) return
            closed = true
            for (listener in listeners) {
                nw_listener_cancel(listener.listener)
            }
        }

        /** One bound listen address: its NWListener and resolved address. */
        internal class Listener(
            val listener: nw_listener_t,
            val localAddress: SocketAddress,
        )
    }

    /**
     * Creates a TCP client connection.
     *
     * Starts the NWConnection asynchronously via [keel_nw_start_conn_async]
     * and suspends until it reaches the ready state.
     */
    override suspend fun connect(address: SocketAddress): Channel = connect(address, ConnectConfig.DEFAULT)

    override suspend fun connect(address: SocketAddress, config: ConnectConfig): Channel = when (address) {
        is InetSocketAddress -> connectInet(address, config.socketOptions, config.idleTimeoutMillis)
        is UnixSocketAddress -> connectUnix(address, config.socketOptions, config.idleTimeoutMillis)
    }

    /**
     * Effective per-connection idle timeout: the per-server / per-client override
     * when present, else the engine-wide [IoEngineConfig.idleTimeoutMillis].
     */
    private fun effectiveIdleTimeout(override: Long?): Long = override ?: config.idleTimeoutMillis

    private suspend fun connectInet(
        address: InetSocketAddress,
        socketOptions: SocketOptions,
        idleTimeoutOverride: Long?,
    ): Channel {
        check(!closed) { "Engine is closed" }

        val host = address.resolveFirst(config.resolver).toCanonicalString()
        val port = address.port
        val endpoint = nw_endpoint_create_host(host, port.toString())
        val params = createTcpParams(socketOptions)
        val conn = nw_connection_create(endpoint, params)
            ?: error("nw_connection_create returned null")

        val connQueue = dispatch_queue_create(
            "io.github.fukusaka.keel.nwconnection.conn",
            null,
        )

        val rc = suspendCancellableCoroutine<Int> { cont ->
            val cbCtx = CallbackContext(cont)
            val ref = StableRef.create(cbCtx)
            keel_nw_start_conn_async(
                conn,
                connQueue,
                startCallback,
                ref.asCPointer(),
            )
            cont.invokeOnCancellation { cbCtx.markCancelled() }
        }
        check(rc == 0) { "connect to $host:$port failed (errno=$rc)" }

        val remoteAddr = InetSocketAddress(
            nw_endpoint_get_hostname(endpoint)?.toKString() ?: host,
            nw_endpoint_get_port(endpoint).toInt(),
        )

        logger.debug { "Connected to $remoteAddr" }
        val channelLogger = guardedLoggerFactory.logger("NwPipelinedChannel")
        val transport = NwIoTransport(
            conn,
            connQueue,
            allocator,
            this@NwEngine.config.idleReadPolicy,
            channelLogger,
            idleTimeoutMillis = effectiveIdleTimeout(idleTimeoutOverride),
            flushCoalescing = this@NwEngine.config.flushCoalescing,
        )
        trackConnection(transport)
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
    private suspend fun bindUnix(address: UnixSocketAddress, bindConfig: BindConfig): StreamServer {
        check(!closed) { "Engine is closed" }
        address.requireFilesystemOnly("NwEngine does not support abstract-namespace Unix sockets")
        validateUnixPath(address.path)

        val params = createUnixListenerTcpParams(address.path, bindConfig.childSocketOptions)
            ?: error("nw_endpoint_create_address(sockaddr_un) failed for UDS path ${address.path}")
        val lsnr = nw_listener_create(params)
            ?: error("nw_listener_create returned null for ${address.path}")

        try {
            listener = lsnr

            val listenerQueue = dispatch_queue_create(
                "io.github.fukusaka.keel.nwconnection.listener.unix",
                null,
            )
            val serverChannel = NwStreamServer(
                lsnr,
                address,
                allocator,
                bindConfig,
                guardedLoggerFactory,
                this@NwEngine.config.idleReadPolicy,
                this@NwEngine.config.idleTimeoutMillis,
                this@NwEngine.config.flushCoalescing,
                ::trackConnection,
            )
            nw_listener_set_queue(lsnr, listenerQueue)

            var listenerErrno = 0
            val rc = suspendCancellableCoroutine<Int> { cont ->
                val cbCtx = CallbackContext(cont)
                nw_listener_set_state_changed_handler(lsnr) { state, error ->
                    if (state == nw_listener_state_ready) {
                        cbCtx.tryResume(0)
                    } else if (state == nw_listener_state_failed) {
                        listenerErrno = keel_nw_error_posix_code(error)
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
            check(rc == 0) { "NWListener failed to start on ${address.path} (errno=$listenerErrno)" }

            logger.debug { "Bound UDS ${address.path}" }
            return serverChannel
        } catch (t: Throwable) {
            cancelListenerQuietly(lsnr, "bindUnix cleanup")
            if (listener == lsnr) listener = null
            throw t
        }
    }

    /**
     * Creates a client connection to a filesystem Unix-domain socket path.
     */
    private suspend fun connectUnix(
        address: UnixSocketAddress,
        socketOptions: SocketOptions,
        idleTimeoutOverride: Long?,
    ): Channel {
        check(!closed) { "Engine is closed" }
        address.requireFilesystemOnly("NwEngine does not support abstract-namespace Unix sockets")
        validateUnixPath(address.path)

        val endpoint = keel_nw_endpoint_create_unix(address.path)
            ?: error("nw_endpoint_create_address(sockaddr_un) failed for UDS path ${address.path}")
        val params = createTcpParams(socketOptions)
        val conn = nw_connection_create(endpoint, params)
            ?: error("nw_connection_create returned null")

        val connQueue = dispatch_queue_create(
            "io.github.fukusaka.keel.nwconnection.conn.unix",
            null,
        )

        val rc = suspendCancellableCoroutine<Int> { cont ->
            val cbCtx = CallbackContext(cont)
            val ref = StableRef.create(cbCtx)
            keel_nw_start_conn_async(conn, connQueue, startCallback, ref.asCPointer())
            cont.invokeOnCancellation { cbCtx.markCancelled() }
        }
        check(rc == 0) { "connect to UDS ${address.path} failed (errno=$rc)" }

        logger.debug { "Connected to UDS ${address.path}" }
        val channelLogger = guardedLoggerFactory.logger("NwPipelinedChannel")
        val transport = NwIoTransport(
            conn,
            connQueue,
            allocator,
            this@NwEngine.config.idleReadPolicy,
            channelLogger,
            idleTimeoutMillis = effectiveIdleTimeout(idleTimeoutOverride),
            flushCoalescing = this@NwEngine.config.flushCoalescing,
        )
        trackConnection(transport)
        return NwPipelinedChannel(transport, channelLogger, address, address)
    }

    /**
     * Pipeline-mode UDS listener. Mirrors [bindPipelineInet] but binds via
     * `nw_parameters_set_local_endpoint` with a `sockaddr_un`-backed endpoint
     * (public API — see [bindUnix]) instead of a TCP port.
     * Listener-level TLS is rejected for UDS (does not fit the UDS threat model).
     */
    private fun openPipelineUnixListener(
        address: UnixSocketAddress,
        config: BindConfig,
        pipelineInitializer: (io.github.fukusaka.keel.pipeline.PipelinedChannel) -> Unit,
    ): NwPipelinedServer.Listener {
        address.requireFilesystemOnly("NwEngine does not support abstract-namespace Unix sockets")
        validateUnixPath(address.path)
        require(!isListenerLevelTls(config)) {
            "NwEngine does not support listener-level TLS over UDS"
        }

        val params = createUnixListenerTcpParams(address.path, config.childSocketOptions)
            ?: error("nw_endpoint_create_address(sockaddr_un) failed for UDS path ${address.path}")
        val lsnr = nw_listener_create(params)
            ?: error("nw_listener_create returned null for ${address.path}")

        try {
            val listenerQueue = dispatch_queue_create(
                "io.github.fukusaka.keel.nwconnection.pipeline.listener.unix",
                null,
            )
            nw_listener_set_queue(lsnr, listenerQueue)

            val sem = dispatch_semaphore_create(0)
            var ready = false
            var listenerErrno = 0
            nw_listener_set_state_changed_handler(lsnr) { state, error ->
                if (state == nw_listener_state_ready) {
                    ready = true
                    dispatch_semaphore_signal(sem)
                } else if (state == nw_listener_state_failed) {
                    listenerErrno = keel_nw_error_posix_code(error)
                    dispatch_semaphore_signal(sem)
                }
            }
            nw_listener_set_new_connection_handler(lsnr) { conn ->
                if (conn != null) {
                    val connQueue = dispatch_queue_create(
                        "io.github.fukusaka.keel.nwconnection.pipeline.conn.unix",
                        null,
                    )
                    nw_connection_set_queue(conn, connQueue)
                    nw_connection_start(conn)

                    val transport = NwIoTransport(
                        conn,
                        connQueue,
                        this@NwEngine.allocator,
                        this@NwEngine.config.idleReadPolicy,
                        logger,
                        idleTimeoutMillis = effectiveIdleTimeout(config.idleTimeoutMillis),
                        flushCoalescing = this@NwEngine.config.flushCoalescing,
                    )
                    trackConnection(transport)
                    // The UDS listener path is the accepted socket's local
                    // address by definition.
                    val channel = NwPipelinedChannel(transport, logger, localAddress = address)
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
            check(ready) { "NWListener failed to start on ${address.path} (errno=$listenerErrno)" }
            logger.debug { "Pipeline bound UDS ${address.path}" }
            return NwPipelinedServer.Listener(lsnr, address)
        } catch (t: Throwable) {
            cancelListenerQuietly(lsnr, "bindPipeline listener cleanup")
            throw t
        }
    }

    /**
     * Cancels an NWListener during a bind-path error cleanup, swallowing
     * and logging any secondary exception from the cancel itself so that
     * the caller's `throw t` preserves the original failure. The cancel
     * is fire-and-forget — Network.framework tears the listener down on
     * its own queue asynchronously.
     */
    private fun cancelListenerQuietly(lsnr: nw_listener_t, context: String) {
        try {
            nw_listener_cancel(lsnr)
        } catch (e: Throwable) {
            logger.warn(e) { "nw_listener_cancel failed during $context" }
        }
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
            // Close the engine-owned allocator child. NWConnection has no EL
            // thread to join; instead each connection's tracking coroutine
            // (trackConnection) joins its async GCD teardown under the
            // cancelAndJoin above, so every untracked per-connection child is
            // drained before we drain this pool and before the user tears down
            // the shared arena. The user-passed parent (`config.allocator`)
            // stays borrowed.
            allocator.close()
            logger.debug { "Engine closed" }
        }
    }

    /**
     * Ties [transport]'s lifecycle to this engine's coroutine scope so [close]
     * joins the connection's async GCD teardown.
     *
     * The per-connection allocator child is an untracked child of the engine
     * allocator (createUntrackedChild), so the engine no longer fans out to
     * close it — this coroutine awaits the connection's own teardown instead.
     * If the engine is closed while the connection is still live, the coroutine
     * is cancelled; its `finally` then forces the teardown under [NonCancellable]
     * so the untracked child is drained before the shared arena is torn down. On
     * a normal connection close the teardown has already run, so the `finally`
     * is a no-op and the coroutine completes promptly (no accumulation across
     * the engine's lifetime).
     */
    private fun trackConnection(transport: NwIoTransport) {
        launch {
            try {
                transport.awaitTeardown()
            } finally {
                if (!transport.isTornDown) {
                    withContext(NonCancellable) {
                        transport.close()
                        withTimeoutOrNull(TEARDOWN_JOIN_TIMEOUT_MS) { transport.awaitTeardown() }
                    }
                }
            }
        }
    }

    /**
     * Detects if the config requests engine-native (listener-level) TLS.
     *
     * [TlsServerConfig] with `installer == null` means the engine should
     * handle TLS at the listener level. Non-null installer means per-connection
     * TLS via [initializeConnection].
     */
    private fun isListenerLevelTls(config: BindConfig): Boolean {
        return config is TlsServerConfig && config.installer == null
    }

    /**
     * Creates TLS-enabled NWConnection parameters from [TlsServerConfig],
     * honouring every server-relevant axis of [TlsConfig] — see
     * [NwTlsParams.createTlsParameters].
     */
    private fun createTlsParams(
        tlsConfig: TlsServerConfig,
        socketOptions: SocketOptions,
    ): platform.Network.nw_parameters_t {
        return NwTlsParams.createTlsParameters(tlsConfig.tls, socketOptions)
    }

    /**
     * Creates non-TLS TCP parameters, applying [socketOptions] via the
     * `_with_options` C wrapper when any supported option is set.
     * Falls back to the default wrapper (no per-creation cost) when the
     * caller passes empty options.
     */
    private fun createTcpParams(socketOptions: SocketOptions): platform.Network.nw_parameters_t {
        return if (socketOptions.isEmpty) {
            keel_nw_create_tcp_params()
                ?: error("keel_nw_create_tcp_params returned null")
        } else {
            keel_nw_create_tcp_params_with_options(
                socketOptions.toNwNoDelayFlag(),
                socketOptions.toNwKeepAliveFlag(),
            ) ?: error("keel_nw_create_tcp_params_with_options returned null")
        }
    }

    /**
     * Creates non-TLS UDS listener parameters, applying [socketOptions]
     * via the `_with_options` C wrapper when any supported option is set.
     */
    private fun createUnixListenerTcpParams(
        path: String,
        socketOptions: SocketOptions,
    ): platform.Network.nw_parameters_t {
        return if (socketOptions.isEmpty) {
            keel_nw_create_tcp_params_unix_listener(path)
        } else {
            keel_nw_create_tcp_params_unix_listener_with_options(
                path,
                socketOptions.toNwNoDelayFlag(),
                socketOptions.toNwKeepAliveFlag(),
            )
        }
    }

    companion object {
        // Same callback as NwStreamServer.startCallback. Duplicated because
        // staticCFunction must be defined in the companion of the using class
        // (cannot reference another class's companion private val).
        /** C callback for [keel_nw_start_conn_async]. */
        private val startCallback = staticCFunction {
                result: Int, ctx: kotlinx.cinterop.COpaquePointer? ->
            val ref = checkNotNull(ctx) { "ctx must be non-null in start callback" }
                .asStableRef<CallbackContext<Int>>()
            ref.get().tryResume(result)
            ref.dispose()
        }

        // Generous timeout for blocking operations at server startup.
        // Not on the hot path — only used by bindPipeline.
        private const val BIND_TIMEOUT_NS = 10L * 1_000_000_000L

        // Safety bound for joining a per-connection teardown at engine close
        // (trackConnection). Teardown is near-instant on the connQueue; this
        // only guards against a wedged dispatch queue so close() cannot hang.
        private const val TEARDOWN_JOIN_TIMEOUT_MS = 5_000L
    }
}
