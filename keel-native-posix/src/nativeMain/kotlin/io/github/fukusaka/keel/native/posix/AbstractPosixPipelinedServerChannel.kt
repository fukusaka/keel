package io.github.fukusaka.keel.native.posix

import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.PipelinedServer
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.error
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.concurrent.Volatile

/**
 * Common skeleton for POSIX callback-based pipelined server channels
 * (epoll / kqueue).
 *
 * Sibling of [AbstractPosixServer] — where that base handles the
 * coroutine-suspend `accept()` path, this base handles the push-style
 * `onAcceptable` callback driven by the boss event loop's readiness
 * delivery. Subclasses provide only the engine-specific arm hook and
 * the worker-side dispatch; everything else — the edge-triggered
 * accept loop, the `setNonBlocking` + `applySocketOptions` chain, the
 * `@Volatile closed` flag, and the fd-close teardown — lives here.
 *
 * ## Why this class exists
 *
 * `EpollPipelinedServerChannel` and `KqueuePipelinedServerChannel` had
 * near-identical `onAcceptable` implementations (only the `Interest`
 * enum and the engine's transport/channel constructors differed). With
 * the [NativeSocket] + [NativeSocketOps] seams in place, the engine-
 * specific surface narrows to two abstract hooks.
 *
 * ## Hot path
 *
 * `onAcceptable` is not a hot path — once per connection, not once per
 * packet. The single virtual dispatch per accepted connection
 * ([dispatchToWorker]) is negligible next to the syscall it guards.
 * io_uring is intentionally NOT a subclass: its multishot accept uses
 * a completion-queue model + per-worker `SO_REUSEPORT` distribution
 * that does not fit this edge-triggered skeleton.
 *
 * ## Close contract
 *
 * Compared to [AbstractPosixServer], close is simpler: no suspended
 * continuation to cancel. Setting [closed] marks all pending callbacks
 * as no-ops via the `if (closed) return` guard at the top of
 * [onAcceptable]; [close] then closes the fd. Idempotent.
 */
@OptIn(ExperimentalForeignApi::class)
public abstract class AbstractPosixPipelinedServerChannel(
    protected val serverFd: Int,
    private val localAddr: SocketAddress,
    protected val config: BindConfig,
    protected val logger: Logger,
    protected val pipelineInitializer: (io.github.fukusaka.keel.pipeline.PipelinedChannel) -> Unit,
    protected val nativeSocket: NativeSocket = PosixNativeSocket,
    protected val nativeSocketOps: NativeSocketOps = PosixNativeSocketOps,
) : PipelinedServer {

    final override val localAddress: SocketAddress get() = localAddr

    @Volatile
    protected var closed: Boolean = false
        private set

    final override val isActive: Boolean get() = !closed

    /**
     * Cached callback handed to [registerReadyCallback] by [armAccept].
     * Stored once to avoid per-arm allocation (accept is one-per-connection,
     * not hot, but minimising rare allocations is cheap here).
     */
    private val readyCallback: () -> Unit = {
        @OptIn(InternalTestApi::class)
        onAcceptable()
    }

    /**
     * Starts accepting connections by delegating to [armAccept].
     * Must be called after the boss event loop is started.
     */
    public fun start() {
        armAccept()
    }

    private fun armAccept() {
        if (closed) return
        registerReadyCallback(readyCallback)
    }

    /**
     * Edge-triggered accept loop with [NativeSocket] seam.
     *
     * Exposed with [InternalTestApi] opt-in (not `internal`, which is
     * module-scoped and invisible to engine-module tests) so accept-
     * branch seam tests in each engine module can drive the loop
     * directly without going through real readiness delivery. The
     * production call site is [readyCallback], handed to the boss
     * loop by [armAccept].
     */
    @InternalTestApi
    public fun onAcceptable() {
        if (closed) return
        // Edge-triggered: drain all pending accepts in a loop.
        while (true) {
            when (val result = nativeSocket.accept(serverFd)) {
                is AcceptResult.Accepted -> {
                    nativeSocketOps.setNonBlocking(result.fd)
                    nativeSocketOps.applySocketOptions(result.fd, config.childSocketOptions)
                    dispatchToWorker(result.fd)
                }
                AcceptResult.WouldBlock -> {
                    armAccept()
                    return
                }
                is AcceptResult.Failed -> {
                    logger.error { "accept() failed: ${errnoMessage(result.errno)}" }
                    armAccept()
                    return
                }
            }
        }
    }

    /**
     * Registers [callback] on [serverFd]'s READ readiness with the boss
     * event loop. Called on initial [start], after
     * [AcceptResult.WouldBlock], and after [AcceptResult.Failed] (log
     * + re-arm semantics). Subclasses implement via their engine-
     * specific `bossLoop.registerCallback(serverFd, READ, callback)`.
     *
     * The supplied [callback] is the single cached [readyCallback]
     * instance — subclasses can store it without reallocating per arm.
     */
    protected abstract fun registerReadyCallback(callback: () -> Unit)

    /**
     * Dispatches the accepted client fd to a worker. Subclasses pick a
     * worker via round-robin on their engine-specific `workerGroup`,
     * wrap [clientFd] in an engine `IoTransport`, build an engine
     * `PipelinedChannel`, invoke [config]`.initializeConnection` and
     * [pipelineInitializer], then set `transport.readEnabled = true`.
     *
     * The base class handles [nativeSocketOps.setNonBlocking] +
     * [applySocketOptions] before this is called.
     */
    protected abstract fun dispatchToWorker(clientFd: Int)

    /**
     * Stops accepting and closes the server socket fd.
     *
     * Idempotent: subsequent calls are no-ops. Pending [onAcceptable]
     * callbacks become no-ops via the `if (closed) return` guard.
     * Does NOT close worker event loops or existing client channels —
     * the engine (typically via `Engine.close`) handles those.
     */
    final override fun close() {
        if (closed) return
        closed = true
        closeFdSafely(serverFd, logger, "pipelined server close")
    }
}
