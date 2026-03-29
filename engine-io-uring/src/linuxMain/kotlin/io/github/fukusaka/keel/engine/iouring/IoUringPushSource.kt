package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.io.HeapNativeBuf
import io.github.fukusaka.keel.io.NativeBuf
import io.github.fukusaka.keel.io.PushSuspendSource
import io_uring.keel_cqe_get_buf_id
import io_uring.keel_cqe_has_more
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.posix.ENOBUFS
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [PushSuspendSource] backed by io_uring multishot recv with provided buffers.
 *
 * Arms a single `IORING_OP_RECV` SQE with `IORING_RECV_MULTISHOT` and
 * `IOSQE_BUFFER_SELECT`. The kernel delivers one CQE per incoming data
 * segment, selecting a buffer from the [ProvidedBufferRing]. Each CQE
 * reuses a pre-allocated [NativeBuf] wrapper bound to the kernel-selected
 * buffer slot via [NativeBuf.resetForReuse].
 *
 * **Zero-allocation CQE path**: NativeBuf wrappers and deallocator closures
 * are pre-allocated at construction (one per buffer slot). The CQE callback
 * calls [NativeBuf.resetForReuse] and sets [NativeBuf.writerIndex] to the
 * received byte count — no object creation on the hot path.
 *
 * **Ownership contract**: The returned [NativeBuf] wraps external memory from
 * the provided buffer ring. The [NativeBuf.deallocator] returns the buffer to
 * the ring when [NativeBuf.release] is called. The caller MUST call release.
 *
 * **Buffer exhaustion**: When all provided buffers are consumed, the kernel
 * returns `-ENOBUFS` and terminates the multishot SQE. After the caller
 * releases some buffers (returning them to the ring), [readOwned] rearms
 * the multishot recv. TCP socket buffers hold incoming data during the gap,
 * so no data is lost.
 *
 * **Threading**: All state is accessed on the [eventLoop] thread only.
 * [readOwned] dispatches to the EventLoop when called from an external thread.
 *
 * ```
 * Data flow:
 *   kernel recv → CQE (buf_id, bytes) → wrappers[buf_id].resetForReuse()
 *   → readOwned() returns to caller → caller reads → release()
 *   → deallocator → ProvidedBufferRing.returnBuffer(buf_id)
 * ```
 *
 * @param fd         The connected socket file descriptor.
 * @param eventLoop  The [IoUringEventLoop] owning this channel.
 * @param bufferRing The [ProvidedBufferRing] for kernel buffer selection.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IoUringPushSource(
    private val fd: Int,
    private val eventLoop: IoUringEventLoop,
    private val bufferRing: ProvidedBufferRing,
) : PushSuspendSource {

    // Pre-allocated NativeBuf wrappers for each buffer slot. Created once at
    // construction to eliminate both NativeBuf object creation and deallocator
    // lambda allocation on the CQE hot path. Each wrapper is bound to a fixed
    // buffer slot with a fixed deallocator that returns the buffer to the ring.
    // Safe to reuse: the kernel guarantees a bufId is not reissued until
    // returnBuffer() adds it back to the ring.
    private val wrappers = Array(bufferRing.bufferCount) { bufId ->
        HeapNativeBuf.wrapExternal(
            bufferRing.getPointer(bufId), bufferRing.bufferSize, 0,
        ) { _ -> bufferRing.returnBuffer(bufId) }
    }

    // Buffered NativeBufs delivered by CQE callbacks but not yet claimed by readOwned().
    private val pendingBufs = ArrayDeque<NativeBuf>()

    // Suspended continuation waiting for the next NativeBuf.
    private var pendingReadCont: CancellableContinuation<NativeBuf?>? = null

    // Multishot recv slot index. -1 = not yet armed.
    private var multishotSlot: Int = -1

    // true after EOF or close().
    private var closed = false

    // true when EOF was detected by CQE callback but no readOwned() was pending.
    // The next readOwned() returns null immediately.
    private var eofQueued = false

    // true when multishot was terminated by -ENOBUFS and needs rearming.
    private var needsRearm = false

    override suspend fun readOwned(): NativeBuf? {
        if (closed) return null

        return suspendCancellableCoroutine { cont ->
            eventLoop.dispatch(cont.context) {
                if (!cont.isActive) return@dispatch

                // Arm multishot recv lazily on first call.
                if (multishotSlot == -1 && !closed && !eofQueued) armMultishotRecv()

                // Rearm after buffer exhaustion if buffers have been returned.
                if (needsRearm && !closed && !eofQueued) {
                    needsRearm = false
                    armMultishotRecv()
                }

                when {
                    pendingBufs.isNotEmpty() -> {
                        // Fast path: NativeBuf already buffered by a prior CQE callback.
                        cont.resume(pendingBufs.removeFirst())
                    }
                    eofQueued || closed -> {
                        cont.resume(null)
                    }
                    else -> {
                        // Slow path: suspend until the next CQE callback delivers data.
                        pendingReadCont = cont
                        cont.invokeOnCancellation {
                            eventLoop.dispatch(cont.context) {
                                if (pendingReadCont === cont) pendingReadCont = null
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Arms the multishot recv SQE on [eventLoop].
     * Must be called on the EventLoop thread.
     */
    private fun armMultishotRecv() {
        multishotSlot = eventLoop.submitMultishotRecv(
            fd = fd,
            bgid = bufferRing.bgid,
            onCqe = { res, flags ->
                if (closed) {
                    // Discard data received after close.
                    if (res > 0) {
                        val bufId = keel_cqe_get_buf_id(flags).toInt()
                        bufferRing.returnBuffer(bufId)
                    }
                    return@submitMultishotRecv
                }

                when {
                    res > 0 -> {
                        // Data received. Reuse the pre-allocated wrapper for this
                        // buffer slot — zero allocation on the CQE hot path.
                        val bufId = keel_cqe_get_buf_id(flags).toInt()
                        val buf = wrappers[bufId]
                        buf.resetForReuse()
                        buf.writerIndex = res

                        val cont = pendingReadCont
                        if (cont != null) {
                            pendingReadCont = null
                            cont.resume(buf)
                        } else {
                            pendingBufs.addLast(buf)
                        }
                    }
                    res == 0 -> {
                        // EOF: peer closed the connection.
                        handleEof()
                    }
                    res == -ENOBUFS -> {
                        // All provided buffers consumed. Mark for rearming after
                        // buffers are returned. TCP socket buffers hold data.
                        needsRearm = true
                    }
                    else -> {
                        // Error (e.g. -ECONNRESET). Treat as EOF.
                        handleEof()
                    }
                }

                // If the kernel terminated multishot (F_MORE=0), mark for rearming.
                if (keel_cqe_has_more(flags) == 0 && !closed) {
                    multishotSlot = -1
                    if (res > 0) {
                        // Multishot ended but not EOF/error — will rearm on next readOwned().
                        needsRearm = true
                    }
                }
            },
        )
    }

    /**
     * Handles EOF or error: signals pending readOwned() or queues EOF marker.
     */
    private fun handleEof() {
        closed = true
        val cont = pendingReadCont
        if (cont != null) {
            pendingReadCont = null
            cont.resume(null)
        } else {
            eofQueued = true
        }
    }

    override fun close() {
        if (!closed) {
            closed = true
            if (multishotSlot != -1) {
                eventLoop.cancelMultishot(multishotSlot)
                multishotSlot = -1
            }
            pendingReadCont?.let { cont ->
                pendingReadCont = null
                cont.resumeWithException(CancellationException("PushSource closed"))
            }
            // Release any buffered NativeBufs (triggers deallocator → returnBuffer).
            while (pendingBufs.isNotEmpty()) {
                pendingBufs.removeFirst().release()
            }
        }
    }
}
