package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.core.Channel
import io.github.fukusaka.keel.core.ServerChannel
import io.github.fukusaka.keel.core.SocketAddress
import io_uring.io_uring_prep_multishot_accept
import io_uring.keel_cqe_has_more
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * io_uring-based [ServerChannel] using multishot accept (Linux 5.19+).
 *
 * A single `IORING_OP_ACCEPT` SQE with `IORING_ACCEPT_MULTISHOT` produces
 * multiple CQEs — one per accepted connection — eliminating the per-accept
 * SQE resubmission overhead of single-shot accept.
 *
 * ```
 * Multishot accept flow:
 *   armMultishotAccept() → one SQE with IORING_ACCEPT_MULTISHOT
 *   CQE arrives with res = clientFd, flags with IORING_CQE_F_MORE
 *   → onCqe callback: deliver fd to pending accept() or buffer in pendingFds
 *   CQE arrives without F_MORE → rearm (submit new multishot accept SQE)
 * ```
 *
 * **Threading**: All multishot state ([pendingFds], [pendingAcceptCont],
 * [multishotSlot]) is accessed exclusively on the [bossLoop] thread.
 * [accept] dispatches to the bossLoop when called from an external thread,
 * following the same pattern as the single-shot implementation's
 * [IoUringEventLoop.submitAndAwait] slow path.
 *
 * @param serverFd    The listening server socket fd (non-blocking).
 * @param bossLoop    The boss [IoUringEventLoop] for accept operations.
 * @param workerGroup Worker EventLoopGroup for accepted channels.
 * @param localAddress Bind address of this server channel.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IoUringServerChannel(
    private val serverFd: Int,
    private val bossLoop: IoUringEventLoop,
    private val workerGroup: IoUringEventLoopGroup,
    override val localAddress: SocketAddress,
) : ServerChannel {

    private var _active = true

    override val isActive: Boolean get() = _active

    // Multishot accept state — bossLoop thread only, no synchronisation needed.
    private val pendingFds = ArrayDeque<Int>()
    private var pendingAcceptCont: CancellableContinuation<Int>? = null
    private var multishotSlot: Int = -1

    /**
     * Suspends until an incoming connection arrives, then returns the channel.
     *
     * On the first call, arms the multishot accept SQE. Subsequent calls
     * either dequeue a buffered fd or suspend until the multishot callback
     * delivers one.
     */
    override suspend fun accept(): Channel {
        check(_active) { "ServerChannel is closed" }

        val clientFd = suspendCancellableCoroutine { cont ->
            bossLoop.dispatch(cont.context) {
                if (!cont.isActive) return@dispatch

                // Arm multishot accept lazily on first call.
                if (multishotSlot == -1) armMultishotAccept()

                if (pendingFds.isNotEmpty()) {
                    // Fast path: fd already buffered by a prior CQE callback.
                    cont.resume(pendingFds.removeFirst())
                } else {
                    // Slow path: suspend until the next CQE callback delivers an fd.
                    pendingAcceptCont = cont
                    cont.invokeOnCancellation {
                        // Dispatch cleanup to bossLoop to avoid cross-thread access.
                        bossLoop.dispatch(cont.context) {
                            if (pendingAcceptCont === cont) pendingAcceptCont = null
                        }
                    }
                }
            }
        }

        if (clientFd < 0) {
            if (!_active) throw CancellationException("ServerChannel closed")
            error("io_uring accept failed: errno=${-clientFd}")
        }

        try {
            SocketUtils.setNonBlocking(clientFd)
            val remoteAddr = SocketUtils.getRemoteAddress(clientFd)
            val localAddr = SocketUtils.getLocalAddress(clientFd)
            val wi = workerGroup.nextIndex()
            return IoUringChannel(
                clientFd, workerGroup.loopAt(wi), workerGroup.allocatorAt(wi),
                remoteAddr, localAddr,
            )
        } catch (e: Throwable) {
            platform.posix.close(clientFd)
            throw e
        }
    }

    /**
     * Arms (or rearms) the multishot accept SQE on [bossLoop].
     * Must be called on the bossLoop thread.
     */
    private fun armMultishotAccept() {
        multishotSlot = bossLoop.submitMultishot(
            prepare = { sqe ->
                io_uring_prep_multishot_accept(sqe, serverFd, null, null, 0)
            },
            onCqe = { res, flags ->
                if (!_active) return@submitMultishot
                if (res >= 0) {
                    val cont = pendingAcceptCont
                    if (cont != null) {
                        pendingAcceptCont = null
                        cont.resume(res)
                    } else {
                        pendingFds.addLast(res)
                    }
                }
                // Rearm if the kernel terminated the multishot SQE.
                if (keel_cqe_has_more(flags) == 0 && _active) {
                    armMultishotAccept()
                }
            },
        )
    }

    /**
     * Closes the server channel and cancels the multishot accept SQE.
     * Idempotent; subsequent calls are no-ops.
     */
    override fun close() {
        if (_active) {
            _active = false
            platform.posix.close(serverFd)
            if (multishotSlot != -1) {
                bossLoop.cancelMultishot(multishotSlot)
                multishotSlot = -1
            }
            pendingAcceptCont?.let { cont ->
                pendingAcceptCont = null
                cont.resumeWithException(CancellationException("ServerChannel closed"))
            }
            // Close any queued fds that haven't been accepted yet.
            while (pendingFds.isNotEmpty()) {
                platform.posix.close(pendingFds.removeFirst())
            }
        }
    }
}
