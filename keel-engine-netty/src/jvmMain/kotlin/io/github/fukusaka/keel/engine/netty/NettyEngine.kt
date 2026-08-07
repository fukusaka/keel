package io.github.fukusaka.keel.engine.netty

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.BindSpec
import io.github.fukusaka.keel.core.ConnectConfig
import io.github.fukusaka.keel.core.IdleReadPolicy
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.core.SocketOptions
import io.github.fukusaka.keel.core.StreamEngine
import io.github.fukusaka.keel.core.StreamServer
import io.github.fukusaka.keel.core.UnixSocketAddress
import io.github.fukusaka.keel.core.bindAllOrRollback
import io.github.fukusaka.keel.core.connectWithFallback
import io.github.fukusaka.keel.core.requireFilesystemOnly
import io.github.fukusaka.keel.core.requireIpLiteral
import io.github.fukusaka.keel.core.resolveFirst
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.debug
import io.github.fukusaka.keel.logging.guarded
import io.github.fukusaka.keel.logging.warn
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.pipeline.PipelinedStreamServer
import io.github.fukusaka.keel.server.ServerTlsProvider
import io.github.fukusaka.keel.server.TlsServerConfig
import io.github.fukusaka.keel.tls.TlsConfig
import io.netty.bootstrap.Bootstrap
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.EventLoop
import io.netty.channel.socket.SocketChannel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import io.github.fukusaka.keel.core.Channel as KeelChannel
import io.netty.channel.Channel as NettyNativeChannel
import java.net.InetSocketAddress as JavaInetSocketAddress

/**
 * Netty-based [StreamEngine] implementation for JVM.
 *
 * Uses Netty's [ServerBootstrap] for server-side and [Bootstrap] for
 * client-side TCP connections. Netty manages its own EventLoop threads
 * (boss group for accept, worker group for I/O).
 *
 * **Coroutine integration**: All suspend functions use
 * [suspendCancellableCoroutine] with Netty's [ChannelFuture] listeners
 * for non-blocking operation. No thread blocking occurs.
 *
 * **auto-read=false**: Each accepted/connected channel starts with
 * `autoRead` disabled. Auto-read is enabled when [AbstractPipelinedChannel.ensureBridge]
 * is called (Coroutine mode) or [NettyIoTransport.readEnabled] is set
 * (Pipeline mode), enabling push-model semantics via Netty's channelRead
 * callbacks.
 *
 * ```
 * NettyEngine (owns NioEventLoopGroups)
 *   |
 *   +-- bind() ---------> NettyStreamServer (Coroutine mode: accept → suspend I/O)
 *   |                       |
 *   |                       +-- accept() --> NettyPipelinedChannel
 *   |
 *   +-- bindPipeline() --> NettyPipelinedServer (Pipeline mode: push I/O)
 *   |
 *   +-- connect() -------> NettyPipelinedChannel
 * ```
 *
 * @param config Engine-wide configuration. [IoEngineConfig.threads] is passed
 *               directly to the selected EventLoopGroup. 0 (default) lets
 *               Netty choose automatically (`cpu * 2`).
 * @param nettyTransport Underlying Netty transport implementation. Default is
 *               [NettyTransport.Auto], which prefers the native transport
 *               for the host platform (Linux → [NettyTransport.Epoll],
 *               macOS / BSD → [NettyTransport.KQueue]) and falls back to
 *               [NettyTransport.Nio] when neither native transport is
 *               available. Override only for explicit testing /
 *               benchmarking / troubleshooting needs (e.g. forcing the
 *               NIO fallback to verify behaviour parity, or pinning to
 *               a specific native transport when Netty's classpath
 *               detection would otherwise pick the wrong one). Specifying
 *               an unavailable transport (e.g. [NettyTransport.Epoll] on
 *               macOS) fails fast at construction.
 */
class NettyEngine(
    override val config: IoEngineConfig = IoEngineConfig(),
    nettyTransport: NettyTransport = NettyTransport.Auto,
) : StreamEngine, ServerTlsProvider {

    override val coroutineContext: CoroutineContext = SupervisorJob()

    /**
     * The configured factory, wrapped so no log statement in this engine can
     * throw. Read once here rather than at each use; see `guarded`.
     */
    private val guardedLoggerFactory = config.loggerFactory.guarded()

    private val logger = guardedLoggerFactory.logger("NettyEngine")

    /**
     * Underlying Netty transport. Resolved at construction time — either
     * the user-provided value (default [NettyTransport.Auto] picks the
     * best native transport for the host platform) or the explicit
     * transport passed to [NettyEngine]'s constructor.
     *
     * Selection criteria (when [NettyTransport.Auto]):
     * - Linux: [NettyTransport.Epoll] (native, peer-FIN visible via
     *   `EPOLLRDHUP` even when `setAutoRead(false)`)
     * - macOS / BSD: [NettyTransport.KQueue] (native, peer-FIN visible
     *   via `EV_EOF`)
     * - Other JVM platforms: [NettyTransport.Nio] (Java NIO Selector
     *   fallback — peer FIN not detectable while `setAutoRead(false)`
     *   due to `sun.nio.ch.SocketChannelImpl.translateInterestOps` only
     *   mapping `OP_READ` to `POLLIN`, never `POLLRDHUP`)
     */
    private val nettyTransport: NettyTransport = nettyTransport.also { it.requireAvailable() }
    private val bossGroup = this.nettyTransport.newEventLoopGroup(1)
    private val workerGroup = this.nettyTransport.newEventLoopGroup(config.threads)
    private var closed = false

    /**
     * [IdleReadPolicy] actually applied to [NettyIoTransport] instances
     * created by this engine. Resolves to:
     * - [IoEngineConfig.idleReadPolicy] when [nettyTransport] resolves
     *   to [NettyTransport.Nio] (the only Netty transport that faces
     *   the Java NIO `Selector` API constraint and therefore needs the
     *   policy);
     * - [IdleReadPolicy.PRESERVE_BACKPRESSURE] otherwise — the native
     *   transports ([NettyTransport.Epoll] / [NettyTransport.KQueue])
     *   observe peer FIN through `EPOLLRDHUP` / `EV_EOF` regardless of
     *   `setAutoRead` state, so the policy would be a no-op; coercing
     *   to `PRESERVE_BACKPRESSURE` keeps the transport's existing
     *   behaviour (lazy auto-read enable on `readEnabled = true`)
     *   untouched.
     */
    private val effectiveIdleReadPolicy: IdleReadPolicy = run {
        val resolved = (this.nettyTransport as? NettyTransport.Auto)?.delegate ?: this.nettyTransport
        if (resolved == NettyTransport.Nio) config.idleReadPolicy else IdleReadPolicy.PRESERVE_BACKPRESSURE
    }


    /**
     * One buffer allocator per worker [EventLoop]. Each allocator is accessed
     * only by the event loop that owns it, so any pool CAS operations are
     * uncontended. Compared to sharing a single allocator across all event
     * loops, this removes the CAS hotspot produced by many workers racing on
     * a single Treiber stack. Compared to a per-channel allocator, it bounds
     * the total direct memory footprint to `numEventLoops × localPoolSize ×
     * bufferSize`, independent of the number of open connections.
     *
     * Populated lazily on the first call to [allocatorFor] because the set of
     * worker [EventLoop] instances is only known after Netty has started up.
     */
    private val eventLoopAllocators = ConcurrentHashMap<EventLoop, BufferAllocator>()

    /**
     * Effective per-connection idle (no-progress) timeout: the per-server
     * ([BindConfig.idleTimeoutMillis]) / per-client ([ConnectConfig.idleTimeoutMillis])
     * override when present, otherwise the engine-wide
     * [IoEngineConfig.idleTimeoutMillis]. `0` disables it.
     */
    private fun effectiveIdleTimeout(override: Long?): Long = override ?: config.idleTimeoutMillis

    private fun allocatorFor(ch: NettyNativeChannel): BufferAllocator =
        eventLoopAllocators.computeIfAbsent(ch.eventLoop()) {
            // Route write-path buffers through the channel's own ByteBuf
            // allocator (ch.alloc() — Netty 4.2's adaptive default; the
            // engine leaves ChannelOption.ALLOCATOR unset) so flush can hand
            // the underlying ByteBuf directly to writeAndFlush (no
            // Unpooled.wrappedBuffer alloc / duplicate()).
            // Propagate the user-passed allocator's BufferAllocatorLifecycleListener
            // into the engine-direct NettyByteBufAllocator (pluggability item 12 B2.5
            // step 2) so a single listener installed on config.allocator observes
            // every NettyByteBufIoBuf lifecycle event — both write-side from
            // allocate() and inbound zero-copy from NettyByteBufIoBuf.wrapInbound.
            NettyByteBufAllocator(ch.alloc(), lifecycleListener = config.allocator.lifecycleListener)
        }

    override suspend fun bind(address: SocketAddress, bindConfig: BindConfig): StreamServer = when (address) {
        is InetSocketAddress -> bindInet(address, bindConfig)
        is UnixSocketAddress -> bindUnix(address, bindConfig)
    }

    private suspend fun bindUnix(address: UnixSocketAddress, bindConfig: BindConfig): StreamServer {
        check(!closed) { "Engine is closed" }
        address.requireFilesystemOnly(
            "NettyEngine does not support abstract-namespace Unix sockets (JDK UnixDomainSocketAddress is filesystem-only)",
        )

        val serverChannel = NettyStreamServer.create()
        val bootstrap = ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(nettyTransport.serverDomainSocketChannelClass())
            .applyChildSocketOptions(bindConfig.childSocketOptions)
            .childHandler(object : ChannelInitializer<NettyNativeChannel>() {
                override fun initChannel(ch: NettyNativeChannel) {
                    ch.config().isAutoRead = false
                    // Deliver all inbound bytes before signalling read-closed on TCP FIN.
                    ch.config().setOption(ChannelOption.ALLOW_HALF_CLOSURE, true)
                    val remoteAddr = NettyPipelinedChannel.toSocketAddress(ch.remoteAddress())
                    val localAddr = NettyPipelinedChannel.toSocketAddress(ch.localAddress())
                    val transport = NettyIoTransport(
                        ch, allocatorFor(ch), effectiveIdleReadPolicy,
                        effectiveIdleTimeout(bindConfig.idleTimeoutMillis),
                        config.flushCoalescing,
                    )
                    val keelChannel = NettyPipelinedChannel(
                        transport, logger, remoteAddr, localAddr,
                    )
                    ch.pipeline().addLast(transport.handler)
                    bindConfig.initializeConnection(keelChannel)
                    serverChannel.onNewChannel(keelChannel)
                }
            })

        val nettyServerCh = suspendCancellableCoroutine<NettyNativeChannel> { cont ->
            bootstrap.bind(nettyTransport.newUdsAddress(address.path)).addListener { f ->
                val cf = f as ChannelFuture
                if (cf.isSuccess) {
                    cont.resume(cf.channel())
                } else {
                    cont.resumeWithException(
                        cf.cause() ?: Exception("bind failed"),
                    )
                }
            }
        }

        try {
            val localAddr = NettyPipelinedChannel.toSocketAddress(nettyServerCh.localAddress()) ?: address
            serverChannel.init(nettyServerCh, localAddr, bindConfig)
            logger.debug { "Bound to $localAddr" }
            return serverChannel
        } catch (t: Throwable) {
            closeQuietly(nettyServerCh, "bindUnix cleanup")
            throw t
        }
    }

    private suspend fun bindInet(address: InetSocketAddress, bindConfig: BindConfig): StreamServer {
        check(!closed) { "Engine is closed" }

        val host = address.resolveFirst(config.resolver).toCanonicalString()
        val port = address.port
        // Two-phase init: create NettyStreamServer before bind so the
        // ChannelInitializer closure can call onNewChannel(). The underlying
        // Netty server channel and local address are set via init() after
        // the bind future completes.
        val serverChannel = NettyStreamServer.create()

        val bootstrap = ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(nettyTransport.serverSocketChannelClass())
            .option(ChannelOption.SO_BACKLOG, bindConfig.backlog)
            .applyChildSocketOptions(bindConfig.childSocketOptions)
            .childHandler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    // Disable auto-read initially. Auto-read is enabled
                    // when ensureBridge() or readEnabled is set.
                    ch.config().isAutoRead = false
                    // Allow the input half to be shut down independently of the
                    // output half. When the client sends TCP FIN after the request
                    // body, Netty delivers all buffered body bytes via channelRead
                    // before firing ChannelInputShutdownReadComplete — preventing
                    // the bridge from being closed (via channelInactive) before
                    // the body pump has consumed the body.
                    ch.config().setOption(ChannelOption.ALLOW_HALF_CLOSURE, true)
                    val remoteAddr = NettyPipelinedChannel.toSocketAddress(ch.remoteAddress())
                    val localAddr = NettyPipelinedChannel.toSocketAddress(ch.localAddress())
                    val transport = NettyIoTransport(
                        ch, allocatorFor(ch), effectiveIdleReadPolicy,
                        effectiveIdleTimeout(bindConfig.idleTimeoutMillis),
                        config.flushCoalescing,
                    )
                    val keelChannel = NettyPipelinedChannel(
                        transport, logger, remoteAddr, localAddr,
                    )
                    ch.pipeline().addLast(transport.handler)
                    bindConfig.initializeConnection(keelChannel)
                    serverChannel.onNewChannel(keelChannel)
                }
            })

        val nettyServerCh = suspendCancellableCoroutine<NettyNativeChannel> { cont ->
            bootstrap.bind(JavaInetSocketAddress(host, port)).addListener { f ->
                val cf = f as ChannelFuture
                if (cf.isSuccess) {
                    cont.resume(cf.channel())
                } else {
                    cont.resumeWithException(
                        cf.cause() ?: Exception("bind failed"),
                    )
                }
            }
        }

        try {
            val localAddr = NettyPipelinedChannel.toSocketAddress(nettyServerCh.localAddress())
                ?: error("Failed to get local address")
            serverChannel.init(nettyServerCh, localAddr, bindConfig)
            logger.debug { "Bound to $localAddr" }
            return serverChannel
        } catch (t: Throwable) {
            closeQuietly(nettyServerCh, "bindInet cleanup")
            throw t
        }
    }

    /**
     * Connects to a remote server via Netty [Bootstrap].
     *
     * Unlike [bind], the handler is added **after** connect completes
     * because there is no ChannelInitializer race — the channel is not
     * yet receiving data until [NettyIoTransport.readEnabled] is set
     * (`autoRead = false`).
     */
    override suspend fun connect(address: SocketAddress): KeelChannel = connect(address, ConnectConfig.DEFAULT)

    override suspend fun connect(address: SocketAddress, config: ConnectConfig): KeelChannel = when (address) {
        is InetSocketAddress -> connectInet(address, config.socketOptions, config.idleTimeoutMillis)
        is UnixSocketAddress -> connectUnix(address, config.socketOptions, config.idleTimeoutMillis)
    }

    private suspend fun connectUnix(
        address: UnixSocketAddress,
        socketOptions: SocketOptions,
        idleTimeoutOverride: Long?,
    ): KeelChannel {
        check(!closed) { "Engine is closed" }
        address.requireFilesystemOnly(
            "NettyEngine does not support abstract-namespace Unix sockets (JDK UnixDomainSocketAddress is filesystem-only)",
        )

        val bootstrap = Bootstrap()
            .group(workerGroup)
            .channel(nettyTransport.domainSocketChannelClass())
            .applySocketOptions(socketOptions)
            .handler(object : ChannelInitializer<NettyNativeChannel>() {
                override fun initChannel(ch: NettyNativeChannel) {
                    ch.config().isAutoRead = false
                }
            })

        val nettyChannel = suspendCancellableCoroutine<NettyNativeChannel> { cont ->
            bootstrap.connect(nettyTransport.newUdsAddress(address.path)).addListener { f ->
                val cf = f as ChannelFuture
                if (cf.isSuccess) {
                    cont.resume(cf.channel())
                } else {
                    cont.resumeWithException(
                        cf.cause() ?: Exception("connect failed"),
                    )
                }
            }
        }

        val remoteAddr = NettyPipelinedChannel.toSocketAddress(nettyChannel.remoteAddress()) ?: address
        val localAddr = NettyPipelinedChannel.toSocketAddress(nettyChannel.localAddress())

        val transport = NettyIoTransport(
            nettyChannel, allocatorFor(nettyChannel), effectiveIdleReadPolicy,
            effectiveIdleTimeout(idleTimeoutOverride),
            config.flushCoalescing,
        )
        val keelChannel = NettyPipelinedChannel(
            transport, logger, remoteAddr, localAddr,
        )
        nettyChannel.pipeline().addLast(transport.handler)

        logger.debug { "Connected to $remoteAddr" }
        return keelChannel
    }

    private suspend fun connectInet(
        address: InetSocketAddress,
        socketOptions: SocketOptions,
        idleTimeoutOverride: Long?,
    ): KeelChannel {
        check(!closed) { "Engine is closed" }
        return address.connectWithFallback(config.resolver) { ip ->
            connectToIp(ip.toCanonicalString(), address.port, socketOptions, idleTimeoutOverride)
        }
    }

    private suspend fun connectToIp(
        host: String,
        port: Int,
        socketOptions: SocketOptions,
        idleTimeoutOverride: Long?,
    ): KeelChannel {
        val bootstrap = Bootstrap()
            .group(workerGroup)
            .channel(nettyTransport.socketChannelClass())
            .applySocketOptions(socketOptions)
            .handler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    // Disable auto-read initially
                    ch.config().isAutoRead = false
                }
            })

        val nettyChannel = suspendCancellableCoroutine<NettyNativeChannel> { cont ->
            bootstrap.connect(JavaInetSocketAddress(host, port)).addListener { f ->
                val cf = f as ChannelFuture
                if (cf.isSuccess) {
                    cont.resume(cf.channel())
                } else {
                    cont.resumeWithException(
                        cf.cause() ?: Exception("connect failed"),
                    )
                }
            }
        }

        val remoteAddr = NettyPipelinedChannel.toSocketAddress(nettyChannel.remoteAddress())
        val localAddr = NettyPipelinedChannel.toSocketAddress(nettyChannel.localAddress())

        val transport = NettyIoTransport(
            nettyChannel, allocatorFor(nettyChannel), effectiveIdleReadPolicy,
            effectiveIdleTimeout(idleTimeoutOverride),
            config.flushCoalescing,
        )
        val keelChannel = NettyPipelinedChannel(
            transport, logger, remoteAddr, localAddr,
        )
        nettyChannel.pipeline().addLast(transport.handler)

        logger.debug { "Connected to $remoteAddr" }
        return keelChannel
    }

    /**
     * Binds a server socket with Pipeline-mode connection handling.
     *
     * Each accepted connection creates a [NettyPipelinedChannel], invokes
     * the [pipelineInitializer] callback to install handlers, and immediately
     * sets [NettyIoTransport.readEnabled] to enable push-model I/O.
     *
     * Non-suspend: uses Netty's `bind().sync()` to block until the server
     * socket is ready. This is acceptable because `bindPipeline` is called
     * once at startup, not on the hot path.
     */
    override fun bindPipeline(
        address: SocketAddress,
        config: BindConfig,
        pipelineInitializer: (PipelinedChannel) -> Unit,
    ): PipelinedStreamServer = bindPipeline(listOf(BindSpec(address, config)), pipelineInitializer)

    /**
     * Multi-address pipeline bind: every entry of [binds] becomes one
     * Netty server channel of a single [NettyPipelinedServer] — one
     * `ServerBootstrap` per entry, sharing the engine's boss/worker
     * groups, with the entry's own config captured by its child
     * initializer. All-or-nothing: a failing bind closes the listeners
     * bound so far and rethrows.
     */
    override fun bindPipeline(
        binds: List<BindSpec>,
        pipelineInitializer: (PipelinedChannel) -> Unit,
    ): PipelinedStreamServer {
        check(!closed) { "Engine is closed" }
        val listeners = bindAllOrRollback(
            binds = binds,
            logger = logger,
            closeOne = { listener: NettyPipelinedServer.Listener ->
                // Rollback must leave the port free when it returns, so the
                // all-or-nothing contract holds — block on the close future.
                listener.serverChannel.close().sync()
            },
        ) { spec -> openPipelineListener(spec, pipelineInitializer) }
        return NettyPipelinedServer(listeners, logger)
    }

    /**
     * Builds a [BindConfig] that terminates TLS with Netty's native
     * `SslHandler` on every accepted connection (see [NettySslInstaller]).
     *
     * Backs the `connector { tls { } }` `EngineNative` strategy: the
     * returned [TlsServerConfig] carries a [NettySslInstaller] so
     * [bindPipeline] installs Netty's `SslHandler` per connection.
     */
    override fun nativeTlsBindConfig(
        tls: TlsConfig,
        backlog: Int,
        socketOptions: SocketOptions,
    ): BindConfig = TlsServerConfig(tls, NettySslInstaller(), backlog, socketOptions)

    /**
     * Per-connection setup shared by every pipeline listener: the child
     * initializer wires the keel transport + channel (including the
     * accepted socket's remote/local addresses — the local address is the
     * branch key a shared [pipelineInitializer] can dispatch on) and
     * applies the listener's own [config].
     */
    private fun pipelineChildInitializer(
        config: BindConfig,
        pipelineInitializer: (PipelinedChannel) -> Unit,
    ): ChannelInitializer<io.netty.channel.Channel> = object : ChannelInitializer<io.netty.channel.Channel>() {
        override fun initChannel(ch: io.netty.channel.Channel) {
            ch.config().isAutoRead = false
            // Deliver all inbound bytes before signalling read-closed on TCP FIN.
            ch.config().setOption(ChannelOption.ALLOW_HALF_CLOSURE, true)
            val remoteAddr = NettyPipelinedChannel.toSocketAddress(ch.remoteAddress())
            val localAddr = NettyPipelinedChannel.toSocketAddress(ch.localAddress())
            val transport = NettyIoTransport(
                ch, allocatorFor(ch), effectiveIdleReadPolicy,
                effectiveIdleTimeout(config.idleTimeoutMillis),
            )
            val keelChannel = NettyPipelinedChannel(
                transport, logger, remoteAddr, localAddr,
            )
            ch.pipeline().addLast(transport.handler)
            config.initializeConnection(keelChannel)
            pipelineInitializer(keelChannel)
            transport.readEnabled = true
        }
    }

    /**
     * Opens and binds one pipeline listen address: one `ServerBootstrap`
     * per entry (Netty's per-listener config unit), blocking until the
     * server channel is bound. Cleans up its own channel on failure so
     * [bindAllOrRollback] only has to roll back the listeners that were
     * fully opened before it.
     */
    private fun openPipelineListener(
        spec: BindSpec,
        pipelineInitializer: (PipelinedChannel) -> Unit,
    ): NettyPipelinedServer.Listener {
        val config = spec.config
        val nettyServerCh = when (val address = spec.address) {
            is InetSocketAddress -> ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(nettyTransport.serverSocketChannelClass())
                .option(ChannelOption.SO_BACKLOG, config.backlog)
                .applyChildSocketOptions(config.childSocketOptions)
                .childHandler(pipelineChildInitializer(config, pipelineInitializer))
                .bind(address.requireIpLiteral(), address.port).sync().channel()
            is UnixSocketAddress -> {
                address.requireFilesystemOnly(
                    "NettyEngine does not support abstract-namespace Unix sockets " +
                        "(JDK UnixDomainSocketAddress is filesystem-only)",
                )
                ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(nettyTransport.serverDomainSocketChannelClass())
                    .applyChildSocketOptions(config.childSocketOptions)
                    .childHandler(pipelineChildInitializer(config, pipelineInitializer))
                    .bind(nettyTransport.newUdsAddress(address.path)).sync().channel()
            }
        }
        try {
            val localAddr = NettyPipelinedChannel.toSocketAddress(nettyServerCh.localAddress())
                ?: spec.address
            logger.debug { "Pipeline bound to $localAddr" }
            return NettyPipelinedServer.Listener(nettyServerCh, localAddr)
        } catch (t: Throwable) {
            closeQuietly(nettyServerCh, "bindPipeline listener cleanup")
            throw t
        }
    }

    /**
     * Closes [channel] during an error cleanup path, logging any secondary
     * exception from the close itself rather than re-throwing it — the
     * original failure that triggered the cleanup is preserved by the
     * caller's `throw t`. The close is fire-and-forget (no `.sync()`) to
     * avoid blocking the caller on a Netty EventLoop round-trip in the
     * error path; Netty drains the channel asynchronously.
     */
    private fun closeQuietly(channel: NettyNativeChannel, context: String) {
        try {
            channel.close()
        } catch (e: Throwable) {
            logger.warn(e) { "Netty channel close() failed during $context" }
        }
    }

    /**
     * Closes the engine: cancels every child coroutine launched on this
     * engine's scope, joins their completion, then gracefully shuts down
     * Netty's boss and worker EventLoopGroups.
     *
     * The `job.cancelAndJoin()` step runs first so that keel children
     * suspended via keel-supplied dispatchers observe cancellation and
     * complete before Netty's internal EventLoop tasks are drained.
     * Netty's own EventLoops are never exposed as a CoroutineDispatcher
     * to keel code, so the "keel coroutines first, then Netty internals"
     * order is correct here. Idempotent.
     */
    override suspend fun close() {
        if (!closed) {
            closed = true
            coroutineContext.job.cancelAndJoin()
            // Short quiet period (0) and timeout (2s) to avoid hanging on shutdown.
            // Default shutdownGracefully() uses 2s quiet + 15s timeout which
            // causes CI timeouts when channels are not fully drained.
            workerGroup.shutdownGracefully(0, 2, java.util.concurrent.TimeUnit.SECONDS).sync()
            bossGroup.shutdownGracefully(0, 2, java.util.concurrent.TimeUnit.SECONDS).sync()
            // Close the per-EL `NettyByteBufAllocator` wrappers. The
            // underlying Netty `ByteBufAllocator` is owned by Netty and
            // released by `shutdownGracefully` above; the wrapper's
            // `close()` is a no-op today, but iterating here completes
            // the contract so a future allocator decorator with real
            // OS state (e.g. a `TrackingAllocator` debug wrapper) gets
            // its teardown call.
            for (a in eventLoopAllocators.values) a.close()
            eventLoopAllocators.clear()
            logger.debug { "Engine closed (transport=${nettyTransport.name})" }
        }
    }

    /**
     * [PipelinedStreamServer] backed by one Netty server channel per bound
     * address (one per [Listener]).
     *
     * [close] initiates every listener's close and then blocks until each
     * Netty channel is fully closed, so all listen sockets are released
     * when it returns; a close failure on one listener is logged and does
     * not stop the remaining listeners from closing.
     */
    internal class NettyPipelinedServer(
        private val listeners: List<Listener>,
        private val logger: Logger,
    ) : PipelinedStreamServer {

        init {
            require(listeners.isNotEmpty()) { "listeners must not be empty" }
        }

        @Volatile
        private var closed = false

        override val localAddress: SocketAddress get() = listeners.first().localAddress
        override val localAddresses: List<SocketAddress> get() = listeners.map { it.localAddress }
        override val isActive: Boolean get() = !closed && listeners.all { it.serverChannel.isActive }

        override fun close() {
            if (closed) return
            closed = true
            // Initiate every close first, then await each — the listeners
            // drain in parallel instead of serially.
            val futures = listeners.map { it.serverChannel.close() }
            for ((i, future) in futures.withIndex()) {
                try {
                    future.sync()
                } catch (t: Throwable) {
                    logger.warn(t) { "closing the listener for ${listeners[i].localAddress} failed" }
                }
            }
        }

        /** One bound listen address: its Netty server channel and resolved address. */
        internal class Listener(
            val serverChannel: NettyNativeChannel,
            val localAddress: SocketAddress,
        )
    }
}
