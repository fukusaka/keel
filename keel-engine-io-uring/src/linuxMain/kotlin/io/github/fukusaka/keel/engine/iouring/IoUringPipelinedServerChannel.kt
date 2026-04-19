package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.PipelinedServer
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.warn
import io.github.fukusaka.keel.native.posix.PosixSocketUtils
import io.github.fukusaka.keel.native.posix.closeFdSafely
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io_uring.io_uring_prep_multishot_accept
import io_uring.keel_prep_multishot_accept_direct
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import platform.posix.ENFILE
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Pipeline-based server that distributes connections across [IoUringEventLoopGroup]
 * workers using `SO_REUSEPORT`.
 *
 * Each worker EventLoop owns a private server socket (all bound to the same
 * address via `SO_REUSEPORT`). The kernel distributes incoming connections
 * across these sockets by hashing the connection 4-tuple, eliminating
 * cross-thread accept coordination.
 *
 * For each accepted connection, the server creates an [IoUringPipelinedChannel],
 * applies the user-provided [pipelineInitializer] to set up the handler chain,
 * and arms multishot recv. All subsequent I/O is zero-suspend: CQE callbacks
 * drive the pipeline directly on the EventLoop thread.
 *
 * ```
 * Worker[0]: ServerSocket[0] → multishot accept → PipelinedChannel → pipeline
 * Worker[1]: ServerSocket[1] → multishot accept → PipelinedChannel → pipeline
 * Worker[N]: ServerSocket[N] → multishot accept → PipelinedChannel → pipeline
 * ```
 *
 * @param workerGroup      The EventLoop group providing per-worker resources.
 * @param serverFds        One server socket fd per worker (SO_REUSEPORT).
 * @param pipelineInitializer Called for each accepted connection to add handlers.
 * @param capabilities     Runtime kernel feature flags.
 * @param logger           Logger for pipeline error reporting.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IoUringPipelinedServerChannel(
    private val workerGroup: IoUringEventLoopGroup,
    private val serverFds: IntArray,
    private val localAddr: SocketAddress,
    private val config: BindConfig,
    private val pipelineInitializer: (PipelinedChannel) -> Unit,
    private val capabilities: IoUringCapabilities,
    private val logger: Logger,
) : PipelinedServer {

    override val localAddress: SocketAddress get() = localAddr
    override val isActive: Boolean get() = !closed

    private var closed = false

    /**
     * Arms multishot accept on each worker EventLoop and blocks the caller
     * until every worker has enqueued its accept SQE.
     *
     * Must be called after construction. Each worker starts accepting
     * connections on its own SO_REUSEPORT server socket.
     *
     * **Synchronous barrier**: the accept-SQE submission is dispatched to
     * each worker EventLoop via [IoUringEventLoop.dispatch], but this method
     * blocks (via a per-worker [CompletableDeferred]) until every dispatched
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
        val barriers = Array(workerGroup.size) { CompletableDeferred<Unit>() }
        for (i in 0 until workerGroup.size) {
            val loop = workerGroup.loopAt(i)
            val serverFd = serverFds[i]
            val hasRegistry = workerGroup.fileRegistryAt(i) != null
            val directAllocActive = useDirectAlloc && hasRegistry
            val barrier = barriers[i]
            loop.dispatch(EmptyCoroutineContext, kotlinx.coroutines.Runnable {
                try {
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
                                onAccept(res, i, directAllocActive)
                            } else if (directAllocActive && res == -ENFILE) {
                                // Fixed file table full — kernel could not
                                // allocate a slot for the accepted fd. The
                                // multishot SQE stays armed (F_MORE); the next
                                // accept will succeed if a slot is freed by a
                                // closing connection. Persistent ENFILE means
                                // maxFiles is too small for the workload.
                                logger.warn {
                                    "direct-alloc accept: fixed file table full " +
                                        "(worker=$i, increase maxFiles if persistent)"
                                }
                            }
                        },
                    )
                    barrier.complete(Unit)
                } catch (e: Throwable) {
                    barrier.completeExceptionally(e)
                }
            })
        }
        // Block until every worker has enqueued its accept SQE. runBlocking
        // here is cheap: the EL threads are already running; each await
        // resumes as soon as the corresponding Runnable completes. Any
        // submitMultishot failure is rethrown on the calling thread so the
        // PipelinedServer is not returned in a half-armed state.
        runBlocking {
            for (b in barriers) b.await()
        }
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
     */
    private fun onAccept(acceptRes: Int, workerIndex: Int, directAlloc: Boolean) {
        val loop = workerGroup.loopAt(workerIndex)
        val bufferRing = workerGroup.bufferRingAt(workerIndex)
            ?: error("Pipeline requires provided buffer ring (kernel 5.19+)")
        val allocator = workerGroup.allocatorAt(workerIndex)
        val fileRegistry = workerGroup.fileRegistryAt(workerIndex)
        val bufferTable = workerGroup.bufferTableAt(workerIndex)
        val transport = if (directAlloc) {
            // Kernel has placed the fd into fileRegistry's table at index
            // `acceptRes`. The raw fd is not available to userspace; pass
            // -1 as the sentinel rawFd. O_NONBLOCK is not set (no
            // SOCK_NONBLOCK in accept flags), but io_uring SQE-based
            // I/O is independent of O_NONBLOCK. FALLBACK_CQE (direct
            // syscall path) is coerced to CQE in IoUringIoTransport.
            IoUringIoTransport(
                fd = -1,
                eventLoop = loop,
                capabilities = capabilities,
                allocator = allocator,
                bufferRing = bufferRing,
                fixedFileRegistry = fileRegistry,
                registeredBufferTable = bufferTable,
                preAllocatedIndex = acceptRes,
            )
        } else {
            PosixSocketUtils.setNonBlocking(acceptRes)
            IoUringIoTransport(
                fd = acceptRes,
                eventLoop = loop,
                capabilities = capabilities,
                allocator = allocator,
                bufferRing = bufferRing,
                fixedFileRegistry = fileRegistry,
                registeredBufferTable = bufferTable,
            )
        }
        val channel = IoUringPipelinedChannel(transport, logger)
        config.initializeConnection(channel)
        pipelineInitializer(channel)
        transport.readEnabled = true
    }

    /**
     * Closes all server sockets.
     *
     * Active connections continue until they close naturally.
     * Idempotent.
     */
    override fun close() {
        if (!closed) {
            closed = true
            for (fd in serverFds) {
                closeFdSafely(fd, logger, "pipelined server close")
            }
        }
    }
}
