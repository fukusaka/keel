package io.github.fukusaka.keel.testing.engine

import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.BindSpec
import io.github.fukusaka.keel.core.Channel
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.core.StreamEngine
import io.github.fukusaka.keel.core.bindAllOrRollback
import io.github.fukusaka.keel.logging.guarded
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.pipeline.PipelinedStreamServer
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.coroutines.CoroutineContext

/**
 * A [StreamEngine] that connects channels entirely in memory — no OS
 * socket, no file descriptor, no real network.
 *
 * [InMemoryEngine] is a test double for the keel engine layer: it
 * implements the same `bindPipeline` / `connect` contract that
 * `engine-nio`, `engine-kqueue`, etc. do, so any consumer that takes a
 * [StreamEngine] (a `keelHttpServer`, the Ktor adapter, a raw pipeline,
 * a `connect()`-based client) can be exercised in-process.
 *
 * ```
 * val engine = InMemoryEngine()
 * val server = engine.bindPipeline(InetSocketAddress("127.0.0.1", 0)) { channel ->
 *     channel.pipeline.addLast("echo", EchoHandler())
 * }
 * val client = engine.connect(server.localAddress)   // true in-memory loopback
 * ```
 *
 * **Loopback wiring**: [bindPipeline] registers a listener keyed by its
 * bind address (a synthetic ephemeral port is assigned when `port == 0`).
 * [connect] finds the listener for the requested address and builds a
 * cross-wired [InMemoryIoTransport] pair — see [InMemoryIoTransport] for
 * the byte-delivery mechanism. The client side is returned as a
 * Coroutine-mode [Channel]; the server side is a [PipelinedChannel] on
 * which the listener's `pipelineInitializer` (and
 * [BindConfig.initializeConnection]) has been run.
 *
 * **Scope of support**: only [bindPipeline] + [connect] are implemented —
 * the accept-loop [bind] throws [UnsupportedOperationException]. This is
 * the surface keel's Pipeline-mode servers (`keelHttpServer`) need.
 *
 * @param config engine-wide configuration; the allocator is shared with
 *   every in-memory transport this engine creates.
 */
public class InMemoryEngine(
    override val config: IoEngineConfig = IoEngineConfig(),
) : StreamEngine {

    override val coroutineContext: CoroutineContext = SupervisorJob()

    /**
     * The configured factory, wrapped so no log statement in this engine can
     * throw. Read once here rather than at each use; see `guarded`.
     */
    private val guardedLoggerFactory = config.loggerFactory.guarded()

    private val logger = guardedLoggerFactory.logger("InMemoryEngine")

    /** Registered listeners keyed by their resolved bind address. */
    private val listeners = mutableMapOf<SocketAddress, InMemoryPipelinedStreamServer>()

    /** Next synthetic port handed out for a `port == 0` ephemeral bind. */
    private var nextEphemeralPort = FIRST_EPHEMERAL_PORT

    private var closed = false

    /**
     * Not supported — [InMemoryEngine] only implements Pipeline-mode
     * binding ([bindPipeline]). The accept-loop [bind] has no in-memory
     * use case.
     */
    override suspend fun bind(address: SocketAddress, bindConfig: BindConfig): Nothing =
        throw UnsupportedOperationException(
            "InMemoryEngine supports only bindPipeline() — accept-loop bind() is not implemented",
        )

    /**
     * Registers an in-memory listener for [address].
     *
     * When [address] is an [InetSocketAddress] with `port == 0`, a
     * synthetic ephemeral port is assigned so the returned server's
     * [PipelinedStreamServer.localAddress] is a concrete address a
     * subsequent [connect] can target. A non-[InetSocketAddress] (e.g.
     * Unix) is registered under the address as-is.
     *
     * @throws IllegalStateException if the engine is closed, or if the
     *   resolved address already has a registered listener.
     */
    override fun bindPipeline(
        address: SocketAddress,
        config: BindConfig,
        pipelineInitializer: (PipelinedChannel) -> Unit,
    ): PipelinedStreamServer = bindOneListener(address, config, pipelineInitializer)

    /**
     * Multi-address bind: one in-memory listener per entry, composed with
     * the shared all-or-nothing rollback loop like the real engines —
     * except that the returned server exposes every bound address while
     * each entry keeps its own registry slot.
     */
    override fun bindPipeline(
        binds: List<BindSpec>,
        pipelineInitializer: (PipelinedChannel) -> Unit,
    ): PipelinedStreamServer {
        check(!closed) { "Engine is closed" }
        val bound = bindAllOrRollback(
            binds = binds,
            logger = logger,
            closeOne = { listener: InMemoryPipelinedStreamServer -> listener.close() },
        ) { spec -> bindOneListener(spec.address, spec.config, pipelineInitializer) }
        if (bound.size == 1) return bound.single()
        return object : PipelinedStreamServer {
            override val localAddress: SocketAddress get() = bound.first().localAddress
            override val localAddresses: List<SocketAddress> get() = bound.map { it.localAddress }
            override val isActive: Boolean get() = bound.all { it.isActive }
            override fun close() = bound.forEach { it.close() }
        }
    }

    private fun bindOneListener(
        address: SocketAddress,
        config: BindConfig,
        pipelineInitializer: (PipelinedChannel) -> Unit,
    ): InMemoryPipelinedStreamServer {
        check(!closed) { "Engine is closed" }
        val bound = assignEphemeralPort(address)
        check(bound !in listeners) { "address already bound: $bound" }
        val server = InMemoryPipelinedStreamServer(
            localAddress = bound,
            config = config,
            pipelineInitializer = pipelineInitializer,
            onClose = { listeners.remove(bound) },
        )
        listeners[bound] = server
        return server
    }

    /**
     * Opens an in-memory connection to a listener registered at [address].
     *
     * Builds a cross-wired [InMemoryIoTransport] pair, runs the listener's
     * connection setup on the server side, and returns the client side as
     * a Coroutine-mode [Channel].
     *
     * @throws IllegalStateException if the engine is closed, or if no
     *   listener is registered for [address] — the in-memory analogue of
     *   `ECONNREFUSED`.
     */
    override suspend fun connect(address: SocketAddress): Channel {
        check(!closed) { "Engine is closed" }
        val listener = listeners[address]
            ?: throw IllegalStateException("connection refused: no in-memory listener bound at $address")

        val (clientTransport, serverTransport) = InMemoryIoTransport.pair(config.allocator)

        // Server side: a PipelinedChannel configured exactly like a real
        // accepted connection, then armed for reading so the inbound
        // request bytes the client flushes are delivered into its pipeline.
        val serverChannel = InMemoryPipelinedChannel(
            transport = serverTransport,
            logger = logger,
            remoteAddress = address,
            localAddress = address,
        )
        listener.accept(serverChannel)
        serverTransport.readEnabled = true

        // Client side: returned to the caller as a Coroutine-mode Channel.
        // The lazy SuspendBridgeHandler is installed on the first read().
        return InMemoryPipelinedChannel(
            transport = clientTransport,
            logger = logger,
            remoteAddress = address,
            localAddress = address,
        )
    }

    /**
     * Closes the engine: closes every still-registered listener, cancels
     * the engine scope, and rejects further [bindPipeline] / [connect].
     * Idempotent.
     */
    override suspend fun close() {
        if (closed) return
        closed = true
        // close() removes each listener from the map via its onClose hook.
        listeners.values.toList().forEach { it.close() }
        coroutineContext.cancel()
    }

    /**
     * Returns [address] with a synthetic ephemeral port substituted when
     * it is an [InetSocketAddress] bound to port 0; other addresses are
     * returned unchanged.
     */
    private fun assignEphemeralPort(address: SocketAddress): SocketAddress {
        if (address !is InetSocketAddress || address.port != 0) return address
        return InetSocketAddress(address.host, nextEphemeralPort++)
    }

    private companion object {
        /**
         * First synthetic port for a `port == 0` bind. Inside the IANA
         * dynamic/ephemeral range (49152-65535) so a synthetic address
         * never collides with a well-known port a test might also use.
         */
        const val FIRST_EPHEMERAL_PORT = 49152
    }
}
