@file:OptIn(UnsafeIoBufApi::class)

package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.UnsafeIoBufApi
import io.github.fukusaka.keel.buf.unsafePointer
import io.github.fukusaka.keel.logging.debug
import io.github.fukusaka.keel.logging.error
import io.github.fukusaka.keel.logging.warn
import io.github.fukusaka.keel.native.posix.NativeSocket
import io.github.fukusaka.keel.native.posix.PosixNativeSocket
import io.github.fukusaka.keel.native.posix.ShutdownResult
import io.github.fukusaka.keel.native.posix.WriteResult
import io.github.fukusaka.keel.native.posix.closeFdSafely
import io.github.fukusaka.keel.native.posix.errnoMessage
import io.github.fukusaka.keel.pipeline.AbstractIoTransport
import io.github.fukusaka.keel.pipeline.AbstractIoTransport.PendingWrite
import io.github.fukusaka.keel.pipeline.EventLoopTimer
import io.github.fukusaka.keel.pipeline.IoTransport
import io.github.fukusaka.keel.pipeline.PendingWriteSnapshotPool
import io_uring.KEEL_POLLERR
import io_uring.KEEL_POLLHUP
import io_uring.KEEL_POLLRDHUP
import io_uring.io_uring_prep_send
import io_uring.iovec
import io_uring.keel_alloc_iovec
import io_uring.keel_cqe_get_buf_id
import io_uring.keel_cqe_has_more
import io_uring.keel_free_iovec
import io_uring.keel_prep_poll_add
import io_uring.keel_prep_shutdown
import io_uring.keel_sqe_set_fixed_file
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.posix.ECANCELED
import platform.posix.ENOBUFS
import platform.posix.MSG_NOSIGNAL
import platform.posix.SHUT_WR
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume

/**
 * io_uring [IoTransport] implementation for Linux.
 *
 * **Read path**: submits RECV with a provided buffer ring. On kernels with
 * `IORING_RECV_MULTISHOT` ([IoUringCapabilities.multishotRecv], 6.0+) a
 * single multishot SQE delivers one CQE per data segment via
 * [IoUringEventLoop.submitMultishotRecv]; on older ring-capable kernels
 * (5.19) a single-shot SQE is re-armed per CQE via
 * [IoUringEventLoop.submitRecvBufSelect]. Either way the kernel fills a
 * pre-registered buffer slot on data arrival and the CQE callback delivers
 * it via [onRead]; `-ENOBUFS` (all slots consumed) defers the re-arm until
 * a buffer is returned.
 *
 * **Write path**: buffers outbound [IoBuf] writes and flushes via
 * [IoModeSelector]-driven strategy:
 * - [IoMode.CQE]: pure io_uring path (SEND / WRITEV SQE, wait for CQE)
 * - [IoMode.FALLBACK_CQE]: direct `send()` syscall, EAGAIN → fallback to CQE
 * - [IoMode.SEND_ZC]: zero-copy via `IORING_OP_SEND_ZC` (two CQEs)
 *
 * **Thread safety**: all methods must be called on the owning [IoUringEventLoop] thread.
 */
@OptIn(ExperimentalForeignApi::class)
// LongParameterList / LargeClass: the transport owns the whole per-connection
// io_uring lifecycle (fd, event loop, capabilities, write-mode selection,
// registered buffer / fixed-file / buffer-ring state, native seams). The
// parameters are the injected collaborators and the size reflects the
// SQE/CQE submit + completion surface for one connection; splitting it would
// scatter tightly-coupled ring state.
@Suppress("LongParameterList", "LargeClass")
internal class IoUringIoTransport(
    private val fd: Int,
    private val eventLoop: IoUringEventLoop,
    private val capabilities: IoUringCapabilities,
    private val writeModeSelector: IoModeSelector = IoModeSelectors.FALLBACK_CQE,
    allocator: BufferAllocator,
    private val bufferRing: ProvidedBufferRing? = null,
    private val fixedFileRegistry: FixedFileRegistry? = null,
    private val registeredBufferTable: IoUringFixedBufferRegistry = DisabledRegisteredBufferRegistry,
    /**
     * Kernel-allocated fixed-file index for direct-allocated multishot accept.
     * When >= 0, the kernel has already placed the fd into the registered
     * file table at this index (see [IoUringCapabilities.acceptDirectAlloc]);
     * the raw [fd] is not exposed to userspace and should be passed as -1.
     * When -1 (default), [fd] is the raw POSIX fd and is registered via
     * [FixedFileRegistry.register].
     */
    preAllocatedIndex: Int = -1,
    private val nativeSocket: NativeSocket = PosixNativeSocket,
    idleTimeoutMillis: Long = 0,
    /**
     * Per-recv allocation size of the allocator-buffer read fallback
     * ([armRecvSingleShotAlloc], kernels without a provided buffer ring).
     * Ring-capable kernels ignore it — their recv buffer size is the
     * ring's own `bufferSize`. Defaults to the engine-wide read buffer
     * size via [IoUringEventLoopGroup.readBufferSize] at the call sites.
     */
    private val readBufferSize: Int = IoTransport.DEFAULT_READ_BUFFER_SIZE,
) : AbstractIoTransport(allocator) {

    override val ioDispatcher: CoroutineDispatcher get() = eventLoop

    override val inOwningContext: Boolean get() = eventLoop.inEventLoop()

    /** Read/write idle (no-progress) timeout for this connection; see [AbstractIoTransport]. */
    override val idleTimeoutMillis: Long = idleTimeoutMillis

    /** Backed by this EventLoop's per-loop [DeadlineScheduler] (EventLoop-confined). */
    override val eventLoopTimer: EventLoopTimer get() = eventLoop.deadlineScheduler

    /**
     * True when the kernel allocated the fixed-file slot itself
     * (direct-allocated accept). In this mode the raw fd is not available
     * to userspace, so [shutdownOutput] uses `IORING_OP_SHUTDOWN` and
     * [teardownOnEventLoop] skips `close(fd)` — slot unregister closes
     * the kernel-held fd.
     */
    private val useDirectAlloc: Boolean = preAllocatedIndex >= 0

    // Fixed file index for IOSQE_FIXED_FILE. -1 if not registered.
    // Direct-alloc path: the kernel has placed the fd into `preAllocatedIndex`
    //   and we only update userspace free-slot bookkeeping via claim().
    // Traditional path: we register the raw fd, which issues a
    //   register_files_update syscall.
    private val fixedFileIndex: Int = when {
        useDirectAlloc -> {
            val registry = fixedFileRegistry
                ?: error("preAllocatedIndex requires fixedFileRegistry")
            if (registry.claim(preAllocatedIndex)) preAllocatedIndex else -1
        }
        else -> fixedFileRegistry?.register(fd) ?: -1
    }

    /**
     * The fd value to use in SQE preparation. When fixed files are active,
     * this is the registered index; otherwise the raw fd.
     */
    internal val sqeFd: Int get() = if (fixedFileIndex >= 0) fixedFileIndex else fd

    /** Whether this transport uses fixed file descriptors. */
    internal val useFixedFile: Boolean get() = fixedFileIndex >= 0

    /**
     * Number of in-flight SQEs that reference the registered file table
     * slot [fixedFileIndex] — recv (all three modes), the async send /
     * writev / zero-copy sends, and the direct-alloc shutdown (POLL_ADD
     * uses the raw fd and is excluded). The slot may not be unregistered
     * — and thereby freed for the NEXT connection to reuse — while any
     * of them is in flight: `IORING_OP_ASYNC_CANCEL` is asynchronous and
     * a poll-armed request re-resolves its slot index on wakeup, so a
     * stale request on a reused slot reads from the next connection's
     * socket (observed as cross-connection data theft on CPU-starved
     * hosts, where the cancel's terminal CQE loses the race to slot
     * reuse). Teardown defers the unregister to the last terminal CQE
     * via [fixedSlotReleasePending]. EventLoop-thread only.
     */
    private var inflightFixedOps = 0

    /**
     * Set by [teardownOnEventLoop] when the transport closed while
     * fixed-slot-referencing SQEs were still in flight; the last
     * [fixedOpCompleted] performs the deferred unregister.
     */
    private var fixedSlotReleasePending = false

    /** Records one fixed-slot-referencing SQE submission. */
    private fun fixedOpSubmitted() {
        if (useFixedFile) inflightFixedOps++
    }

    /**
     * Records one fixed-slot-referencing SQE reaching its terminal CQE,
     * and performs the teardown-deferred slot unregister once the last
     * one lands.
     */
    private fun fixedOpCompleted() {
        if (!useFixedFile) return
        inflightFixedOps--
        if (fixedSlotReleasePending && inflightFixedOps == 0) {
            fixedSlotReleasePending = false
            fixedFileRegistry?.unregister(fixedFileIndex)
        }
    }

    // --- Read path (multishot recv with provided buffer ring) ---

    // Pre-allocated IoBuf wrappers: one per buffer slot.
    // Reused on each CQE callback via reset() — zero allocation on hot path.
    // Forward the per-engine allocator's BufferAllocatorLifecycleListener so
    // each ring-buffer wrapper fires onAllocated / onReleased through the
    // same channel as the allocator-produced fallback path (pluggability
    // item 12 B2.5 step 4). The listener flows from the user-passed
    // config.allocator.lifecycleListener through createChild into the
    // per-engine allocator the transport holds.
    private val wrappers = bufferRing?.let { ring ->
        val listener = allocator.lifecycleListener
        Array(ring.bufferCount) { bufId ->
            RingBufferIoBuf(bufId, ring, listener)
        }
    }

    /**
     * Callback slot of the in-flight recv SQE (multishot on 6.0+ kernels,
     * single-shot buffer-select on 5.19), or -1 when no recv is armed.
     * The single live-recv invariant — at most one armed recv per
     * transport — is shared by both modes; the `recvSlot < 0` gates in
     * the [readEnabled] setter and the single-shot re-arm both rely on it.
     */
    private var recvSlot = -1

    /**
     * The allocator-owned buffer of the in-flight plain single-shot recv
     * ([armRecvSingleShotAlloc]), or null when no such recv is armed or
     * the ring modes are active. Released exclusively by the recv CQE
     * callback — see the ownership note on [armRecvSingleShotAlloc] for
     * why teardown must not release it early. EventLoop-thread only.
     */
    private var pendingRecvBuf: IoBuf? = null

    /**
     * True when the last multishot recv terminated with `-ENOBUFS` (the
     * shared provided-buffer ring was empty) and re-arming has been deferred
     * until a buffer is returned. Re-arming immediately would busy-loop: the
     * kernel keeps re-issuing `-ENOBUFS` for as long as the ring stays empty,
     * burning 100% of the EventLoop on a connection that cannot progress
     * (the recv-buffer-leak / -ENOBUFS busy-loop — surfaced under `server-http × compression-upload` where the
     * pull-model body conduit holds buffers long enough to drain the ring).
     * Cleared by [rearmRecvAfterStarvation]. EventLoop-thread only.
     */
    private var recvStarved = false

    /**
     * Deferred re-arm registered with the shared [ProvidedBufferRing] when a
     * recv hits `-ENOBUFS`. Allocated once per transport (a field, not a
     * per-operation lambda) so the starvation path stays allocation-free. The
     * ring invokes it when a buffer is returned; it re-arms only if the
     * channel is still open and reading.
     */
    private val rearmRecvAfterStarvation: () -> Unit = {
        recvStarved = false
        if (opened && readEnabled) armRecv()
    }

    // ---- Flow-control pause (pauseReads / resumeReads) ----

    /**
     * True while [pauseReads] has suspended inbound consumption. [armRecv]
     * is a no-op while set, so no recv SQE exists beyond the in-flight
     * one being cancelled — the kernel socket buffer retains further
     * bytes and TCP back-pressure reaches the peer. Peer FIN stays
     * observable through the lifetime POLL_ADD. EventLoop-thread only.
     */
    private var readPaused = false

    /**
     * True while a pause-initiated `IORING_OP_ASYNC_CANCEL` against the
     * in-flight recv is outstanding. Distinguishes the cancel's benign
     * `-ECANCELED` terminal CQE (clear state, maybe re-arm) from a
     * genuine connection error (which must still fire
     * [fireReadClosedOnce]); teardown's own cancels are already filtered
     * by the not-opened gate. Cleared on every recv terminal and on
     * [armRecv]. EventLoop-thread only.
     */
    private var recvCancelPending = false

    override fun pauseReads() {
        if (readPaused) return // idempotent: a second cancel would be a wasted SQE
        readPaused = true
        cancelIdleTimeout() // back-pressure: pause the read-idle timeout
        // Cancel the in-flight recv on the multishot tier only: a
        // multishot SQE keeps delivering for as long as the ring has
        // buffers (and copy-on-pressure keeps it fed), so without the
        // cancel the pause would never take effect there. The cancel is
        // asynchronous: data CQEs already completed remain the bounded
        // overshoot, and the terminal CQE performs the deferred state
        // reset. The single-shot tiers need no cancel — their in-flight
        // recv delivers at most once and the re-arm is already gated on
        // the pause (the epoll-shaped overshoot of one delivery).
        if (recvSlot >= 0 && capabilities.multishotRecv && bufferRing != null) {
            recvCancelPending = true
            eventLoop.cancelSqeKeepCallback(recvSlot)
        }
    }

    override fun resumeReads() {
        readPaused = false
        if (!opened || !readEnabled) return
        armIdleTimeout()
        // recvSlot >= 0: the pause-cancel's terminal CQE has not drained
        // yet — it re-arms on arrival now that readPaused is false
        // (arming here would double-arm, the #741 shape). recvStarved:
        // the ring's deferred re-arm callback owns the recovery.
        if (recvSlot < 0 && !recvStarved) armRecv()
    }

    /**
     * Slot tracking the single-shot `IORING_OP_POLL_ADD` SQE that watches
     * for peer FIN / hangup / error events. Negative when no POLL_ADD is
     * armed; non-negative once [armPollAddForFin] has registered the SQE.
     * The slot is implicitly released when the kernel delivers the
     * (single) CQE — either because the poll mask fired or because the
     * fd was closed and the kernel cancelled the op.
     */
    private var pollAddFinSlot = -1

    /**
     * Whether [onReadClosed] has already been invoked. Guards against
     * double-firing when both [armPollAddForFin]'s POLL_ADD CQE and
     * [armRecv]'s multishot recv CQE observe the same peer FIN. Set on
     * the EventLoop thread only.
     */
    private var readClosedFired = false

    private fun fireReadClosedOnce() {
        if (readClosedFired) return
        readClosedFired = true
        onReadClosed?.invoke()
    }

    /**
     * Arms a single-shot `IORING_OP_POLL_ADD` watching for `POLLRDHUP |
     * POLLHUP | POLLERR` (no `POLLIN` — bytes do not trigger the CQE).
     *
     * **Why a separate POLL_ADD instead of relying on multishot recv**:
     * the multishot recv path only delivers a `res = 0` CQE on FIN
     * while it is armed (i.e. while `readEnabled = true`). When a user
     * holds `readEnabled = false` (write-only push client, monitoring
     * forwarder, etc.), no recv SQE is queued and peer FIN is invisible
     * — the connection lingers in `CLOSE-WAIT` until the next write or
     * `SO_KEEPALIVE` expires (~2 hours by default). POLL_ADD provides
     * an *event-only* peer-close channel that is independent of the
     * read syscall path: the kernel produces one CQE when the requested
     * mask is satisfied, and the fd's receive buffer is left untouched
     * so genuine TCP back-pressure (kernel `rcvbuf` retention) is
     * preserved.
     *
     * Equivalent to `epoll_ctl(EPOLL_CTL_ADD, EPOLLRDHUP | EPOLLHUP |
     * EPOLLERR)` on engine-epoll and `EVFILT_READ` + `EV_EOF` flag
     * observation on engine-kqueue. With this in place, engine-io-uring
     * achieves the same "peer FIN detection without active read"
     * semantics as those engines and does not need to honour
     * [io.github.fukusaka.keel.core.IdleReadPolicy] (the policy is
     * relevant only for engines whose underlying API forces a trade-off
     * between peer-close detection and back-pressure on the idle-read
     * window).
     *
     * **Why single-shot, not multishot**: `POLLRDHUP | POLLHUP | POLLERR`
     * are terminal events — once a bit is set, the kernel never clears
     * it for the lifetime of the connection. Multishot poll re-arms on
     * state transitions, but terminal events have no transition back,
     * so multishot would fire exactly one CQE and idle thereafter —
     * functionally equivalent to single-shot. Given the equivalence,
     * single-shot is the simpler choice: the kernel auto-releases the
     * SQE after the single CQE rather than requiring an explicit
     * `IORING_OP_ASYNC_CANCEL` round-trip when the event has already
     * fired before close. Single-shot also sidesteps the multishot
     * poll re-arm race classes that have surfaced historically in
     * io_uring CVEs (the kernel's terminal-event handling for the
     * single-shot path is the longer-standing implementation).
     */
    private fun armPollAddForFin() {
        // Idempotency gate. `onChannelAttached` is normally called exactly
        // once per channel lifetime, but defensive: a second call (e.g.
        // pipeline re-attach scenarios, an external thread racing the
        // bossLoop dispatch) would otherwise overwrite `pollAddFinSlot`,
        // orphaning the first SQE's slot in `callbackSlots[]` until the
        // kernel delivers its CQE. Same canonical pattern as the double-arm
        // gates fixed in PR #737 (the since-removed owned-source read path)
        // and PR #741 (IoUringIoTransport.readEnabled).
        if (pollAddFinSlot >= 0) return
        // POLL_ADD does not support the registered file table (fd must be
        // a raw POSIX fd), so direct-allocated transports skip this path.
        // Direct-allocated multishot accept is gated behind the
        // `acceptDirectAlloc` capability and is rare in normal use.
        if (useDirectAlloc) {
            eventLoop.logger.debug {
                "skipping POLL_ADD for FIN detection: useDirectAlloc=true (no raw fd)"
            }
            return
        }
        // Mask: POLLRDHUP | POLLHUP | POLLERR — peer-close / hangup / error
        // events ONLY. POLLIN is intentionally excluded so application bytes
        // arriving in the receive buffer do NOT fire this CQE (data delivery
        // remains exclusively the multishot recv path's responsibility).
        // Without this, a `transport.onReadClosed` default that closes the
        // channel on EOF would prematurely tear the connection down on every
        // non-empty data arrival under `readEnabled = false`.
        val pollMask: UInt = KEEL_POLLRDHUP or KEEL_POLLHUP or KEEL_POLLERR
        pollAddFinSlot = eventLoop.submitCallback(
            prepare = { sqe ->
                io_uring.keel_prep_poll_add(sqe, fd, pollMask)
            },
            onCqe = { res, _ ->
                pollAddFinSlot = -1
                if (!opened) return@submitCallback
                if (res >= 0) {
                    eventLoop.logger.debug {
                        "POLL_ADD FIN CQE: fd=$fd revents=0x${res.toString(16)}"
                    }
                    fireReadClosedOnce()
                }
                // Negative `res` means the poll itself was cancelled
                // (e.g. `-ECANCELED` when [teardownOnEventLoop] cancels the
                // SQE before close). No action — close path handles cleanup.
            },
        )
        eventLoop.logger.debug {
            "POLL_ADD FIN armed: fd=$fd slot=$pollAddFinSlot mask=0x${pollMask.toString(16)}"
        }
    }

    override fun onChannelAttached() {
        // Arm POLL_ADD here — after [AbstractPipelinedChannel.init] has
        // wired up [onReadClosed] — so the (single) CQE always observes
        // a non-null callback. Same race-avoidance pattern as engine-nio
        // / engine-netty / engine-nwconnection (PR #475's
        // [IoTransport.onChannelAttached] hook).
        if (eventLoop.inEventLoop()) {
            armPollAddForFin()
        } else {
            eventLoop.dispatch(EmptyCoroutineContext, Runnable { armPollAddForFin() })
        }
    }

    override var readEnabled: Boolean = false
        set(value) {
            field = value
            if (value && opened) {
                // The connection is now waiting to read → the read-side idle timeout
                // applies (covers accept-to-first-byte, slowloris-silent, keep-alive idle).
                armIdleTimeout()
                // Arm only when no live recv is in flight and we are not
                // already waiting on starvation (the `rearmRecvAfterStarvation`
                // callback registered with the buffer ring will fire on the next
                // `returnBuffer` and arm there — racing it here would submit a
                // second recv SQE that orphans the slot of the first and
                // double-delivers CQEs into the shared `wrappers[bufId]`).
                if (recvSlot < 0 && !recvStarved) armRecv()
            } else if (!value) {
                cancelIdleTimeout() // back-pressure: pause the read-idle timeout
            }
        }

    /**
     * Arms the inbound read for this transport, dispatching on the
     * kernel's recv capabilities:
     *
     * - ring + [IoUringCapabilities.multishotRecv] (6.0+): multishot recv —
     *   one SQE, many CQEs ([armRecvMultishot])
     * - ring without multishot (5.19): single-shot buffer-select recv
     *   re-armed per CQE ([armRecvSingleShot])
     * - no ring (< 5.19): plain single-shot recv into an allocator-owned
     *   buffer ([armRecvSingleShotAlloc])
     *
     * The ring modes share the `wrappers[bufId]` delivery ([deliverRecv])
     * and the `-ENOBUFS` deferred re-arm ([onRecvEnobufs]); the allocator
     * mode has neither concern (the destination buffer is fixed at submit
     * time, so no kernel-side selection can starve).
     */
    private fun armRecv() {
        if (readPaused) return // flow-control pause: no new recv until resumeReads
        recvCancelPending = false // a fresh SQE has no outstanding pause-cancel
        val ring = bufferRing
        when {
            ring == null -> armRecvSingleShotAlloc()
            capabilities.multishotRecv -> armRecvMultishot(ring)
            else -> armRecvSingleShot(ring)
        }
    }

    private fun armRecvMultishot(ring: ProvidedBufferRing) {
        fixedOpSubmitted()
        recvSlot = eventLoop.submitMultishotRecv(
            fd = sqeFd,
            fixedFile = useFixedFile,
            bgid = ring.bgid,
            onCqe = { res, flags ->
                // Terminal CQE bookkeeping must run even post-teardown
                // (the deferred slot unregister depends on it), so it
                // precedes the opened check.
                if (keel_cqe_has_more(flags) == 0) fixedOpCompleted()
                if (!opened) return@submitMultishotRecv
                eventLoop.logger.debug {
                    "recv CQE: sqeFd=$sqeFd fixedFile=$useFixedFile res=$res flags=0x${flags.toString(16)}"
                }
                when {
                    res > 0 -> deliverRecv(ring, res, flags)
                    res == -ENOBUFS -> {
                        // Shared provided-buffer ring ran out. The kernel drops
                        // IORING_CQE_F_MORE on -ENOBUFS so the CQE drain already
                        // released the slot. A pause-cancel that raced this
                        // natural termination has nothing left to cancel —
                        // clear its flag so it cannot mask a later genuine
                        // -ECANCELED.
                        recvCancelPending = false
                        recvSlot = -1
                        onRecvEnobufs(ring)
                    }
                    res == -ECANCELED && recvCancelPending -> {
                        // Benign terminal of the pauseReads cancel — not a
                        // connection error, so no fireReadClosedOnce. If the
                        // pause was already resumed, this CQE is the agreed
                        // re-arm point (arming earlier would double-arm).
                        recvCancelPending = false
                        recvSlot = -1
                        if (readEnabled && !readPaused && !recvStarved) armRecv()
                    }
                    else -> fireReadClosedOnce()
                }
            },
        )
        eventLoop.logger.debug {
            "armRecv submitted: sqeFd=$sqeFd fixedFile=$useFixedFile recvSlot=$recvSlot"
        }
    }

    /**
     * Single-shot fallback of [armRecvMultishot] for ring-capable kernels
     * without `IORING_RECV_MULTISHOT` (5.19): the same kernel-side buffer
     * selection, but every CQE terminates its SQE, so delivery re-arms
     * explicitly. Backpressure is inherent in this mode — when
     * [readEnabled] is false the re-arm simply does not happen (the
     * epoll/kqueue semantics), unlike multishot where the SQE stays armed
     * and flow control relies on `-ENOBUFS`.
     */
    private fun armRecvSingleShot(ring: ProvidedBufferRing) {
        fixedOpSubmitted()
        recvSlot = eventLoop.submitRecvBufSelect(
            fd = sqeFd,
            fixedFile = useFixedFile,
            bgid = ring.bgid,
            len = ring.bufferSize,
            onCqe = { res, flags ->
                // Single-shot: this CQE terminates the SQE. The drain frees
                // this callback's slot after the callback returns (hasMore
                // = false), so a re-arm below acquires a fresh slot; clear
                // the in-flight marker first so the re-arm gates see
                // recvSlot < 0. The fixed-op bookkeeping must run even
                // post-teardown (deferred slot unregister), so it precedes
                // the opened check.
                recvSlot = -1
                fixedOpCompleted()
                if (!opened) return@submitRecvBufSelect
                eventLoop.logger.debug {
                    "recv CQE (single-shot): sqeFd=$sqeFd fixedFile=$useFixedFile res=$res flags=0x${flags.toString(16)}"
                }
                when {
                    res > 0 -> {
                        deliverRecv(ring, res, flags)
                        // Re-arm only if the handler left the transport open
                        // and reading. `onRead` or `onReadComplete` may have
                        // flipped readEnabled (whose setter re-arms on the
                        // false→true edge and sees recvSlot >= 0 once it has)
                        // — the recvSlot guard keeps the single-live-recv
                        // invariant.
                        val canRearmRecv = opened && readEnabled && !recvStarved && recvSlot < 0
                        if (canRearmRecv) armRecv()
                    }
                    res == -ENOBUFS -> onRecvEnobufs(ring)
                    else -> fireReadClosedOnce()
                }
            },
        )
        eventLoop.logger.debug {
            "armRecv submitted (single-shot): sqeFd=$sqeFd fixedFile=$useFixedFile recvSlot=$recvSlot"
        }
    }

    /**
     * Allocator-buffer fallback for kernels without a provided buffer ring
     * (< 5.19): a plain single-shot `IORING_OP_RECV` into an allocator-owned
     * [IoBuf]. No kernel-side buffer selection — the destination is fixed at
     * submit time — so no `-ENOBUFS` class exists; like [armRecvSingleShot],
     * backpressure is inherent (no re-arm while [readEnabled] is false).
     *
     * **Buffer ownership**: the in-flight buffer is held in [pendingRecvBuf]
     * until its CQE. On data the buffer is handed to [onRead] (ownership
     * transfers to the handler, identical to the ring-wrapper contract); on
     * EOF, error, or `-ECANCELED` it is released here. The buffer is NEVER
     * released at teardown time: `IORING_OP_ASYNC_CANCEL` is asynchronous and
     * the kernel may still complete the recv (writing into the memory) before
     * the cancellation lands — releasing early would return pooled memory the
     * kernel can still write into. Teardown cancels the SQE and the resulting
     * CQE (data or -ECANCELED) performs the release through this callback.
     */
    private fun armRecvSingleShotAlloc() {
        val buf = allocator.allocate(readBufferSize)
        pendingRecvBuf = buf
        fixedOpSubmitted()
        recvSlot = eventLoop.submitRecv(
            fd = sqeFd,
            fixedFile = useFixedFile,
            buf = buf.unsafePointer,
            len = readBufferSize,
            onCqe = { res, _ ->
                // Single-shot: this CQE terminates the SQE (same slot
                // lifecycle as the buffer-select variant above). Fixed-op
                // bookkeeping runs even post-teardown (deferred unregister).
                recvSlot = -1
                fixedOpCompleted()
                val pending = pendingRecvBuf
                pendingRecvBuf = null
                eventLoop.logger.debug {
                    "recv CQE (alloc single-shot): sqeFd=$sqeFd fixedFile=$useFixedFile res=$res"
                }
                when {
                    pending == null -> {
                        // Defensive: a CQE with no in-flight buffer recorded
                        // would indicate a double-delivery; nothing to release.
                        eventLoop.logger.warn {
                            "recv CQE with no pending buffer: sqeFd=$sqeFd res=$res"
                        }
                    }
                    !opened || res <= 0 -> {
                        // Teardown raced the CQE (-ECANCELED or late data),
                        // EOF (res = 0), or a receive error: the buffer never
                        // reaches the handler, so release it here.
                        pending.release()
                        if (opened) fireReadClosedOnce()
                    }
                    else -> {
                        touchIdleTimeout() // progress: refresh the read-idle deadline
                        pending.writerIndex = res
                        onRead?.invoke(pending)
                        // One completion is one batch.
                        onReadComplete?.invoke()
                        // Re-arm with a fresh buffer; same gates as the
                        // buffer-select single-shot mode (recvStarved is
                        // always false here — no ring, no starvation).
                        if (opened && readEnabled && recvSlot < 0) armRecv()
                    }
                }
            },
        )
        eventLoop.logger.debug {
            "armRecv submitted (alloc single-shot): sqeFd=$sqeFd fixedFile=$useFixedFile recvSlot=$recvSlot"
        }
    }

    /**
     * Delivers one recv CQE's bytes to [onRead] through the pre-allocated
     * ring-buffer wrapper. Shared by the multishot and single-shot recv
     * modes — the CQE encodes the selected buffer the same way in both.
     */
    private fun deliverRecv(ring: ProvidedBufferRing, res: Int, flags: UInt) {
        // One buffer left the ring for this CQE; record it so the ring's
        // `hasAvailable` reflects current occupancy.
        ring.onConsumed()
        touchIdleTimeout() // progress: refresh the read-idle deadline
        val bufId = keel_cqe_get_buf_id(flags).toInt()
        val buf = wrappers!![bufId]
        buf.reset()
        buf.writerIndex = res
        if (ring.underPressure) {
            // Copy-on-pressure: the shared ring is close to empty, so hand
            // the consumer an allocator-owned copy and return the slot now —
            // a delivered slot is otherwise pinned for as long as downstream
            // references it (the request's whole latency when the codec
            // retains it for header views), and enough pinned slots stall
            // every connection on this loop. See [ProvidedBufferRing.underPressure].
            val copy = allocator.allocate(res)
            buf.copyTo(copy, res)
            buf.release() // refCount 1 -> 0: returns the slot to the ring
            ring.onCopyOnPressure()
            onRead?.invoke(copy)
        } else {
            onRead?.invoke(buf)
        }
        // One completion is one batch, whichever path delivered it.
        onReadComplete?.invoke()
    }

    /**
     * Handles a recv `-ENOBUFS` CQE (shared provided-buffer ring empty).
     * Shared by both recv modes; the caller has already cleared [recvSlot].
     */
    private fun onRecvEnobufs(ring: ProvidedBufferRing) {
        ring.onRecvEnobufs() // occupancy observability: count every starvation CQE
        if (ring.hasAvailable) {
            // Buffers are already back in the ring — typically within this
            // same CQE batch, when a single read delivery exceeds the whole
            // ring (e.g. a ~1 MiB WS frame vs a 512 KiB ring): the kernel
            // fills + reports every buffer and raises -ENOBUFS, and the app
            // returns them before this CQE is processed. Re-arm now;
            // deferring would stall forever (no later returnBuffer to fire
            // the re-arm).
            recvStarved = false
            armRecv()
        } else if (!recvStarved) {
            // Ring genuinely empty (buffers still held downstream). Defer
            // the re-arm to the next returnBuffer instead of busy-looping
            // (the -ENOBUFS busy-loop). The recvStarved guard collapses
            // repeat -ENOBUFS into one registration.
            recvStarved = true
            ring.requestRearmOnAvailable(rearmRecvAfterStarvation)
        }
    }

    // --- Lifecycle ---

    /**
     * Sends FIN on the EventLoop thread, like [close] does.
     *
     * The direct-alloc path submits `IORING_OP_SHUTDOWN` through
     * [IoUringEventLoop.submitCallback], which is documented EventLoop-only: it
     * takes a slot from a plain (non-atomic) freelist and an SQE from the shared
     * submission ring, both owned by the loop under `IORING_SETUP_SINGLE_ISSUER`.
     * Calling it from another thread corrupts that state. The plain path issues
     * `shutdown(2)` directly, and its `outputShutdown` guard is a plain field —
     * two callers could both pass it. Confining the whole method to the loop
     * fixes both.
     *
     * Idempotent, and safe to call from any thread. The FIN is sent
     * asynchronously when the caller is off-loop, and after any buffered
     * writes have drained.
     */
    override fun shutdownOutput() {
        if (eventLoop.inEventLoop()) {
            shutdownOutputOwned()
        } else {
            eventLoop.dispatch(EmptyCoroutineContext, Runnable { shutdownOutputOwned() })
        }
    }

    /**
     * An async send chain owns its buffers outside [pendingWrites] (they move
     * into a snapshot the moment the SQEs are submitted), so an empty queue
     * alone does not mean the bytes have reached the ring.
     */
    override val outputDrained: Boolean
        get() = pendingWrites.isEmpty() && asyncFlushesInFlight == 0

    override fun sendFin() {
        if (useDirectAlloc) {
            // Direct-allocated slots do not expose the raw fd; route
            // through IORING_OP_SHUTDOWN + IOSQE_FIXED_FILE. Fire and
            // forget — the CQE result is logged on failure only.
            fixedOpSubmitted()
            eventLoop.submitCallback(
                prepare = { sqe ->
                    keel_prep_shutdown(sqe, sqeFd, SHUT_WR)
                    keel_sqe_set_fixed_file(sqe)
                },
                onCqe = { res, _ ->
                    fixedOpCompleted()
                    if (res < 0) {
                        eventLoop.logger.warn {
                            "io_uring shutdown(SHUT_WR) failed: index=$sqeFd ${errnoMessage(-res)}"
                        }
                    }
                },
            )
        } else {
            when (val result = nativeSocket.shutdown(fd, SHUT_WR)) {
                ShutdownResult.Ok -> Unit
                is ShutdownResult.Failed -> eventLoop.logger.warn {
                    "shutdown(SHUT_WR) failed: fd=$fd ${errnoMessage(result.errno)}"
                }
            }
        }
    }

    // --- Write path ---

    /** Per-connection I/O statistics for adaptive mode selection. */
    internal val stats = ConnectionStats()

    /**
     * Free-list for the [ArrayList]<[PendingWrite]> ownership snapshots taken
     * before an async writev/sendmsg completion transfers buffer release
     * responsibility away from [pendingWrites]. See [PendingWriteSnapshotPool]
     * KDoc for why a fixed-size double-buffer is unsafe here.
     */
    private val pendingWriteSnapshotPool = PendingWriteSnapshotPool()

    // Per-flush tracking for stats recording.
    private var flushHadEagain = false
    private var flushBytesWritten = 0L

    /** Bytes still pending in async flush, decremented on async completion. */
    private var asyncPendingFlushBytes = 0

    /**
     * Fire-and-forget flush with [IoModeSelector]-driven strategy.
     *
     * Selects the I/O mode per connection and dispatches to the appropriate
     * flush strategy. All three modes are supported as fire-and-forget:
     * - [IoMode.FALLBACK_CQE]: direct `send()`, EAGAIN → async SEND SQE
     * - [IoMode.CQE]: async SEND SQE (single) or WRITEV SQE (gather)
     * - [IoMode.SEND_ZC]: async SEND_ZC SQE with 2-CQE callback
     *
     * @return true if flush completed synchronously, false if async pending.
     */
    override fun flush(): Boolean {
        if (pendingWrites.isEmpty()) return true

        val rawMode = writeModeSelector.select(stats)
        // Capability fallback: degrade to CQE if the kernel lacks the opcode.
        // SENDMSG_ZC also requires sendZc (single-buffer path uses SEND_ZC).
        // FALLBACK_CQE issues direct send()/writev() syscalls with the raw fd;
        // direct-allocated slots don't expose a raw fd, so those modes must
        // stay on the pure io_uring CQE path.
        val mode = when {
            rawMode == IoMode.SEND_ZC && !capabilities.sendZc -> IoMode.CQE
            rawMode == IoMode.SENDMSG_ZC && (!capabilities.sendmsgZc || !capabilities.sendZc) -> IoMode.CQE
            rawMode == IoMode.FALLBACK_CQE && useDirectAlloc -> IoMode.CQE
            else -> rawMode
        }

        flushHadEagain = false
        flushBytesWritten = 0L
        val done = try {
            when (mode) {
                IoMode.FALLBACK_CQE -> flushDirectSend()
                IoMode.CQE -> {
                    flushCqe()
                    false
                }
                IoMode.SEND_ZC -> {
                    flushSendZc()
                    false
                }
                IoMode.SENDMSG_ZC -> {
                    flushSendmsgZc()
                    false
                }
            }
        } finally {
            stats.recordFlush(flushHadEagain, flushBytesWritten)
            pendingWrites.clear()
        }
        // A flush that did not complete synchronously means data is buffered for a
        // peer whose receive window is full (slow-read) — start the write-idle clock.
        // Drain progress refreshes it and a full drain cancels it, both via
        // updatePendingBytes on the async send completion(s).
        if (!done) armWriteIdleTimeout()
        return done
    }

    // --- FALLBACK_CQE: direct send → EAGAIN → async SEND SQE ---

    /**
     * Attempts direct POSIX `writev()` for all pending writes.
     *
     * Uses gather write to send all buffers in a single syscall,
     * avoiding per-buffer send() overhead with many small buffers
     * (e.g., 100KB response split into 13 × 8KB by BufferedSuspendSink).
     * On partial write or EAGAIN, releases fully-written buffers and
     * submits the remainder as async SEND chain.
     *
     * @return true if all data sent synchronously, false if async pending.
     */
    private fun flushDirectSend(): Boolean {
        if (pendingWrites.size == 1) {
            return flushDirectSendSingle(pendingWrites[0])
        }
        return flushDirectSendGather()
    }

    /**
     * Gather write via [NativeSocket.writev] for multiple pending writes.
     *
     * On partial write, releases fully-written buffers and submits the
     * remainder as an async SEND chain via [submitAsyncWritevRemainder]; on a
     * full `EAGAIN` (nothing written) submits all buffers via
     * [submitAsyncSendChain]. Routing through the [nativeSocket] seam (the
     * same one epoll / kqueue already use) makes both branches
     * seam-testable — the raw `keel_writev` call this replaced was invisible
     * to `FakeNativeSocket`.
     */
    private fun flushDirectSendGather(): Boolean {
        val totalBytes = pendingWrites.sumOf { it.length }
        val writtenBytes: Int

        memScoped {
            val count = pendingWrites.size
            val bases = allocArray<CPointerVar<ByteVar>>(count)
            val lens = allocArray<ULongVar>(count)
            for ((i, pw) in pendingWrites.withIndex()) {
                bases[i] = (pw.buf.unsafePointer + pw.offset)!!
                lens[i] = pw.length.convert()
            }
            writtenBytes = when (val result = nativeSocket.writev(fd, bases, lens, count)) {
                WriteResult.WouldBlock -> {
                    // Nothing written — submit all as an async SEND chain.
                    // Snapshot pendingWrites: flush() clears it the moment this
                    // returns, but the chain iterates the buffers across CQE
                    // callbacks (the same ownership-snapshot contract the
                    // partial-writev remainder path uses).
                    flushHadEagain = true
                    asyncFlushesInFlight++
                    asyncPendingFlushBytes += totalBytes
                    submitAsyncSendChain(pendingWriteSnapshotPool.borrow(pendingWrites), 0)
                    return false
                }
                is WriteResult.Failed -> {
                    // Unrecoverable error (EPIPE after peer RST, ECONNRESET, EBADF,
                    // etc.). Surface to the pipeline via fireReadClosedOnce so the
                    // channel tears down — the previous "release and return true"
                    // path was silent from the pipeline's perspective, leaving the
                    // orphaned transport alive and the upstream codec convinced
                    // its bytes had landed. Same canonical pattern as
                    // flushDirectSendSingle's `if (fatalError) fireReadClosedOnce()`
                    // and the four async write callbacks fixed in PR #746.
                    eventLoop.logger.warn {
                        "writev() failed: fd=$fd ${errnoMessage(result.errno)} (totalBytes=$totalBytes)"
                    }
                    for (pw in pendingWrites) pw.buf.release()
                    updatePendingBytes(-totalBytes)
                    fireReadClosedOnce()
                    return true
                }
                is WriteResult.Written -> result.bytes
            }
        }

        flushBytesWritten += writtenBytes

        if (writtenBytes >= totalBytes) {
            for (pw in pendingWrites) pw.buf.release()
            updatePendingBytes(-totalBytes)
            return true
        }

        // Partial write: release fully-written buffers, submit remainder async.
        flushHadEagain = true
        var consumed = 0
        var splitIndex = 0
        for ((i, pw) in pendingWrites.withIndex()) {
            if (consumed + pw.length <= writtenBytes) {
                consumed += pw.length
                pw.buf.release()
                updatePendingBytes(-pw.length)
            } else {
                splitIndex = i
                break
            }
        }
        // Submit remaining from splitIndex as async chain.
        asyncFlushesInFlight++
        val alreadySentInSplit = (writtenBytes - consumed).coerceAtLeast(0)
        val remainingBytes = totalBytes - writtenBytes
        asyncPendingFlushBytes += remainingBytes
        if (alreadySentInSplit > 0) {
            updatePendingBytes(-alreadySentInSplit)
        }
        submitAsyncWritevRemainder(pendingWriteSnapshotPool.borrow(pendingWrites), splitIndex, alreadySentInSplit)
        return false
    }

    /**
     * Sends a single [PendingWrite] via direct send() with EAGAIN → SEND SQE fallback.
     *
     * On success, releases the buffer and returns true.
     * On EAGAIN, submits async send (which retains the buffer) and returns false.
     *
     * @return true if fully sent synchronously, false if async SEND SQE submitted.
     */
    private fun flushDirectSendSingle(pw: PendingWrite): Boolean {
        var written = 0
        var fatalError = false
        while (written < pw.length) {
            val ptr = (pw.buf.unsafePointer + pw.offset + written)!!
            val remaining = pw.length - written
            when (val result = nativeSocket.send(fd, ptr, remaining, MSG_NOSIGNAL)) {
                is WriteResult.Written -> {
                    written += result.bytes
                    flushBytesWritten += result.bytes.toLong()
                }
                WriteResult.WouldBlock -> {
                    flushHadEagain = true
                    // Decrement sync-written portion; async remainder tracked via asyncPendingFlushBytes.
                    updatePendingBytes(-written)
                    val asyncBytes = pw.length - written
                    // Same bookkeeping the gather / CQE / ZC paths do: the SQE below
                    // outlives this call and flush()'s finally clears pendingWrites,
                    // so the queue emptying is not evidence the bytes have gone.
                    // Without this, awaitPendingFlush resumes early and the half-close
                    // sends its FIN ahead of the outstanding send.
                    asyncFlushesInFlight++
                    asyncPendingFlushBytes += asyncBytes
                    // Transfer buffer ownership to submitAsyncSend.
                    // Do NOT release here — submitAsyncSend manages the lifecycle.
                    submitAsyncSend(pw.buf, pw.offset + written, asyncBytes)
                    return false
                }
                is WriteResult.Failed -> {
                    val err = result.errno
                    // PosixNativeSocket maps send()==0 to Failed(errno=0). TCP send
                    // returning 0 for a non-empty request is spec-unexpected — log
                    // with the dedicated message so errnoMessage(0) ("Success")
                    // doesn't muddy the trail.
                    eventLoop.logger.warn {
                        if (err == 0) {
                            "send() returned 0 unexpectedly: fd=$fd written=$written/${pw.length}"
                        } else {
                            // Unrecoverable error (e.g., EPIPE after peer RST,
                            // ECONNRESET, EBADF). Mark the connection for teardown
                            // — previously we silently released the buffer and
                            // returned "flush complete", leaving the orphaned
                            // transport alive and the pipeline unaware that the
                            // echo never reached the peer.
                            "send() failed: fd=$fd ${errnoMessage(err)} (written=$written/${pw.length})"
                        }
                    }
                    fatalError = true
                    break
                }
            }
        }
        pw.buf.release()
        updatePendingBytes(-pw.length)
        if (fatalError) {
            // Route through the read-closed path so the pipeline notifies
            // inactive and the channel tears down cleanly. Safe even though
            // the error is write-side: the connection is unusable either way.
            fireReadClosedOnce()
        }
        return true
    }

    /**
     * Submits [writes] from [startIndex] as a sequential async SEND chain.
     *
     * Called from [flushDirectSendGather] on a full `EAGAIN`. [writes] is a
     * snapshot borrowed from [pendingWriteSnapshotPool] because `flush()`
     * clears `pendingWrites` the moment the gather returns, while this chain
     * advances asynchronously across CQE callbacks; the snapshot is recycled
     * when the chain drains. Buffers are sent one at a time in order: the next
     * buffer is submitted only after the current one fully completes via CQE
     * callback chaining. This guarantees TCP byte-stream order even with
     * partial sends (io_uring CQEs for concurrent SQEs on the same fd do not
     * guarantee completion order).
     *
     * `asyncPendingFlushBytes` is credited once at the call site with the full
     * total (matching every other flush path); this chain must NOT add per
     * buffer or the count double-charges and [onAsyncFlushDone] over-decrements
     * the pending-bytes backpressure counter.
     *
     * **Future optimization**: `IOSQE_IO_LINK` (Linux 5.3+) could submit
     * all SQEs in one batch while preserving order. However, partial sends
     * break the link chain, requiring fallback logic. Deferred per YAGNI —
     * EAGAIN with multiple PendingWrites is rare on typical workloads.
     *
     * [onFlushComplete] is invoked after the last buffer completes.
     */
    private fun submitAsyncSendChain(writes: ArrayList<PendingWrite>, startIndex: Int) {
        if (startIndex >= writes.size) {
            pendingWriteSnapshotPool.recycle(writes)
            onAsyncFlushDone()
            return
        }
        val pw = writes[startIndex]
        submitAsyncSendSequential(pw.buf, pw.offset, pw.length) {
            submitAsyncSendChain(writes, startIndex + 1)
        }
    }

    /**
     * Submits a SEND SQE and invokes [onComplete] when all bytes are sent.
     *
     * Takes ownership of [buf] (already retained by the caller's write()).
     * The buffer is released after all bytes are sent or on error.
     * Partial sends recurse until complete, then [onComplete] is called.
     */
    private fun submitAsyncSendSequential(
        buf: IoBuf,
        offset: Int,
        length: Int,
        onComplete: () -> Unit,
    ) {
        val ptr = (buf.unsafePointer + offset)!!
        fixedOpSubmitted()
        eventLoop.submitCallback(
            prepare = { sqe ->
                io_uring_prep_send(sqe, sqeFd, ptr, length.convert(), MSG_NOSIGNAL)
                if (useFixedFile) keel_sqe_set_fixed_file(sqe)
            },
            onCqe = { res, _ ->
                fixedOpCompleted()
                val sent = if (res > 0) res else 0
                val remaining = length - sent
                if (remaining > 0 && res > 0) {
                    // Partial send: submit another SQE for the remainder.
                    submitAsyncSendSequential(buf, offset + sent, remaining, onComplete)
                } else {
                    // Surface async send errors (EPIPE / ECONNRESET / etc.) —
                    // previously we silently released the buffer and called
                    // onComplete, leaving the pipeline unaware that the
                    // echo never landed and the orphaned transport alive.
                    if (res < 0) {
                        eventLoop.logger.warn {
                            "async send CQE failed: sqeFd=$sqeFd res=$res (${errnoMessage(-res)})"
                        }
                    }
                    buf.release()
                    onComplete()
                    if (res < 0) fireReadClosedOnce()
                }
            },
        )
    }

    /**
     * Submits a SEND SQE for a single buffer (standalone, not part of a chain).
     *
     * Used by [flushDirectSend] (EAGAIN fallback) and [flushCqe] (single buffer).
     */
    private fun submitAsyncSend(buf: IoBuf, offset: Int, length: Int) {
        submitAsyncSendSequential(buf, offset, length) {
            onAsyncFlushDone()
        }
    }

    // --- CQE mode: all I/O via io_uring SQE/CQE ---

    /**
     * Submits all pending writes as io_uring SQEs without direct syscall attempt.
     *
     * Single buffer uses SEND SQE; multiple buffers use WRITEV SQE (gather write).
     */
    private fun flushCqe() {
        asyncFlushesInFlight++
        val totalBytes = pendingWrites.sumOf { it.length }
        asyncPendingFlushBytes += totalBytes
        if (pendingWrites.size == 1) {
            val pw = pendingWrites[0]
            submitAsyncSend(pw.buf, pw.offset, pw.length)
        } else {
            submitAsyncWritev()
        }
    }

    /**
     * Submits all pending writes as a single WRITEV SQE via callback.
     *
     * On partial writev, fully-written buffers are released and the remainder
     * is retried via [submitAsyncSendSequential].
     */
    private fun submitAsyncWritev() {
        val count = pendingWrites.size
        val totalBytes = pendingWrites.sumOf { it.length }
        val writes = pendingWriteSnapshotPool.borrow(pendingWrites) // snapshot before clear

        val iovecs: kotlinx.cinterop.CPointer<io_uring.iovec>
        memScoped {
            val bases = allocArray<COpaquePointerVar>(count)
            val lens = allocArray<ULongVar>(count)
            for ((i, pw) in writes.withIndex()) {
                bases[i] = (pw.buf.unsafePointer + pw.offset)
                lens[i] = pw.length.convert()
            }
            iovecs = io_uring.keel_alloc_iovec(bases.reinterpret(), lens.reinterpret(), count)
                ?: run {
                    for (pw in writes) pw.buf.release()
                    pendingWriteSnapshotPool.recycle(writes)
                    error("keel_alloc_iovec failed (OOM)")
                }
        }

        fixedOpSubmitted()
        eventLoop.submitWritevCallback(sqeFd, iovecs, count.toUInt(), fixedFile = useFixedFile) { res ->
            fixedOpCompleted()
            io_uring.keel_free_iovec(iovecs)
            // Surface writev errors (EPIPE / ECONNRESET / etc.) — without
            // this the partial-write fall-through would treat a negative
            // res as `writtenBytes = 0` and either resubmit the same
            // payload (eventual cancel / orphan) or fall into the
            // "all buffers fully written" guard. Same shape as the
            // submitAsyncSendSequential fix below.
            if (res < 0) {
                eventLoop.logger.warn {
                    "async writev CQE failed: sqeFd=$sqeFd res=$res (${errnoMessage(-res)})"
                }
                for (pw in writes) pw.buf.release()
                pendingWriteSnapshotPool.recycle(writes)
                onAsyncFlushDone()
                fireReadClosedOnce()
                return@submitWritevCallback
            }
            val writtenBytes = res
            if (writtenBytes >= totalBytes) {
                for (pw in writes) pw.buf.release()
                pendingWriteSnapshotPool.recycle(writes)
                onAsyncFlushDone()
            } else {
                // Partial writev: release fully-written, retry remainder sequentially.
                var consumed = 0
                var splitIndex = -1
                for ((i, pw) in writes.withIndex()) {
                    if (consumed + pw.length <= writtenBytes) {
                        consumed += pw.length
                        pw.buf.release()
                    } else {
                        splitIndex = i
                        break
                    }
                }
                if (splitIndex < 0) {
                    // All buffers fully written (shouldn't happen, but safe)
                    pendingWriteSnapshotPool.recycle(writes)
                    onAsyncFlushDone()
                } else {
                    // Send remaining buffers sequentially via SEND chain.
                    // Ownership of `writes` transfers to submitAsyncWritevRemainder,
                    // which recycles it once the chain completes.
                    submitAsyncWritevRemainder(writes, splitIndex, writtenBytes - consumed)
                }
            }
        }
    }

    /**
     * Sends remaining buffers from a partial writev sequentially.
     *
     * The split buffer (at [splitIndex]) may be partially written;
     * [alreadySent] bytes are skipped. Subsequent buffers are sent in full.
     * [onAsyncFlushDone] is called after the last buffer completes.
     */
    private fun submitAsyncWritevRemainder(
        writes: ArrayList<PendingWrite>,
        splitIndex: Int,
        alreadySent: Int,
    ) {
        val pw = writes[splitIndex]
        val offset = pw.offset + alreadySent.coerceAtLeast(0)
        val length = pw.length - alreadySent.coerceAtLeast(0)
        submitAsyncSendSequential(pw.buf, offset, length) {
            // Send remaining buffers after the split point.
            val nextIndex = splitIndex + 1
            if (nextIndex >= writes.size) {
                pendingWriteSnapshotPool.recycle(writes)
                onAsyncFlushDone()
            } else {
                submitAsyncWritevRemainderFrom(writes, nextIndex)
            }
        }
    }

    /**
     * Sends buffers from [startIndex] to end sequentially.
     * Each buffer starts after the previous completes.
     */
    private fun submitAsyncWritevRemainderFrom(writes: ArrayList<PendingWrite>, startIndex: Int) {
        if (startIndex >= writes.size) {
            pendingWriteSnapshotPool.recycle(writes)
            onAsyncFlushDone()
            return
        }
        val pw = writes[startIndex]
        submitAsyncSendSequential(pw.buf, pw.offset, pw.length) {
            submitAsyncWritevRemainderFrom(writes, startIndex + 1)
        }
    }

    // --- SEND_ZC mode: zero-copy send via 2-CQE callback ---

    /**
     * Submits all pending writes as sequential SEND_ZC SQEs.
     *
     * Each buffer is sent individually. For gather + zero-copy, use
     * [flushSendmsgZc] (SENDMSG_ZC, kernel 6.1+).
     */
    private fun flushSendZc() {
        asyncFlushesInFlight++
        asyncPendingFlushBytes += pendingWrites.sumOf { it.length }
        // Snapshot before submitting: flush() clears pendingWrites the moment
        // this returns, but the chain advances asynchronously across CQE
        // callbacks and must keep iterating the buffers captured here (the
        // same ownership-snapshot contract the writev remainder path uses).
        submitAsyncSendZcChain(pendingWriteSnapshotPool.borrow(pendingWrites), 0)
    }

    /**
     * Submits [writes] from [index] as sequential SEND_ZC SQEs.
     *
     * Each buffer is sent after the previous fully completes, preserving
     * TCP byte-stream order. [onAsyncFlushDone] is called after the last buffer
     * and the snapshot is recycled.
     */
    private fun submitAsyncSendZcChain(writes: ArrayList<PendingWrite>, index: Int) {
        if (index >= writes.size) {
            pendingWriteSnapshotPool.recycle(writes)
            onAsyncFlushDone()
            return
        }
        val pw = writes[index]
        submitAsyncSendZcSequential(pw.buf, pw.offset, pw.length) {
            submitAsyncSendZcChain(writes, index + 1)
        }
    }

    /**
     * Submits a single SEND_ZC SQE and invokes [onComplete] after all bytes are sent.
     *
     * Handles partial sends by recursively submitting for the remainder.
     * Buffer is released after completion.
     */
    private fun submitAsyncSendZcSequential(
        buf: IoBuf,
        offset: Int,
        length: Int,
        onComplete: () -> Unit,
    ) {
        val ptr = (buf.unsafePointer + offset)!!
        val bufIndex = registeredBufferTable.indexOf(buf.unsafePointer)
        if (bufIndex >= 0) {
            // Registered buffer: use SEND_ZC_FIXED (no per-send page pinning).
            eventLoop.sendZcFixedCount++
            fixedOpSubmitted()
            eventLoop.submitSendZcFixedCallback(
                sqeFd,
                ptr,
                length.convert(),
                MSG_NOSIGNAL,
                bufIndex = bufIndex,
                fixedFile = useFixedFile,
            ) { res ->
                fixedOpCompleted()
                if (res < 0) {
                    eventLoop.logger.warn {
                        "async SEND_ZC_FIXED CQE failed: sqeFd=$sqeFd res=$res (${errnoMessage(-res)})"
                    }
                    buf.release()
                    onComplete()
                    fireReadClosedOnce()
                    return@submitSendZcFixedCallback
                }
                val sent = res
                val remaining = length - sent
                // Guard res > 0: res < 0 is handled above, so res here is 0 or positive.
                // A res == 0 completion on a still-unsent buffer must NOT resubmit the same
                // SQE — that spins the EventLoop forever (and stalls every connection on it).
                // Mirror submitAsyncSendSequential's res > 0 guard: treat res == 0 as done
                // (release + complete), same as a fully-sent buffer.
                if (remaining > 0 && res > 0) {
                    submitAsyncSendZcSequential(buf, offset + sent, remaining, onComplete)
                } else {
                    buf.release()
                    onComplete()
                }
            }
        } else {
            // Unregistered buffer: use regular SEND_ZC (per-send page pinning).
            eventLoop.sendZcRegularCount++
            fixedOpSubmitted()
            eventLoop.submitSendZcCallback(
                sqeFd,
                ptr,
                length.convert(),
                MSG_NOSIGNAL,
                fixedFile = useFixedFile,
            ) { res ->
                fixedOpCompleted()
                if (res < 0) {
                    eventLoop.logger.warn {
                        "async SEND_ZC CQE failed: sqeFd=$sqeFd res=$res (${errnoMessage(-res)})"
                    }
                    buf.release()
                    onComplete()
                    fireReadClosedOnce()
                    return@submitSendZcCallback
                }
                val sent = res
                val remaining = length - sent
                // Guard res > 0: res < 0 is handled above, so res here is 0 or positive.
                // A res == 0 completion on a still-unsent buffer must NOT resubmit the same
                // SQE — that spins the EventLoop forever (and stalls every connection on it).
                // Mirror submitAsyncSendSequential's res > 0 guard: treat res == 0 as done
                // (release + complete), same as a fully-sent buffer.
                if (remaining > 0 && res > 0) {
                    submitAsyncSendZcSequential(buf, offset + sent, remaining, onComplete)
                } else {
                    buf.release()
                    onComplete()
                }
            }
        }
    }

    // --- SENDMSG_ZC mode: gather write + zero-copy via msghdr ---

    /**
     * Submits all pending writes as a single SENDMSG_ZC SQE (gather + zero-copy).
     *
     * For a single buffer, falls back to [flushSendZc] (SEND_ZC) to avoid
     * msghdr allocation overhead.
     *
     * The msghdr and iovec array are heap-allocated and freed after the
     * second CQE (notification) arrives.
     */
    private fun flushSendmsgZc() {
        asyncFlushesInFlight++
        val totalBytes = pendingWrites.sumOf { it.length }
        asyncPendingFlushBytes += totalBytes

        if (pendingWrites.size == 1) {
            // Single buffer: use SEND_ZC directly (no msghdr overhead).
            val pw = pendingWrites[0]
            submitAsyncSendZcSequential(pw.buf, pw.offset, pw.length) {
                onAsyncFlushDone()
            }
            return
        }

        // Gather: build iovec + msghdr, submit SENDMSG_ZC.
        val writes = pendingWriteSnapshotPool.borrow(pendingWrites)
        val count = writes.size

        memScoped {
            val bases = allocArray<COpaquePointerVar>(count)
            val lens = allocArray<ULongVar>(count)
            for ((i, pw) in writes.withIndex()) {
                bases[i] = (pw.buf.unsafePointer + pw.offset)
                lens[i] = pw.length.convert()
            }
            val iovecs = io_uring.keel_alloc_iovec(bases.reinterpret(), lens.reinterpret(), count)
                ?: run {
                    for (pw in writes) pw.buf.release()
                    pendingWriteSnapshotPool.recycle(writes)
                    error("keel_alloc_iovec failed (OOM)")
                }
            val msghdr = io_uring.keel_alloc_msghdr(iovecs, count)
                ?: run {
                    io_uring.keel_free_iovec(iovecs)
                    for (pw in writes) pw.buf.release()
                    pendingWriteSnapshotPool.recycle(writes)
                    error("keel_alloc_msghdr failed (OOM)")
                }

            fixedOpSubmitted()
            eventLoop.submitSendmsgZcCallback(sqeFd, msghdr, MSG_NOSIGNAL.convert(), fixedFile = useFixedFile) { res ->
                fixedOpCompleted()
                io_uring.keel_free_msghdr(msghdr)
                io_uring.keel_free_iovec(iovecs)
                for (pw in writes) pw.buf.release()
                pendingWriteSnapshotPool.recycle(writes)
                // Surface SENDMSG_ZC errors (EPIPE / ECONNRESET / etc.) — without
                // this, a broken stream silently completed the flush from the
                // pipeline's perspective, leaving the orphaned transport alive
                // and the upstream codec convinced its bytes landed.
                if (res < 0) {
                    eventLoop.logger.warn {
                        "async SENDMSG_ZC CQE failed: sqeFd=$sqeFd res=$res (${errnoMessage(-res)})"
                    }
                    onAsyncFlushDone()
                    fireReadClosedOnce()
                    return@submitSendmsgZcCallback
                }
                // Partial sendmsg is not retried — TCP guarantees in-order delivery,
                // and partial sendmsg on a stream socket is uncommon (only under
                // extreme memory pressure). If it occurs, the connection will be
                // closed by the peer detecting missing data.
                onAsyncFlushDone()
            }
        }
    }

    // --- Await pending async flush (Coroutine mode) ---

    /**
     * Number of async send chains submitted but not yet completed.
     *
     * A count rather than a flag because [flush] has no in-flight guard: a
     * write arriving while an earlier chain is outstanding submits a second
     * one, and the first completion must not report the whole flush done.
     * [outputDrained] gates the half-close FIN on this and [awaitPendingFlush]
     * resumes on it, so a flag would let both run while bytes are still in the
     * ring. The sibling [asyncPendingFlushBytes] already accumulates with `+=`
     * for the same reason.
     */
    private var asyncFlushesInFlight = 0

    /**
     * Says a waiter's dispatcher refused its resume. The list, the park, the
     * snapshot and the guarded resume are the base's now -- three transports
     * carried the same copy -- and this is the half that needs a logger.
     */
    override fun reportFlushWaiterResumeRefused(refusal: Throwable) {
        eventLoop.logger.error(refusal) {
            "resuming a drained flush waiter threw; nothing can reach that waiter, the rest go on"
        }
    }

    /**
     * Called when an async send chain completes. Reports the flush done —
     * resuming the Coroutine mode continuation, invoking [onFlushComplete] and
     * releasing a deferred half-close FIN — only once the last outstanding
     * chain has finished.
     */
    private fun onAsyncFlushDone() {
        asyncFlushesInFlight--
        if (asyncFlushesInFlight > 0) return
        val flushed = asyncPendingFlushBytes
        asyncPendingFlushBytes = 0
        updatePendingBytes(-flushed)
        resumeFlushWaiters(takeFlushWaiters())
        onFlushComplete?.invoke()
        sendFinIfDrained()
    }

    /**
     * Suspends until all pending async flush operations complete.
     *
     * Dispatches the check+register lambda to the EventLoop so the
     * [asyncFlushesInFlight] check and the waiter store are atomic
     * with the write-CQE handler that decrements it. If the flush already
     * completed before the lambda executes, [cont] is resumed immediately
     * rather than stored, avoiding a TOCTOU deadlock.
     */
    override suspend fun awaitPendingFlush() {
        suspendCancellableCoroutine { cont ->
            val register = Runnable {
                when {
                    !opened -> cont.cancel()
                    asyncFlushesInFlight == 0 -> cont.resume(Unit)
                    else -> {
                        parkFlushWaiter(cont)
                    }
                }
            }
            if (eventLoop.inEventLoop()) {
                register.run()
            } else {
                eventLoop.dispatch(EmptyCoroutineContext, register)
            }
        }
    }

    override fun close() {
        if (!markClosing()) return
        if (eventLoop.inEventLoop()) {
            teardownOnEventLoop()
        } else {
            // Channel.close() is non-suspend and may be invoked from any thread.
            // Dispatch the EventLoop-bound teardown (cancelSqe, fixed-file
            // unregister, fd close) onto the owning EventLoop. Fire-and-forget:
            // pending close tasks are drained at the top of each loop iteration,
            // so the ring is never torn down before its channel teardown runs.
            //
            // Concurrent close() callers may both pass `markClosing()` under
            // rare races and each enqueue a teardown task; `markTeardownStarted`
            // inside `teardownOnEventLoop` keeps the cleanup idempotent on the
            // EventLoop thread.
            eventLoop.dispatch(EmptyCoroutineContext) {
                teardownOnEventLoop()
            }
        }
    }

    private fun teardownOnEventLoop() {
        if (!markTeardownStarted()) return
        cancelIdleTimeout()
        cancelWriteIdleTimeout()
        if (recvSlot >= 0) {
            // Keep the recv callback alive across the cancel: the
            // allocator-buffer fallback's in-flight buffer may only be
            // released by its terminal CQE (the kernel can still complete
            // the recv with data before the cancellation lands), and that
            // release lives in the kept callback's `!opened` branch. The
            // ring modes' callbacks are post-teardown-safe too (they
            // early-return on `!opened`).
            eventLoop.cancelSqeKeepCallback(recvSlot)
            recvSlot = -1
        }
        // Cancel the peer-FIN-watching POLL_ADD before closing the fd.
        // An in-flight POLL_ADD SQE holds a kernel-side `struct file`
        // reference to the watched socket; userspace `close(fd)` drops the
        // fd table entry but the kernel keeps the socket alive until every
        // pending io_uring op completes. For TCP sockets this defers FIN
        // emission until cancellation, so peer-side `POLLRDHUP` never fires
        // on the connected partner — manifesting as `onReadClosed` not
        // firing within the test/timeout window. Cancelling the SQE
        // unwinds the kernel reference; the subsequent `close(fd)` is then
        // the last reference and the TCP stack emits FIN promptly.
        if (pollAddFinSlot >= 0) {
            eventLoop.cancelSqe(pollAddFinSlot)
            pollAddFinSlot = -1
        }
        for (pw in pendingWrites) pw.buf.release()
        pendingWrites.clear()
        pendingBytes = 0
        asyncPendingFlushBytes = 0
        // Unblock every caller suspended in awaitPendingFlush(): the data is
        // gone. Guarded per waiter -- a cancel's refusal must not strand the
        // waiters behind it in the already-taken snapshot, nor abort the
        // teardown stages after this (the fd close among them).
        for (cont in takeFlushWaiters()) {
            try {
                cont.cancel()
            } catch (refusal: Throwable) {
                eventLoop.logger.error(refusal) {
                    "cancelling a flush waiter on teardown threw; nothing can reach that waiter, the teardown goes on"
                }
            }
        }
        if (fixedFileIndex >= 0) {
            if (inflightFixedOps == 0) {
                fixedFileRegistry?.unregister(fixedFileIndex)
            } else {
                // SQEs referencing the slot are still in flight (e.g. the
                // just-cancelled recv whose terminal CQE has not landed).
                // Unregistering now would free the slot for the next
                // connection while the kernel can still resolve the stale
                // request against it — defer to the last terminal CQE.
                fixedSlotReleasePending = true
            }
        }
        // Direct-allocated slots: unregister() above issues
        // register_files_update(slot, -1) which closes the kernel-held fd.
        // There is no userspace fd to close via POSIX close().
        if (!useDirectAlloc) {
            closeFdSafely(fd, eventLoop.logger, "transport teardown")
        }
    }
}
