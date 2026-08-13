@file:OptIn(InternalPosixEventLoopApi::class)

package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.BindSpec
import io.github.fukusaka.keel.core.Channel
import io.github.fukusaka.keel.core.ConnectConfig
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.core.IpAddress
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.core.SocketOptions
import io.github.fukusaka.keel.core.StreamEngine
import io.github.fukusaka.keel.core.StreamServer
import io.github.fukusaka.keel.core.UnixSocketAddress
import io.github.fukusaka.keel.core.bindAllOrRollback
import io.github.fukusaka.keel.core.connectWithFallback
import io.github.fukusaka.keel.core.requireIp
import io.github.fukusaka.keel.core.resolveFirst
import io.github.fukusaka.keel.logging.debug
import io.github.fukusaka.keel.logging.guarded
import io.github.fukusaka.keel.logging.warn
import io.github.fukusaka.keel.native.posix.ConnectResult
import io.github.fukusaka.keel.native.posix.Interest
import io.github.fukusaka.keel.native.posix.InternalPosixEventLoopApi
import io.github.fukusaka.keel.native.posix.NativeSocket
import io.github.fukusaka.keel.native.posix.NativeSocketOps
import io.github.fukusaka.keel.native.posix.PosixIoTransport
import io.github.fukusaka.keel.native.posix.PosixNativeSocket
import io.github.fukusaka.keel.native.posix.PosixNativeSocketOps
import io.github.fukusaka.keel.native.posix.applySocketOptions
import io.github.fukusaka.keel.native.posix.closeFdSafely
import io.github.fukusaka.keel.native.posix.errnoMessage
import io.github.fukusaka.keel.pipeline.PipelinedStreamServer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import platform.posix.errno
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext

/**
 * Linux epoll-based [StreamEngine] implementation with multi-threaded EventLoop.
 *
 * Uses a boss/worker EventLoop model (same as NIO and Netty):
 * - **Boss EventLoop**: handles `accept()` readiness on server fds
 * - **Worker EventLoopGroup**: handles `read`/`write`/`flush` on accepted channels
 *
 * New connections are assigned to worker EventLoops in round-robin order.
 * Each worker thread runs its own epoll fd and acts as a
 * [CoroutineDispatcher][kotlinx.coroutines.CoroutineDispatcher], so all
 * I/O + request processing for a channel runs on a single thread without
 * cross-thread dispatch.
 *
 * ```
 * EpollEngine
 *   |
 *   +-- bossLoop (accept EventLoop)
 *   |     |
 *   |     +-- bind() → EpollStreamServer
 *   |           |
 *   |           +-- accept() → assign to workerGroup.next()
 *   |
 *   +-- workerGroup (N worker EventLoops, round-robin)
 *         |
 *         +-- worker[0]: Channel A, D, ...
 *         +-- worker[1]: Channel B, E, ...
 *         +-- worker[N]: ...
 * ```
 *
 * @param config Engine-wide configuration. [IoEngineConfig.threads] controls
 *               the number of worker EventLoop threads. 0 (default) resolves
 *               to `availableProcessors()`.
 * @param nativeSocket POSIX syscall seam. Defaults to [PosixNativeSocket]
 *                     (the production impl that delegates to `keel_*`
 *                     C wrappers). Tests inject a fake implementation to
 *                     drive specific errno branches without real fds.
 * @param nativeSocketOps Cold-path POSIX lifecycle seam (socket / bind /
 *                       listen / setsockopt / getsockname / getpeername /
 *                       getsockopt(SO_ERROR) + composite `bindListener`
 *                       and `bindUnixListener`). Defaults to
 *                       [PosixNativeSocketOps]. Tests inject a fake to
 *                       drive `ConnectResult.Failed` / `SO_ERROR`
 *                       non-zero / address-read branches without a real
 *                       kernel.
 */
@OptIn(ExperimentalForeignApi::class)
class EpollEngine(
    override val config: IoEngineConfig = IoEngineConfig(),
    private val nativeSocket: NativeSocket = PosixNativeSocket,
    nativeSocketOps: NativeSocketOps? = null,
    private val suspendRegisterOverride: EpollSuspendRegister? = null,
) : StreamEngine {

    override val coroutineContext: CoroutineContext = SupervisorJob()

    /**
     * The configured factory, wrapped so no log statement in this engine can
     * throw. Read once here rather than at each use; see `guarded`.
     */
    private val guardedLoggerFactory = config.loggerFactory.guarded()

    private val logger = guardedLoggerFactory.logger("EpollEngine")
    private val nativeSocketOps: NativeSocketOps = nativeSocketOps ?: PosixNativeSocketOps(logger)

    /**
     * The accept loop, and the worker loops it hands connections to.
     *
     * Assigned in the `init` below rather than here, so that a failure part way
     * through has something to unwind. As property initialisers they had none:
     * a throw from the group's constructor discarded a fully built boss loop,
     * and a throw from either `start()` left running threads behind — with the
     * engine reference never leaving the constructor, so [close] could never be
     * called on any of it.
     */
    private val bossLoop: EpollEventLoop
    private val workerGroup: EpollEventLoopGroup

    /**
     * Set once by [close]; read by every entry point's `check(!closed)`.
     *
     * Volatile so a thread that has never synchronised with [close] still sees
     * it: the plain read could go on returning `false` indefinitely, letting
     * `connect()` open sockets on an engine that is gone. This narrows that
     * window — it does not close it, and is not what makes the result safe. A
     * loop can sweep after any check here passes, which is why the transport's
     * `joinedLoop` is the guard that actually decides.
     */
    @Volatile
    private var closed = false

    /** Whether a worker loop still holds a callback for [fd] + [interest]; see the group's property. */
    internal fun hasWorkerRegistration(fd: Int, interest: Interest): Boolean =
        workerGroup.hasCallbackRegistration(fd, interest)

    /** Participants currently held by the worker loops; see the loop's probe. */
    internal fun workerParticipants(): Int = workerGroup.participants()

    // Each stage releases what it took. `pthread_create` answering `EAGAIN` is
    // the condition this is for -- a process out of threads -- and it lands in
    // the middle of building an engine, where nothing else can clean up: the
    // reference never leaves this constructor, so [close] is unreachable for
    // the rest of the process and every loop already built keeps its epoll fd,
    // wakeup eventfd, native scratch and allocator child.
    //
    // Closing an unstarted loop is what makes the last stage's rollback work,
    // and it runs the teardown its thread would have.
    init {
        bossLoop = EpollEventLoop(guardedLoggerFactory.logger("EpollEventLoop"))
        try {
            workerGroup = EpollEventLoopGroup(
                resolveThreads(config),
                guardedLoggerFactory.logger("EpollEventLoop"),
                config.allocator,
                config.readBufferSize,
                config.idleTimeoutMillis,
                config.flushCoalescing,
            )
            try {
                bossLoop.start()
                workerGroup.start()
            } catch (startFailure: Throwable) {
                // The group rolls its own loops back, and closing it again is a
                // no-op; what this adds is the case where the boss failed to
                // start and the group had not been asked yet.
                closeQuietly(startFailure) { workerGroup.close() }
                throw startFailure
            }
        } catch (constructionFailure: Throwable) {
            closeQuietly(constructionFailure) { bossLoop.close() }
            throw constructionFailure
        }
    }

    /**
     * Runs [release], attaching any failure to [cause] rather than letting it
     * replace the reason the rollback is happening.
     */
    private inline fun closeQuietly(cause: Throwable, release: () -> Unit) {
        try {
            release()
        } catch (releaseFailure: Throwable) {
            cause.addSuppressed(releaseFailure)
        }
    }

    /**
     * Binds a suspend-based server on [host]:[port].
     *
     * Creates a server socket and returns an [EpollStreamServer] whose
     * [accept][EpollStreamServer.accept] returns [EpollPipelinedChannel]
     * instances. The listener is registered with the boss EventLoop's epoll by
     * `accept()`, not here, so binding alone does not leave a watch with no
     * waiter behind it.
     *
     * @throws IllegalStateException if the engine is closed.
     */
    override suspend fun bind(address: SocketAddress, bindConfig: BindConfig): StreamServer = when (address) {
        is InetSocketAddress -> bindInet(address, bindConfig)
        is UnixSocketAddress -> bindUnix(address, bindConfig)
    }

    private suspend fun bindUnix(address: UnixSocketAddress, bindConfig: BindConfig): StreamServer {
        check(!closed) { "Engine is closed" }

        val serverFd = nativeSocketOps.bindUnixListener(address, bindConfig.backlog)

        return releaseOnFailure(serverFd, "bindUnix cleanup") {
            // The listener is left unregistered here; accept() registers it
            // through [EpollEventLoop.register] once it has a waiter to hand the
            // event to. Registering earlier would break the loop's
            // registered-implies-handler invariant, whose no-handler branch
            // removes the interest again.
            logger.debug { "Bound to $address" }
            EpollStreamServer(
                serverFd,
                bossLoop,
                workerGroup,
                address,
                bindConfig,
                logger,
                nativeSocket,
                nativeSocketOps,
            )
        }
    }

    private suspend fun bindInet(address: InetSocketAddress, bindConfig: BindConfig): StreamServer {
        check(!closed) { "Engine is closed" }

        val ip = address.resolveFirst(config.resolver)
        val port = address.port
        val serverFd = nativeSocketOps.bindListener(ip, port, bindConfig.backlog)

        return releaseOnFailure(serverFd, "bindInet cleanup") {
            // The listener is left unregistered here; accept() registers it
            // through [EpollEventLoop.register] once it has a waiter to hand the
            // event to. Registering earlier would break the loop's
            // registered-implies-handler invariant, whose no-handler branch
            // removes the interest again.
            val localAddr = nativeSocketOps.getLocalAddress(serverFd)
            logger.debug { "Bound to $localAddr" }
            EpollStreamServer(
                serverFd,
                bossLoop,
                workerGroup,
                localAddr,
                bindConfig,
                logger,
                nativeSocket,
                nativeSocketOps,
            )
        }
    }

    /**
     * Creates a TCP client connection (non-blocking).
     *
     * The socket is created in non-blocking mode so `connect()` returns
     * immediately with `EINPROGRESS`. The coroutine then suspends on
     * `EPOLLOUT` via the EventLoop until the connection is established
     * (or fails). On loopback, `connect()` may succeed immediately
     * (returns 0) without needing to suspend.
     *
     * After connection, `getsockopt(SO_ERROR)` verifies success.
     * The connected channel is assigned to the next worker EventLoop
     * in round-robin order.
     */
    override suspend fun connect(address: SocketAddress): Channel = connect(address, ConnectConfig.DEFAULT)

    override suspend fun connect(address: SocketAddress, config: ConnectConfig): Channel = when (address) {
        is InetSocketAddress ->
            connectInet(address, config.socketOptions, config.readBufferSize, config.idleTimeoutMillis)
        is UnixSocketAddress ->
            connectUnix(address, config.socketOptions, config.readBufferSize, config.idleTimeoutMillis)
    }

    private suspend fun connectUnix(
        address: UnixSocketAddress,
        socketOptions: SocketOptions,
        readBufferSizeOverride: Int?,
        idleTimeoutOverride: Long?,
    ): Channel {
        check(!closed) { "Engine is closed" }

        val fd = nativeSocketOps.openUnixClientSocket()
        val workerLoop = releaseOnFailure(fd) {
            nativeSocketOps.applySocketOptions(fd, socketOptions)
            workerGroup.next()
        }

        val connectResult = releaseOnFailure(fd) { nativeSocketOps.connectUnixNonBlocking(fd, address) }
        when (connectResult) {
            ConnectResult.Connected -> Unit
            ConnectResult.InProgress -> {
                (suspendRegisterOverride ?: workerLoop).awaitWriteReady(fd, logger)
                // Guarded from here on; see the sibling path for why the
                // await's own stretch is left out.
                val error = releaseOnFailure(fd) { nativeSocketOps.getSocketError(fd) }
                if (error != 0) {
                    closeFdSafely(fd, logger, "connect cleanup")
                    error("connect($address) failed: ${errnoMessage(error)}")
                }
            }
            is ConnectResult.Failed -> {
                closeFdSafely(fd, logger, "connect cleanup")
                error("connect($address) failed: ${errnoMessage(connectResult.errno)}")
            }
        }

        logger.debug { "Connected to $address" }
        val transport = releaseOnFailure(fd) {
            val rbs = readBufferSizeOverride ?: workerLoop.readBufferSize
            val ito = idleTimeoutOverride ?: workerLoop.idleTimeoutMillis
            PosixIoTransport(fd, workerLoop, workerLoop.allocator, nativeSocket, rbs, ito)
        }
        // Built before the check: the transport joins the loop when the channel
        // attaches, so that this connection is in the registry only once there
        // is something to deliver a stop notification to.
        val channel = releaseOnFailure(transport) {
            EpollPipelinedChannel(transport, logger, address, null)
        }
        if (!transport.joinedLoop) {
            // The loop swept between this call's check at the top and that join.
            // Closing the transport rather than the descriptor: close() is
            // idempotent and releases the fd itself, so nothing here can close a
            // number the loop might still hold or that a later close would close
            // twice. The channel is discarded unreturned.
            val stopped = IllegalStateException(
                "connect(address) failed: the EventLoop stopped during connect",
            )
            // Through the same release as the guards, because this branch is the
            // one where the loop has already swept: `close()` then runs the teardown
            // inline on this thread, and a stage that fails re-raises -- which would
            // hand the caller a buffer-release failure with nothing saying the loop
            // stopped.
            releaseTransport(transport, stopped)
            throw stopped
        }
        return channel
    }

    private suspend fun connectInet(
        address: InetSocketAddress,
        socketOptions: SocketOptions,
        readBufferSizeOverride: Int?,
        idleTimeoutOverride: Long?,
    ): Channel {
        check(!closed) { "Engine is closed" }
        return address.connectWithFallback(config.resolver) { ip ->
            connectToIp(ip, address.port, socketOptions, readBufferSizeOverride, idleTimeoutOverride)
        }
    }

    private suspend fun connectToIp(
        ip: IpAddress,
        port: Int,
        socketOptions: SocketOptions,
        readBufferSizeOverride: Int?,
        idleTimeoutOverride: Long?,
    ): Channel {
        val fd = nativeSocketOps.openClientSocket(ip)
        // Inside the guard, not above it. `NativeSocketOps` is a public
        // constructor parameter, so neither of these is keel's code to promise
        // about: the in-tree implementation swallows a failed `setsockopt` and
        // returns a result rather than throwing, but an implementation that
        // does otherwise would strand a descriptor here -- and `connectWithFallback`
        // retries the next resolved address, so one per A-record.
        val workerLoop = releaseOnFailure(fd) {
            nativeSocketOps.applySocketOptions(fd, socketOptions)
            workerGroup.next()
        }

        val connectResult = releaseOnFailure(fd) { nativeSocketOps.connectNonBlocking(fd, ip, port) }
        when (connectResult) {
            ConnectResult.Connected -> Unit
            ConnectResult.InProgress -> {
                // Connection in progress — suspend until fd is writable.
                (suspendRegisterOverride ?: workerLoop).awaitWriteReady(fd, logger)
                // Verify connection succeeded via SO_ERROR.
                // Guarded from here on. Until the await returns the
                // descriptor belongs to it -- it releases on cancellation and
                // on failure, under a claim CAS -- so closing it here as well
                // would be closing a number the kernel may already have
                // handed on. Ownership comes back with the return.
                val error = releaseOnFailure(fd) { nativeSocketOps.getSocketError(fd) }
                if (error != 0) {
                    closeFdSafely(fd, logger, "connect cleanup")
                    error("connect() failed: ${errnoMessage(error)}")
                }
            }
            is ConnectResult.Failed -> {
                closeFdSafely(fd, logger, "connect cleanup")
                error("connect() failed: ${errnoMessage(connectResult.errno)}")
            }
        }

        // `getpeername` on a peer that reset between the connect completing
        // and this call answers ENOTCONN, and both queries are `check`s over
        // the syscall. The throw used to leave the descriptor open for the
        // process's life, once per connect attempt.
        val remoteAddr = releaseOnFailure(fd) { nativeSocketOps.getRemoteAddress(fd) }
        val localAddr = releaseOnFailure(fd) { nativeSocketOps.getLocalAddress(fd) }
        logger.debug { "Connected to $remoteAddr" }
        val transport = releaseOnFailure(fd) {
            val rbs = readBufferSizeOverride ?: workerLoop.readBufferSize
            val ito = idleTimeoutOverride ?: workerLoop.idleTimeoutMillis
            PosixIoTransport(fd, workerLoop, workerLoop.allocator, nativeSocket, rbs, ito)
        }
        // Built before the check; see the sibling connect path.
        val channel = releaseOnFailure(transport) {
            EpollPipelinedChannel(transport, logger, remoteAddr, localAddr)
        }
        if (!transport.joinedLoop) {
            // The loop swept between this call's check at the top and that join.
            // Closing the transport rather than the descriptor: close() is
            // idempotent and releases the fd itself, so nothing here can close a
            // number the loop might still hold or that a later close would close
            // twice. The channel is discarded unreturned.
            val stopped = IllegalStateException(
                "connect(remoteAddr) failed: the EventLoop stopped during connect",
            )
            // Through the same release as the guards, because this branch is the
            // one where the loop has already swept: `close()` then runs the teardown
            // inline on this thread, and a stage that fails re-raises -- which would
            // hand the caller a buffer-release failure with nothing saying the loop
            // stopped.
            releaseTransport(transport, stopped)
            throw stopped
        }
        return channel
    }

    /**
     * Runs [build] and returns its value, closing [fd] and re-raising if it
     * throws.
     *
     * Every path that opens a descriptor holds one nobody else can name until
     * it is handed to something that will close it -- a channel for `connect`,
     * a server or a listener for `bind`. On the connect path that stretch
     * reaches past the transport, which is not in the loop's registry until the
     * channel attaches, so no stop notification reaches it either. What is left
     * when a step in it fails is the caller's exception rather than a
     * descriptor open for the process's life on a socket whose peer believes it
     * is connected.
     *
     * On the connect path every step that can fail goes through here or through
     * [releaseTransport], with three exceptions that release for themselves --
     * the `SO_ERROR` and connect-failure branches, and the await below -- and
     * one that cannot fail:
     * the debug line renders a [SocketAddress], which is `sealed` in this
     * library, so its `toString` is keel's own however the address was
     * obtained. A caller-supplied `NativeSocketOps` cannot put its code there.
     *
     * `crossinline` for the reason the transport's teardown stages carry it: a
     * `return` written inside a future [build] would leave the `try` without
     * entering the `catch`, and the descriptor would go unreleased -- which is
     * the whole contract.
     *
     * **Not the stretch spent waiting for write-readiness** (connect only).
     * That await owns the descriptor while it holds it and releases it on
     * cancellation and on failure, under a claim so the two endings cannot
     * both close. Closing here as well would be closing a number the kernel
     * may have handed on.
     *
     * [context] names the stage in the close's own report, and defaults to the
     * connect window because that is where most of these are.
     */
    @Suppress("TooGenericExceptionCaught")
    private inline fun <T> releaseOnFailure(
        fd: Int,
        context: String = "connect construction",
        crossinline build: () -> T,
    ): T =
        try {
            build()
        } catch (buildFailure: Throwable) {
            // Raw, because nothing owns the descriptor yet -- the same close
            // the connect-failure branches above make.
            closeFdSafely(fd, logger, context)
            throw buildFailure
        }

    /**
     * Runs [build] and returns its value, closing [transport] and re-raising
     * if it throws.
     *
     * Once the transport exists it owns the descriptor, so this is its
     * teardown rather than a raw close -- and the teardown re-raises what its
     * stages failed with, which would replace the answer the caller of
     * `connect` is waiting for. Attached to that answer instead.
     */
    @Suppress("TooGenericExceptionCaught")
    private inline fun <T> releaseOnFailure(transport: PosixIoTransport, crossinline build: () -> T): T =
        try {
            build()
        } catch (buildFailure: Throwable) {
            releaseTransport(transport, buildFailure)
            throw buildFailure
        }

    /**
     * Closes [transport] on the way out of a failed connect, without letting
     * the release speak over [cause].
     *
     * The teardown re-raises what its stages failed with, and thrown from here
     * that would replace the answer the caller of `connect` is waiting for --
     * which on the swept-loop branch would be a buffer-release failure with
     * nothing saying the loop stopped. Attached to [cause] and logged instead.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun releaseTransport(transport: PosixIoTransport, cause: Throwable) {
        try {
            transport.close()
        } catch (releaseFailure: Throwable) {
            cause.addSuppressed(releaseFailure)
            logger.warn(releaseFailure) { "releasing a failed connect threw as well: fd=${transport.fd}" }
        }
    }

    /**
     * Binds a pipeline-based server on [host]:[port].
     *
     * Creates a callback-driven server that processes connections entirely
     * through [Pipeline] handlers — no coroutine suspension on the hot path.
     *
     * @param pipelineInitializer Callback to configure the pipeline for each connection.
     * @return A [PipelinedStreamServer] for lifecycle management.
     */
    override fun bindPipeline(
        address: SocketAddress,
        config: BindConfig,
        pipelineInitializer: (io.github.fukusaka.keel.pipeline.PipelinedChannel) -> Unit,
    ): PipelinedStreamServer = bindPipeline(listOf(BindSpec(address, config)), pipelineInitializer)

    /**
     * Multi-address pipeline bind: every entry of [binds] becomes one
     * listener of a single [EpollPipelinedStreamServer], all armed on the
     * shared boss loop. All-or-nothing: a failing bind closes the
     * listeners bound so far and rethrows.
     */
    override fun bindPipeline(
        binds: List<BindSpec>,
        pipelineInitializer: (io.github.fukusaka.keel.pipeline.PipelinedChannel) -> Unit,
    ): PipelinedStreamServer {
        check(!closed) { "Engine is closed" }
        val listeners = bindAllOrRollback(
            binds = binds,
            logger = logger,
            closeOne = { listener: EpollPipelinedStreamServer.Listener ->
                closeFdSafely(listener.serverFd, logger, "multi-address bind rollback")
            },
        ) { spec -> openPipelineListener(spec) }
        // Guarded like every other stretch that holds an open descriptor
        // nobody owns. `bindAllOrRollback` has returned by here, so its rollback
        // is spent, and the server that will close these does not exist yet --
        // a throw between the two leaves every listener open with its port
        // still bound. Nothing in the constructor is known to throw, which is
        // the same footing the listener branches below stand on.
        val serverChannel = releaseListenersOnFailure(listeners) {
            EpollPipelinedStreamServer(
                listeners = listeners,
                bossLoop = bossLoop,
                workerGroup = workerGroup,
                logger = logger,
                pipelineInitializer = pipelineInitializer,
                nativeSocket = nativeSocket,
                nativeSocketOps = nativeSocketOps,
            )
        }
        try {
            serverChannel.start()
        } catch (t: Throwable) {
            serverChannel.close()
            throw t
        }
        return serverChannel
    }

    /**
     * Runs [build] over already-open [listeners] and returns its value, closing
     * every listener descriptor and re-raising if it throws.
     *
     * The one stretch in a pipeline bind where the descriptors have no owner at
     * all: `bindAllOrRollback` has returned, so its rollback is spent, and the
     * server that will close them does not exist yet.
     */
    @Suppress("TooGenericExceptionCaught")
    private inline fun <T> releaseListenersOnFailure(
        listeners: List<EpollPipelinedStreamServer.Listener>,
        crossinline build: () -> T,
    ): T =
        try {
            build()
        } catch (buildFailure: Throwable) {
            for (listener in listeners) {
                closeFdSafely(listener.serverFd, logger, "pipeline server construction")
            }
            throw buildFailure
        }

    /**
     * Opens and binds one pipeline listen socket. Cleans up its own fd on
     * failure so [bindAllOrRollback] only has to roll back the listeners
     * that were fully opened before it.
     */
    private fun openPipelineListener(spec: BindSpec): EpollPipelinedStreamServer.Listener {
        return when (val address = spec.address) {
            is InetSocketAddress -> {
                val serverFd = nativeSocketOps.bindListener(address.requireIp(), address.port, spec.config.backlog)
                releaseOnFailure(serverFd, "bindPipeline listener cleanup") {
                    val localAddr = nativeSocketOps.getLocalAddress(serverFd)
                    logger.debug { "Pipeline bound to $localAddr" }
                    EpollPipelinedStreamServer.Listener(serverFd, localAddr, spec.config)
                }
            }
            is UnixSocketAddress -> {
                val serverFd = nativeSocketOps.bindUnixListener(address, spec.config.backlog)
                // Guarded like its sibling above. Nothing here is known to
                // throw -- the holder takes three fields -- but the two
                // branches open the same kind of descriptor into the same
                // window, and only one of them was answering for it.
                releaseOnFailure(serverFd, "bindPipeline listener cleanup") {
                    logger.debug { "Pipeline bound to $address" }
                    EpollPipelinedStreamServer.Listener(serverFd, address, spec.config)
                }
            }
        }
    }

    /**
     * Closes the engine, stopping both boss and worker EventLoops.
     *
     * A stopping loop cancels its parked waiters and tells every live
     * connection it holds, which a Pipeline-mode connection surfaces as
     * read-closed / EOF; a Coroutine-mode caller still closes its own channels.
     * Idempotent.
     */
    override suspend fun close() {
        if (!closed) {
            closed = true
            coroutineContext.job.cancelAndJoin()
            // The group is closed whatever the boss did. `closed` is already
            // true, so a throw from the first would leave every worker loop
            // holding its descriptors with no second caller able to retry --
            // the same reason the group closes each of its own loops
            // independently. Barely reachable (the boss is always started, so
            // it takes the join path, and it is built with the no-op default
            // allocator) and guarded for the same reason the group's is.
            var failure: Throwable? = null
            try {
                bossLoop.close()
            } catch (bossFailure: Throwable) {
                failure = bossFailure
            }
            try {
                workerGroup.close()
            } catch (groupFailure: Throwable) {
                val first = failure
                if (first == null) failure = groupFailure else first.addSuppressed(groupFailure)
            }
            failure?.let { throw it }
            logger.debug { "Engine closed" }
        }
    }

    companion object {
        /** Resolves threads=0 to available CPU cores. */
        @OptIn(kotlin.experimental.ExperimentalNativeApi::class)
        private fun resolveThreads(config: IoEngineConfig): Int =
            if (config.threads > 0) {
                config.threads
            } else {
                kotlin.native.Platform.getAvailableProcessors()
            }
    }
}
