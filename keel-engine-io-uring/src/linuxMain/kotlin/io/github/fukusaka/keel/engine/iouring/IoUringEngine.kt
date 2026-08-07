package io.github.fukusaka.keel.engine.iouring

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
import io.github.fukusaka.keel.logging.info
import io.github.fukusaka.keel.logging.warn
import io.github.fukusaka.keel.native.posix.NativeSocket
import io.github.fukusaka.keel.native.posix.NativeSocketOps
import io.github.fukusaka.keel.native.posix.PosixNativeSocket
import io.github.fukusaka.keel.native.posix.PosixNativeSocketOps
import io.github.fukusaka.keel.native.posix.applySocketOptions
import io.github.fukusaka.keel.native.posix.closeFdSafely
import io.github.fukusaka.keel.native.posix.errnoMessage
import io.github.fukusaka.keel.native.posix.fillSockaddrUn
import io.github.fukusaka.keel.pipeline.IoTransport
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.pipeline.PipelinedStreamServer
import io_uring.io_uring_prep_connect
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import platform.posix.sockaddr_in
import platform.posix.sockaddr_in6
import posix_socket.keel_fill_sockaddr_in6_addr
import posix_socket.keel_htonl
import posix_socket.keel_init_sockaddr_in
import posix_socket.keel_init_sockaddr_in6
import posix_socket.keel_sockaddr_un_sizeof
import kotlin.coroutines.CoroutineContext

/**
 * Linux io_uring-based [StreamEngine] implementation with multi-threaded EventLoop.
 *
 * Supports two server modes:
 *
 * **Coroutine mode** ([bind]/[connect]): boss EventLoop accepts via `IORING_OP_ACCEPT`
 * (multishot on Linux 5.19+) and distributes connections to workers in round-robin.
 * App drives I/O via suspend `read()`/`write()`/`flush()`.
 *
 * **Pipeline mode** ([bindPipeline]): each worker owns a private server socket
 * with `SO_REUSEPORT`. The kernel distributes connections by 4-tuple hash —
 * no boss EventLoop bottleneck. Handlers process data synchronously via callbacks.
 *
 * Each worker thread runs its own io_uring ring and acts as a
 * [CoroutineDispatcher][kotlinx.coroutines.CoroutineDispatcher], so all
 * I/O + request processing for a channel runs on a single thread.
 *
 * **Minimum kernel requirement**: Linux 5.6 (`IORING_OP_SEND` / `IORING_OP_RECV`;
 * enforced at construction — older kernels should use the epoll engine).
 * Features degrade per capability tier: 5.6–5.18 uses single-shot accept and
 * single-shot recv into allocator-owned buffers; 5.19 adds multishot accept and
 * the provided buffer ring; 6.0+ adds multishot recv and `SEND_ZC` (optimal).
 *
 * @param config Engine-wide configuration. [IoEngineConfig.threads] controls
 *               the number of worker EventLoop threads. 0 (default) resolves
 *               to `availableProcessors()`.
 * @param nativeSocket POSIX syscall seam. Defaults to [PosixNativeSocket]
 *                     (the production impl that delegates to `keel_*`
 *                     C wrappers). Tests inject a fake implementation to
 *                     drive specific errno branches without real fds.
 *                     Only used for the synchronous fallback paths
 *                     (`shutdownOutput`, `flushDirectSendSingle`); the
 *                     async SQE paths still go through io_uring.
 * @param nativeSocketOps Cold-path POSIX lifecycle seam (socket / bind /
 *                       listen / setsockopt / getsockname / getpeername
 *                       + composite `bindListener` / `bindUnixListener`).
 *                       Defaults to [PosixNativeSocketOps]. Tests inject a
 *                       fake to drive bind failure (EADDRINUSE) /
 *                       address-read branches. `connect()` paths are
 *                       NOT routed through this seam because io_uring
 *                       uses `IORING_OP_CONNECT` (native async) instead
 *                       of `connect(2)`.
 * @param registeredBufferStrategy How (and whether) per-EventLoop buffer
 *                       pools are pre-registered with the kernel for
 *                       `SEND_ZC_FIXED`. Defaults to
 *                       [RegisteredBufferStrategy.STATIC]; see the enum's
 *                       per-value documentation for trade-offs and the
 *                       kernel fallback behaviour.
 * @param registeredBufferSlotCount Per-EventLoop upper bound on the number
 *                       of buffers the STATIC warmup touches. The effective
 *                       registered count is clamped by the allocator pool's
 *                       own slot capacity. The total `RLIMIT_MEMLOCK`
 *                       footprint is `slotCount × bufferSize × workerThreads`.
 *                       Consulted by STATIC only.
 * @param registeredBufferSize Size in bytes of each buffer the STATIC
 *                       warmup allocates. Should match the allocator's
 *                       pooled read-buffer class (the default) — other
 *                       sizes miss the pool and contribute nothing to the
 *                       registered set. Consulted by STATIC only.
 * @param bufferRingSlotCount Number of recv buffers in each EventLoop's
 *                       provided buffer ring (kernel requirement: a power
 *                       of two). The ring is shared by every connection on
 *                       the loop and a slot stays out of the ring while the
 *                       delivered buffer is referenced downstream, so this
 *                       bounds how many in-flight deliveries the loop
 *                       sustains before pressure handling kicks in. Memory
 *                       footprint is `slotCount × readBufferSize × workerThreads`.
 */
@OptIn(ExperimentalForeignApi::class)
// LongParameterList: the constructor bundles the engine config with the
// io_uring capability / registered-buffer tuning knobs and the injectable
// native seams (NativeSocket / NativeSocketOps) that tests substitute; the
// defaults keep the common call site a no-arg construction.
@Suppress("LongParameterList")
class IoUringEngine(
    override val config: IoEngineConfig = IoEngineConfig(),
    private val writeModeSelector: IoModeSelector = IoModeSelectors.eagainThreshold(),
    capabilities: IoUringCapabilities? = null,
    private val nativeSocket: NativeSocket = PosixNativeSocket,
    nativeSocketOps: NativeSocketOps? = null,
    registeredBufferStrategy: RegisteredBufferStrategy = RegisteredBufferStrategy.STATIC,
    registeredBufferSlotCount: Int = IoUringEventLoopGroup.DEFAULT_REGISTERED_BUFFER_SLOT_COUNT,
    registeredBufferSize: Int = IoTransport.DEFAULT_READ_BUFFER_SIZE,
    bufferRingSlotCount: Int = ProvidedBufferRing.DEFAULT_BUFFER_COUNT,
    /**
     * SQE (submission) ring size per EventLoop (power of 2). Defaults to
     * [IoUringEventLoop.DEFAULT_RING_SIZE]. Raise it for very high fan-out
     * workloads that prepare many SQEs per loop tick.
     */
    ringSize: Int = IoUringEventLoop.DEFAULT_RING_SIZE,
    /**
     * CQE (completion) ring size per EventLoop. `0` (default) leaves the kernel
     * default of `2 * ringSize`; a positive power-of-2 `>= ringSize` sets
     * `IORING_SETUP_CQSIZE` so the CQ ring absorbs more completions before
     * overflow (useful for multishot-heavy or high-completion-rate workloads).
     */
    cqSize: Int = 0,
) : StreamEngine {

    override val coroutineContext: CoroutineContext = SupervisorJob()

    /**
     * The configured factory, wrapped so no log statement in this engine can
     * throw. Read once here rather than at each use; see `guarded`.
     */
    private val guardedLoggerFactory = config.loggerFactory.guarded()

    private val logger = guardedLoggerFactory.logger("IoUringEngine")
    private val nativeSocketOps: NativeSocketOps = nativeSocketOps ?: PosixNativeSocketOps(logger)
    private val resolvedCapabilities: IoUringCapabilities
    private val bossLoop: IoUringEventLoop

    // Internal for the in-tree occupancy measure (linuxTest), which reads
    // the per-EventLoop buffer-ring counters after a graceful close.
    internal val workerGroup: IoUringEventLoopGroup
    private var closed = false

    init {
        // Hard kernel floor: IORING_OP_SEND / IORING_OP_RECV (the engine's
        // unconditional read/write opcodes) appeared in Linux 5.6. Below
        // that, every connection would fail at its first I/O regardless of
        // capability fallbacks, so refuse to start with a clear message
        // instead of failing per-connection. Checked against the real
        // kernel even when capabilities are injected — an override cannot
        // change which opcodes the running kernel implements.
        val runningKernel = KernelVersion.current()
        check(runningKernel >= MIN_KERNEL_VERSION) {
            "io_uring engine requires Linux >= ${MIN_KERNEL_VERSION.major}.${MIN_KERNEL_VERSION.minor} " +
                "(IORING_OP_SEND / IORING_OP_RECV); detected " +
                "${runningKernel.major}.${runningKernel.minor}. Use the epoll engine on older kernels."
        }

        // Detect capabilities using kernel version. Opcode probe (SEND_ZC)
        // requires a ring, so create boss loop first with default capabilities,
        // then probe from its ring.
        val defaultCaps = capabilities ?: run {
            val kv = runningKernel
            IoUringCapabilities(
                multishotAccept = kv >= KernelVersion(5, 19),
                multishotRecv = kv >= KernelVersion(6, 0),
                providedBufferRing = kv >= KernelVersion(5, 19),
                // Preliminary: will be refined by opcode probe below.
                sendZc = kv >= KernelVersion(6, 0),
            )
        }

        bossLoop = IoUringEventLoop(guardedLoggerFactory.logger("IoUringEventLoop"), defaultCaps, ringSize, cqSize)

        // Refine sendZc via opcode probe if auto-detecting.
        val refinedCaps = if (capabilities != null) {
            defaultCaps
        } else {
            val probed = IoUringCapabilities.detect(bossLoop.ringPtr)
            defaultCaps.copy(sendZc = probed.sendZc)
        }

        // Normalize the kernel-invalid recv capability cell: multishot recv
        // requires buffer selection (IOSQE_BUFFER_SELECT), so "multishot
        // without a provided buffer ring" cannot be honoured. Version
        // detection never produces it (6.0 implies 5.19); only a manual
        // capabilities override can. Downgrade with a warn — the same
        // pattern as STATIC registered buffers on a kernel without
        // IORING_REGISTER_BUFFERS.
        resolvedCapabilities = if (refinedCaps.multishotRecv && !refinedCaps.providedBufferRing) {
            logger.warn {
                "IoUringCapabilities(multishotRecv = true, providedBufferRing = false) is not a " +
                    "valid kernel combination (multishot recv requires kernel-side buffer " +
                    "selection); treating multishotRecv as false."
            }
            refinedCaps.copy(multishotRecv = false)
        } else {
            refinedCaps
        }

        workerGroup = IoUringEventLoopGroup(
            size = resolveThreads(config),
            logger = guardedLoggerFactory.logger("IoUringEventLoop"),
            allocator = config.allocator,
            capabilities = resolvedCapabilities,
            ringSize = ringSize,
            cqSize = cqSize,
            readBufferSize = config.readBufferSize,
            idleTimeoutMillis = config.idleTimeoutMillis,
            registeredBufferStrategy = registeredBufferStrategy,
            registeredBufferSlotCount = registeredBufferSlotCount,
            registeredBufferSize = registeredBufferSize,
            bufferRingSlotCount = bufferRingSlotCount,
        )

        bossLoop.start()
        workerGroup.start()
    }

    /**
     * Binds a suspend-based server on [host]:[port].
     *
     * Creates a server socket and returns an [IoUringStreamServer] whose
     * [accept][IoUringStreamServer.accept] returns [IoUringPipelinedChannel] instances.
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
        try {
            logger.debug { "Bound to $address" }
            return IoUringStreamServer(
                serverFd,
                bossLoop,
                workerGroup,
                address,
                bindConfig,
                writeModeSelector,
                resolvedCapabilities,
                logger,
                nativeSocket,
                nativeSocketOps,
            )
        } catch (t: Throwable) {
            closeFdSafely(serverFd, logger, "bindUnix cleanup")
            throw t
        }
    }

    private suspend fun bindInet(address: InetSocketAddress, bindConfig: BindConfig): StreamServer {
        check(!closed) { "Engine is closed" }

        val ip = address.resolveFirst(config.resolver)
        val port = address.port
        val serverFd = nativeSocketOps.bindListener(ip, port, bindConfig.backlog)
        try {
            val localAddr = nativeSocketOps.getLocalAddress(serverFd)
            logger.debug { "Bound to $localAddr" }
            return IoUringStreamServer(
                serverFd,
                bossLoop,
                workerGroup,
                localAddr,
                bindConfig,
                writeModeSelector,
                resolvedCapabilities,
                logger,
                nativeSocket,
                nativeSocketOps,
            )
        } catch (t: Throwable) {
            closeFdSafely(serverFd, logger, "bindInet cleanup")
            throw t
        }
    }

    /**
     * Creates a TCP client connection via `IORING_OP_CONNECT`.
     *
     * Unlike epoll (which uses non-blocking `connect()` + EPOLLOUT), io_uring
     * handles the async connect natively. The SQE carries the full sockaddr
     * and completes when the connection is established (CQE.res=0) or fails.
     *
     * @throws IllegalStateException if the engine is closed or the address is invalid.
     */
    override suspend fun connect(address: SocketAddress): Channel = connect(address, ConnectConfig.DEFAULT)

    override suspend fun connect(address: SocketAddress, config: ConnectConfig): Channel = when (address) {
        is InetSocketAddress -> connectInet(address, config.socketOptions, config.idleTimeoutMillis)
        is UnixSocketAddress -> connectUnix(address, config.socketOptions, config.idleTimeoutMillis)
    }

    private suspend fun connectUnix(
        address: UnixSocketAddress,
        socketOptions: SocketOptions,
        idleTimeoutOverride: Long?,
    ): Channel {
        check(!closed) { "Engine is closed" }

        val fd = nativeSocketOps.openUnixClientSocket()
        nativeSocketOps.applySocketOptions(fd, socketOptions)
        val wi = workerGroup.nextIndex()
        val workerLoop = workerGroup.loopAt(wi)
        val allocator = workerGroup.allocatorAt(wi)

        val res: Int
        try {
            res = memScoped {
                // Raw byte buffer sized by the platform's sockaddr_un (110 on Linux
                // glibc). cinterop does not always surface platform.posix.sockaddr_un,
                // so we allocate by byte count via keel_sockaddr_un_sizeof and fill
                // via the C helper that already knows the struct layout.
                val addrBuf = allocArray<ByteVar>(keel_sockaddr_un_sizeof().toLong())
                val addrLen = address.fillSockaddrUn(addrBuf)
                workerLoop.submitAndAwait { sqe ->
                    io_uring_prep_connect(
                        sqe, fd,
                        addrBuf.reinterpret(),
                        addrLen.convert(),
                    )
                }
            }
        } catch (e: Throwable) {
            closeFdSafely(fd, logger, "connect cleanup")
            throw e
        }

        if (res < 0) {
            closeFdSafely(fd, logger, "connect cleanup")
            error("connect($address) failed: ${errnoMessage(-res)}")
        }

        val bufferRing = workerGroup.bufferRingAt(wi)
        val fileRegistry = workerGroup.fileRegistryAt(wi)
        val bufferTable = workerGroup.bufferTableAt(wi)
        val transport = withContext(workerLoop) {
            IoUringIoTransport(
                fd, workerLoop, resolvedCapabilities, writeModeSelector, allocator, bufferRing, fileRegistry, bufferTable,
                nativeSocket = nativeSocket,
                idleTimeoutMillis = idleTimeoutOverride ?: workerLoop.idleTimeoutMillis,
                readBufferSize = workerGroup.readBufferSize,
            )
        }
        logger.debug { "Connected to $address" }
        return IoUringPipelinedChannel(transport, logger, address, null)
    }

    private suspend fun connectInet(
        address: InetSocketAddress,
        socketOptions: SocketOptions,
        idleTimeoutOverride: Long?,
    ): Channel {
        check(!closed) { "Engine is closed" }
        return address.connectWithFallback(config.resolver) { ip ->
            connectToIp(ip, address.port, socketOptions, idleTimeoutOverride)
        }
    }

    private suspend fun connectToIp(
        ip: IpAddress,
        port: Int,
        socketOptions: SocketOptions,
        idleTimeoutOverride: Long?,
    ): Channel {
        val fd = nativeSocketOps.openClientSocket(ip)
        nativeSocketOps.applySocketOptions(fd, socketOptions)
        val wi = workerGroup.nextIndex()
        val workerLoop = workerGroup.loopAt(wi)
        val allocator = workerGroup.allocatorAt(wi)

        val res: Int
        try {
            res = memScoped {
                when (ip) {
                    is IpAddress.V4 -> {
                        val addr = alloc<sockaddr_in>()
                        keel_init_sockaddr_in(addr.ptr, port.toUShort())
                        addr.sin_addr.s_addr = keel_htonl(ip.value.toUInt())
                        workerLoop.submitAndAwait { sqe ->
                            io_uring_prep_connect(
                                sqe, fd,
                                addr.ptr.reinterpret(),
                                sizeOf<sockaddr_in>().convert(),
                            )
                        }
                    }
                    is IpAddress.V6 -> {
                        val addr = alloc<sockaddr_in6>()
                        keel_init_sockaddr_in6(addr.ptr, port.toUShort(), ip.scopeId.toUInt())
                        ip.toByteArray().toUByteArray().usePinned { pinned ->
                            keel_fill_sockaddr_in6_addr(addr.ptr, pinned.addressOf(0))
                        }
                        workerLoop.submitAndAwait { sqe ->
                            io_uring_prep_connect(
                                sqe, fd,
                                addr.ptr.reinterpret(),
                                sizeOf<sockaddr_in6>().convert(),
                            )
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            closeFdSafely(fd, logger, "connect cleanup")
            throw e
        }

        if (res < 0) {
            closeFdSafely(fd, logger, "connect cleanup")
            error("connect() failed: ${errnoMessage(-res)}")
        }

        val remoteAddr = nativeSocketOps.getRemoteAddress(fd)
        val localAddr = nativeSocketOps.getLocalAddress(fd)
        val bufferRing = workerGroup.bufferRingAt(wi)
        val fileRegistry = workerGroup.fileRegistryAt(wi)
        val bufferTable = workerGroup.bufferTableAt(wi)
        // Construct the transport on the worker EventLoop pthread so
        // `FixedFileRegistry.register(fd)` (invoked from the transport
        // constructor's property initialiser) runs on the submitter task.
        val transport = withContext(workerLoop) {
            IoUringIoTransport(
                fd, workerLoop, resolvedCapabilities, writeModeSelector, allocator, bufferRing, fileRegistry, bufferTable,
                nativeSocket = nativeSocket,
                idleTimeoutMillis = idleTimeoutOverride ?: workerLoop.idleTimeoutMillis,
                readBufferSize = workerGroup.readBufferSize,
            )
        }
        logger.debug { "Connected to $remoteAddr" }
        return IoUringPipelinedChannel(transport, logger, remoteAddr, localAddr)
    }

    /**
     * Creates a pipeline-based server with SO_REUSEPORT multi-thread accept.
     *
     * Each worker EventLoop owns a private server socket per bound inet
     * address. The kernel distributes incoming connections across workers
     * by 4-tuple hash. For each connection, [pipelineInitializer] is called
     * to set up the handler chain, then multishot recv is armed for
     * zero-suspend I/O.
     *
     * Unlike [bind] (which returns a suspend-based [StreamServer]), this
     * method creates a fully callback-driven server with no coroutine overhead.
     *
     * @param pipelineInitializer Called per accepted connection to add handlers.
     * @return A [PipelinedStreamServer] for lifecycle management.
     * @throws IllegalStateException if the engine is closed.
     */
    override fun bindPipeline(
        address: SocketAddress,
        config: BindConfig,
        pipelineInitializer: (PipelinedChannel) -> Unit,
    ): PipelinedStreamServer = bindPipeline(listOf(BindSpec(address, config)), pipelineInitializer)

    /**
     * Multi-address pipeline bind: every entry of [binds] becomes one
     * listener of a single [IoUringPipelinedStreamServer] — an inet entry
     * expands to one SO_REUSEPORT socket per worker, a Unix-domain entry
     * to a single socket on worker 0. All-or-nothing: a failing bind
     * closes the listeners bound so far and rethrows.
     */
    override fun bindPipeline(
        binds: List<BindSpec>,
        pipelineInitializer: (PipelinedChannel) -> Unit,
    ): PipelinedStreamServer {
        check(!closed) { "Engine is closed" }
        val listeners = bindAllOrRollback(
            binds = binds,
            logger = logger,
            closeOne = { listener: IoUringPipelinedStreamServer.Listener ->
                for (fd in listener.serverFds) {
                    closeFdSafely(fd, logger, "multi-address bind rollback")
                }
            },
        ) { spec -> openPipelineListener(spec) }
        val server = IoUringPipelinedStreamServer(
            workerGroup = workerGroup,
            listeners = listeners,
            pipelineInitializer = pipelineInitializer,
            capabilities = resolvedCapabilities,
            writeModeSelector = writeModeSelector,
            logger = logger,
            nativeSocket = nativeSocket,
            nativeSocketOps = nativeSocketOps,
        )
        try {
            server.start()
        } catch (t: Throwable) {
            server.close()
            throw t
        }
        logger.debug { "Pipeline server bound to ${server.localAddresses} (${workerGroup.size} workers)" }
        return server
    }

    /**
     * Opens and binds one pipeline listen address. Cleans up its own fds on
     * failure so [bindAllOrRollback] only has to roll back the listeners
     * that were fully opened before it.
     */
    private fun openPipelineListener(spec: BindSpec): IoUringPipelinedStreamServer.Listener {
        return when (val address = spec.address) {
            is InetSocketAddress -> {
                val ip = address.requireIp()
                val port = address.port
                // Track how many fds have been successfully created so that a mid-loop
                // failure (e.g. EMFILE between workers) closes only the fds actually
                // acquired, not uninitialised zero slots which would be interpreted as
                // stdin and produce a spurious `close(0)` warning.
                val serverFds = IntArray(workerGroup.size)
                var createdCount = 0
                try {
                    for (i in serverFds.indices) {
                        serverFds[i] = nativeSocketOps.bindListener(ip, port, spec.config.backlog, reusePort = true)
                        createdCount = i + 1
                    }
                    // All fds bind to the same address (SO_REUSEPORT); [0] is representative.
                    val localAddr = nativeSocketOps.getLocalAddress(serverFds[0])
                    IoUringPipelinedStreamServer.Listener(serverFds, localAddr, spec.config)
                } catch (t: Throwable) {
                    for (i in 0 until createdCount) closeFdSafely(serverFds[i], logger, "bindPipeline listener cleanup")
                    throw t
                }
            }
            is UnixSocketAddress -> {
                // SO_REUSEPORT is not supported on AF_UNIX, so a Unix-domain
                // listener uses a single server fd. Only worker 0 receives
                // accept readiness — UDS workloads are typically low-fanout
                // (IPC / sidecars) so the loss of kernel-side connection
                // hashing is acceptable.
                val serverFd = nativeSocketOps.bindUnixListener(address, spec.config.backlog)
                IoUringPipelinedStreamServer.Listener(intArrayOf(serverFd), address, spec.config)
            }
        }
    }

    /**
     * Closes the engine, stopping both boss and worker EventLoops.
     *
     * Does NOT close existing channels — caller is responsible for closing
     * active connections before shutting down the engine. Idempotent.
     */
    override suspend fun close() {
        if (!closed) {
            closed = true
            coroutineContext.job.cancelAndJoin()
            bossLoop.close()
            workerGroup.close()
            // Surface the zero-copy dispatch split for diagnostics / bench runs.
            // Reading the per-EL counters is safe here: close() above joined
            // every EventLoop pthread, so the plain Longs are quiescent.
            val fixed = workerGroup.totalSendZcFixedCount()
            val regular = workerGroup.totalSendZcRegularCount()
            if (fixed + regular > 0) {
                logger.info { "SEND_ZC dispatch: fixed=$fixed regular=$regular" }
            }
            logger.debug { "Engine closed" }
        }
    }

    /**
     * Total zero-copy send dispatches (`SEND_ZC_FIXED` + regular `SEND_ZC`)
     * across the worker EventLoops. Only meaningful after [close] — the
     * per-loop counters are EL-confined plain `Long`s and are read here
     * without synchronization, which is safe once `close()` has joined
     * every EventLoop pthread.
     *
     * Test-facing: lets an integration test assert which write mode the
     * engine actually used (e.g. that `bindPipeline` transports honour
     * the engine's `writeModeSelector`).
     */
    internal fun totalSendZcDispatchCount(): Long =
        workerGroup.totalSendZcFixedCount() + workerGroup.totalSendZcRegularCount()

    companion object {
        /**
         * Hard kernel floor of this engine: `IORING_OP_SEND` /
         * `IORING_OP_RECV` (Linux 5.6) are the unconditional read/write
         * opcodes every capability tier bottoms out on. Older kernels are
         * rejected at engine construction; the epoll engine covers them.
         */
        internal val MIN_KERNEL_VERSION = KernelVersion(5, 6)

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
