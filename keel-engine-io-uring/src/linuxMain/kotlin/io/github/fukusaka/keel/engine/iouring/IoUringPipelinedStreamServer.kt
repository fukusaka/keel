package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.debug
import io.github.fukusaka.keel.logging.warn
import io.github.fukusaka.keel.native.posix.NativeSocket
import io.github.fukusaka.keel.native.posix.NativeSocketOps
import io.github.fukusaka.keel.native.posix.PosixNativeSocket
import io.github.fukusaka.keel.native.posix.PosixNativeSocketOps
import io.github.fukusaka.keel.native.posix.applySocketOptions
import io.github.fukusaka.keel.native.posix.closeFdSafely
import io.github.fukusaka.keel.native.posix.errnoMessage
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.pipeline.PipelinedStreamServer
import io_uring.io_uring_prep_accept
import io_uring.io_uring_prep_multishot_accept
import io_uring.keel_prep_multishot_accept_direct
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import platform.posix.ECANCELED
import platform.posix.ENFILE
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Pipeline-based server that distributes connections across [IoUringEventLoopGroup]
 * workers using `SO_REUSEPORT`.
 *
 * One server owns one or more [Listener]s (one per bound address — the
 * multi-address `bindPipeline` overload; a single-address bind is the
 * one-element case). An inet listener holds one private server socket per
 * worker EventLoop (all bound to the same address via `SO_REUSEPORT`); the
 * kernel distributes incoming connections across those sockets by hashing
 * the connection 4-tuple, eliminating cross-thread accept coordination. A
 * Unix-domain listener holds a single socket serviced by worker 0
 * (`SO_REUSEPORT` is not supported on `AF_UNIX`).
 *
 * For each accepted connection, the server creates an [IoUringPipelinedChannel],
 * applies the user-provided [pipelineInitializer] to set up the handler chain,
 * and arms multishot recv. All subsequent I/O is zero-suspend: CQE callbacks
 * drive the pipeline directly on the EventLoop thread.
 *
 * ```
 * Listener[A] (address A): Worker[0..N] × ServerSocket → multishot accept ┐
 * Listener[B] (address B): Worker[0..N] × ServerSocket → multishot accept ┼→ PipelinedChannel → pipeline
 * ```
 *
 * @param workerGroup      The EventLoop group providing per-worker resources.
 * @param listeners        One entry per bound address, each holding its
 *   per-worker socket fds and per-address config.
 * @param pipelineInitializer Called for each accepted connection to add
 *   handlers, regardless of the listener it arrived on.
 * @param capabilities     Runtime kernel feature flags.
 * @param writeModeSelector Engine-wide write-mode selector, forwarded to every
 *   accepted connection's transport. This server previously constructed its
 *   transports without it, so bindPipeline connections silently wrote in the
 *   transport's default mode regardless of the engine configuration (the
 *   Coroutine-mode bind/connect paths did forward it).
 * @param logger           Logger for pipeline error reporting.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IoUringPipelinedStreamServer(
    private val workerGroup: IoUringEventLoopGroup,
    private val listeners: List<Listener>,
    private val pipelineInitializer: (PipelinedChannel) -> Unit,
    private val capabilities: IoUringCapabilities,
    private val writeModeSelector: IoModeSelector,
    private val logger: Logger,
    private val nativeSocket: NativeSocket = PosixNativeSocket,
    private val nativeSocketOps: NativeSocketOps = PosixNativeSocketOps(logger),
) : PipelinedStreamServer {

    init {
        require(listeners.isNotEmpty()) { "listeners must not be empty" }
    }

    override val localAddress: SocketAddress get() = listeners.first().localAddress
    override val localAddresses: List<SocketAddress> get() = listeners.map { it.localAddress }
    override val isActive: Boolean get() = !closed

    private var closed = false

    /**
     * Arms accept on every listener socket and blocks the caller until
     * every worker has enqueued its accept SQE.
     *
     * Must be called after construction. For an inet listener each worker
     * starts accepting connections on its own SO_REUSEPORT server socket;
     * a Unix-domain listener's single socket is armed on worker 0.
     *
     * **Synchronous barrier**: the accept-SQE submission is dispatched to
     * each worker EventLoop via [IoUringEventLoop.dispatch], but this method
     * blocks (via a per-arm [CompletableDeferred]) until every dispatched
     * Runnable has completed its [IoUringEventLoop.submitMultishot] call.
     * This closes the race where a caller of `bindPipeline(...)` could
     * immediately initiate a TCP connect before the kernel-side
     * `SO_REUSEPORT` accept queue was armed on the worker that the
     * connection hashed to, forcing the caller's data to wait on the
     * client-side `SO_RCVTIMEO` until that worker picked up the dispatched
     * Runnable. Under heavy CPU contention (e.g. GitHub Actions 4-vCPU
     * runners) that wait could exceed 5 seconds and cause spurious test
     * failures — see `IoUringPipelinedServerTest` regression history.
     *
     * The barrier only waits for the SQE to be queued in userspace, not for
     * the kernel to observe it. That is sufficient in practice because the
     * EventLoop iteration that runs the dispatched Runnable calls
     * `io_uring_submit_and_wait` immediately afterwards (on the next
     * iteration of the drain-tasks / submit-and-wait loop), and the window
     * between SQE-enqueue and submit-and-wait is bounded by the EL's
     * internal loop, not by arbitrary scheduler latency.
     */
    fun start() {
        // Direct-allocated multishot accept requires a registered file table.
        // If fixedFiles is off (user override) or the per-worker file
        // registry is absent, fall back to the traditional accept path even
        // if acceptDirectAlloc is requested.
        val useDirectAlloc = capabilities.acceptDirectAlloc && capabilities.fixedFiles
        val barriers = mutableListOf<CompletableDeferred<Unit>>()
        for (listener in listeners) {
            for (i in listener.serverFds.indices) {
                val loop = workerGroup.loopAt(i)
                val serverFd = listener.serverFds[i]
                val hasRegistry = workerGroup.fileRegistryAt(i) != null
                val directAllocActive = useDirectAlloc && hasRegistry
                val barrier = CompletableDeferred<Unit>()
                barriers.add(barrier)
                loop.dispatch(EmptyCoroutineContext, kotlinx.coroutines.Runnable {
                    try {
                        if (capabilities.multishotAccept) {
                            armMultishotAccept(loop, serverFd, i, directAllocActive, listener)
                        } else {
                            // Kernels without IORING_ACCEPT_MULTISHOT (< 5.19):
                            // a single-shot accept SQE re-armed per CQE. The
                            // Coroutine-mode server has had this fallback since
                            // its inception; the pipelined server previously
                            // armed multishot unconditionally, which an older
                            // kernel rejects with -EINVAL on every accept.
                            armSingleShotAccept(loop, serverFd, i, listener)
                        }
                        barrier.complete(Unit)
                    } catch (e: Throwable) {
                        barrier.completeExceptionally(e)
                    }
                })
            }
        }
        // Block until every worker has enqueued its accept SQEs. runBlocking
        // here is cheap: the EL threads are already running; each await
        // resumes as soon as the corresponding Runnable completes. Any
        // submitMultishot failure is rethrown on the calling thread so the
        // PipelinedStreamServer is not returned in a half-armed state.
        runBlocking {
            for (b in barriers) b.await()
        }
    }

    /**
     * Arms the multishot accept SQE on [loop] for [serverFd] (kernel 5.19+).
     * One SQE produces a CQE per incoming connection until terminated.
     */
    private fun armMultishotAccept(
        loop: IoUringEventLoop,
        serverFd: Int,
        workerIndex: Int,
        directAllocActive: Boolean,
        listener: Listener,
    ) {
        loop.submitMultishot(
            prepare = { sqe ->
                if (directAllocActive) {
                    keel_prep_multishot_accept_direct(sqe, serverFd, null, null, 0)
                } else {
                    io_uring_prep_multishot_accept(sqe, serverFd, null, null, 0)
                }
            },
            onCqe = { res, _ ->
                if (res >= 0 && !closed) {
                    logger.debug {
                        val label = if (directAllocActive) "slot" else "fd"
                        "accept CQE: worker=$workerIndex $label=$res"
                    }
                    // Exceptions from onAccept (e.g., `error(...)`
                    // if a required capability is missing, or a
                    // transport init failure) would otherwise
                    // propagate to the EL CQE drain and leave the
                    // accepted connection orphaned — the kernel
                    // holds it connected but there is no reader,
                    // so the peer sees ECONNRESET on the next
                    // read. Log the context here so the accepted
                    // fd / slot is traceable before falling
                    // through to the EL-level generic catch.
                    try {
                        onAccept(res, workerIndex, directAllocActive, listener)
                    } catch (t: Throwable) {
                        val label = if (directAllocActive) "slot" else "fd"
                        logger.warn(t) {
                            "accept handler threw; connection orphaned: " +
                                "worker=$workerIndex $label=$res"
                        }
                        throw t
                    }
                } else if (directAllocActive && res == -ENFILE) {
                    // Fixed file table full — kernel could not
                    // allocate a slot for the accepted fd. The
                    // multishot SQE stays armed (F_MORE); the next
                    // accept will succeed if a slot is freed by a
                    // closing connection. Persistent ENFILE means
                    // maxFiles is too small for the workload.
                    logger.warn {
                        "direct-alloc accept: fixed file table full " +
                            "(worker=$workerIndex, increase maxFiles if persistent)"
                    }
                }
            },
        )
    }

    /**
     * Single-shot accept fallback for kernels without multishot accept
     * (< 5.19): one `IORING_OP_ACCEPT` SQE per connection, re-armed after
     * every CQE. Uses the same callback-slot machinery as the multishot
     * path — a single-shot CQE carries no `F_MORE`, so the drain frees
     * the slot after the callback and the re-arm acquires a fresh one.
     *
     * Re-arm policy: always re-arm while the server is open, even when
     * the accept itself failed (`ECONNABORTED` and similar per-connection
     * errors are transient) or the handler threw — otherwise one bad
     * connection would silently stop the worker from ever accepting
     * again. A persistent environment error (e.g. fd exhaustion) shows
     * up as a warn-log flood rather than a silent dead server. Teardown
     * is the exception: `closed` (or `-ECANCELED` from the cancel) stops
     * the chain. Direct-allocated accept is multishot-only (5.19+ implies
     * multishot accept), so this path always deals in raw fds.
     */
    private fun armSingleShotAccept(
        loop: IoUringEventLoop,
        serverFd: Int,
        workerIndex: Int,
        listener: Listener,
    ) {
        loop.submitMultishot(
            prepare = { sqe -> io_uring_prep_accept(sqe, serverFd, null, null, 0) },
            onCqe = { res, _ ->
                if (closed || res == -ECANCELED) return@submitMultishot
                try {
                    if (res >= 0) {
                        logger.debug { "accept CQE (single-shot): worker=$workerIndex fd=$res" }
                        try {
                            onAccept(res, workerIndex, directAlloc = false, listener = listener)
                        } catch (t: Throwable) {
                            logger.warn(t) {
                                "accept handler threw; connection orphaned: " +
                                    "worker=$workerIndex fd=$res"
                            }
                            throw t
                        }
                    } else {
                        logger.warn {
                            "single-shot accept failed: worker=$workerIndex " +
                                "res=$res (${errnoMessage(-res)}); re-arming"
                        }
                    }
                } finally {
                    if (!closed) armSingleShotAccept(loop, serverFd, workerIndex, listener)
                }
            },
        )
    }

    /**
     * Called on the worker EventLoop thread when a new connection is accepted.
     *
     * Creates [IoUringPipelinedChannel], applies the pipeline initializer
     * to set up handlers, and arms multishot recv.
     *
     * @param acceptRes CQE `res` from the multishot accept SQE. In the
     *                  traditional path this is the raw client fd; in the
     *                  direct-allocated path this is the fixed-file index
     *                  chosen by the kernel.
     * @param directAlloc Whether this worker used direct-allocated accept.
     * @param listener The listener the connection arrived on — its config
     *                 (child socket options, timeouts, connection
     *                 initializer) applies to this connection.
     */
    private fun onAccept(acceptRes: Int, workerIndex: Int, directAlloc: Boolean, listener: Listener) {
        val loop = workerGroup.loopAt(workerIndex)
        // Null on kernels without IORING_REGISTER_PBUF_RING (< 5.19): the
        // transport's read dispatch falls back to plain single-shot recv
        // into allocator-owned buffers, so a missing ring is a supported
        // configuration, not an error.
        val bufferRing = workerGroup.bufferRingAt(workerIndex)
        val allocator = workerGroup.allocatorAt(workerIndex)
        val fileRegistry = workerGroup.fileRegistryAt(workerIndex)
        val bufferTable = workerGroup.bufferTableAt(workerIndex)
        val config = listener.config
        val transport: IoUringIoTransport
        val channelLocal: SocketAddress
        if (directAlloc) {
            // Kernel has placed the fd into fileRegistry's table at index
            // `acceptRes`. The raw fd is not available to userspace; pass
            // -1 as the sentinel rawFd. O_NONBLOCK is not set (no
            // SOCK_NONBLOCK in accept flags), but io_uring SQE-based
            // I/O is independent of O_NONBLOCK. FALLBACK_CQE (direct
            // syscall path) is coerced to CQE in IoUringIoTransport.
            transport = IoUringIoTransport(
                fd = -1,
                eventLoop = loop,
                capabilities = capabilities,
                writeModeSelector = writeModeSelector,
                allocator = allocator,
                bufferRing = bufferRing,
                fixedFileRegistry = fileRegistry,
                registeredBufferTable = bufferTable,
                preAllocatedIndex = acceptRes,
                nativeSocket = nativeSocket,
                idleTimeoutMillis = config.idleTimeoutMillis ?: loop.idleTimeoutMillis,
                readBufferSize = workerGroup.readBufferSize,
            )
            // No raw fd to getsockname on a direct-allocated accept, so the
            // listener's bind address is the best available local endpoint
            // (exact for a specific-address bind; the wildcard form itself
            // for a wildcard bind, unlike the raw-fd path's concrete
            // interface address).
            channelLocal = listener.localAddress
        } else {
            nativeSocketOps.setNonBlocking(acceptRes)
            nativeSocketOps.applySocketOptions(acceptRes, config.childSocketOptions)
            transport = IoUringIoTransport(
                fd = acceptRes,
                eventLoop = loop,
                capabilities = capabilities,
                writeModeSelector = writeModeSelector,
                allocator = allocator,
                bufferRing = bufferRing,
                fixedFileRegistry = fileRegistry,
                registeredBufferTable = bufferTable,
                nativeSocket = nativeSocket,
                idleTimeoutMillis = config.idleTimeoutMillis ?: loop.idleTimeoutMillis,
                readBufferSize = workerGroup.readBufferSize,
            )
            // The accepted socket's own local endpoint (concrete interface
            // address under a wildcard bind) — lets the shared pipeline
            // initializer branch on the listening address. Falls back to
            // the listener address if the query races a disconnecting peer.
            channelLocal = runCatching {
                nativeSocketOps.getLocalAddress(acceptRes)
            }.getOrNull() ?: listener.localAddress
        }
        val channel = IoUringPipelinedChannel(transport, logger, localAddress = channelLocal)
        config.initializeConnection(channel)
        pipelineInitializer(channel)
        transport.readEnabled = true
    }

    /**
     * Closes every listener's server sockets.
     *
     * Active connections continue until they close naturally.
     * Idempotent.
     */
    override fun close() {
        if (!closed) {
            closed = true
            for (listener in listeners) {
                for (fd in listener.serverFds) {
                    closeFdSafely(fd, logger, "pipelined server close")
                }
            }
        }
    }

    /**
     * One bound listen address of this server: its per-worker socket fds
     * (one per worker for inet via `SO_REUSEPORT`; a single element for
     * Unix-domain sockets, serviced by worker 0), the resolved bind
     * address, and the per-address config applied to connections accepted
     * on it.
     */
    internal class Listener(
        val serverFds: IntArray,
        val localAddress: SocketAddress,
        val config: BindConfig,
    )
}
