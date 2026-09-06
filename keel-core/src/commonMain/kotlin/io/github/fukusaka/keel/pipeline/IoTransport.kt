package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.IoBuf
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Transport-layer I/O operations for a single TCP connection.
 *
 * Owns the underlying file descriptor / socket / connection object and
 * provides read, write, shutdown, and lifecycle management. Engine
 * implementations extract all platform-specific I/O logic into an
 * IoTransport so that [AbstractPipelinedChannel] and [HeadHandler] use
 * the same code path across all engines.
 *
 * ```
 * Read path (transport → pipeline):
 *   readEnabled = true → platform event registration
 *   → data arrives → onRead(buf)
 *   → EOF (peer FIN)              → onReadClosed()
 *   → error / reset / idle / stop → onClosed()
 *
 * Write path (pipeline → transport):
 *   write(buf) → flush() → platform syscall
 *   → EAGAIN → async retry → onFlushComplete()
 * ```
 *
 * All methods must be called on the [ioDispatcher] thread unless
 * otherwise noted.
 *
 * Pure interface — no default implementations. Use [AbstractIoTransport]
 * for shared defaults (awaitPendingFlush, awaitClosed, callback properties).
 *
 * ## Read Path
 *
 * Set [onRead], [onReadClosed] and [onClosed] callbacks, then set
 * [readEnabled] to `true` to start the read loop. The transport handles
 * buffer allocation, platform syscalls (or async callbacks), EAGAIN retry,
 * and automatic re-arming internally. The peer's end of file is reported
 * via [onReadClosed]; a connection the transport ends itself — an error, a
 * reset, an idle reclamation, a stopped loop — via [onClosed].
 *
 * ## Write Backpressure
 *
 * [isWritable] tracks whether the transport can accept more writes without
 * excessive buffering. [AbstractIoTransport] maintains a pending byte
 * counter incremented on [write] and decremented on flush completion.
 * When pending bytes reach [DEFAULT_HIGH_WATER_MARK], [isWritable]
 * becomes false; when they drop below [DEFAULT_LOW_WATER_MARK], it
 * becomes true again.
 */
interface IoTransport {

    // === Read path ===

    /**
     * Callback invoked when inbound data arrives.
     *
     * The transport allocates the buffer, fills it with received data,
     * and invokes this callback. The callback takes ownership of the
     * buffer (must release it when done). After invoking [onRead], the
     * transport automatically re-arms for the next read.
     *
     * Set before [readEnabled] = true. Not called after [close].
     */
    var onRead: ((IoBuf) -> Unit)?

    /**
     * Callback invoked when the transport has finished delivering the reads
     * it had for one readiness or completion event.
     *
     * The batch boundary, and the reason a handler can answer a burst with
     * one flush instead of one per message: it is told when there is no more
     * to come *for now*, which is what "for now" means on each engine — the
     * reads drained from a single wake, a single completion, or a single
     * framework callback.
     *
     * Only Netty's boundary closes a loop that ran more than once; the others
     * read once per event, so their boundary follows a single [onRead]. The
     * saved flushes come from the same place on all of them: one read carries
     * however many messages the peer pipelined into it, and a decoder turns
     * that into many `onRead` calls downstream under one boundary.
     *
     * A hint, not a frame delimiter, and the guarantees are correspondingly
     * thin. It arrives after the reads of its own batch, and that is all a
     * handler may lean on:
     *
     * - **It can arrive with nothing before it.** Netty ends every read cycle
     *   this way, including a cycle whose first read returned no bytes, so a
     *   connection that is woken and finds nothing still reports a boundary.
     * - **It can arrive after the channel has ended.** A handler that closes
     *   from inside [onRead] gets the inactive first and this afterwards,
     *   because a transport announces the end of the batch it was in the
     *   middle of delivering.
     * - **A transport that cannot tell one batch from the next need not send
     *   it at all.** A handler with no boundary flushes per message, which is
     *   what every handler does today.
     *
     * `PipelineHandler.onReadComplete` and `Pipeline.notifyReadComplete` have
     * existed for this since the pipeline was written; nothing sent it.
     */
    var onReadComplete: (() -> Unit)?

    /**
     * Pauses inbound consumption for flow control (read-side
     * back-pressure). Contract for every engine: stop consuming new
     * bytes from the underlying source within a bounded overshoot (at
     * most the in-flight delivery), so the kernel/framework receive
     * buffer fills and TCP flow control reaches the peer. No data may
     * be dropped. Peer-FIN detection may be delayed while paused.
     *
     * Distinct from [readEnabled], which expresses read *interest* and
     * is mediated by [io.github.fukusaka.keel.core.IdleReadPolicy]
     * (under `DETECT_PEER_CLOSE` the read primitive stays armed when
     * `readEnabled = false`); [pauseReads] must stop consumption
     * regardless of the policy. The default delegates to
     * `readEnabled = false`, which is the correct pause on engines
     * whose disabled read already stops consuming; engines where it
     * does not (policy-armed push engines) override with a real
     * disarm. Must be called on the transport's I/O thread.
     */
    fun pauseReads() {
        readEnabled = false
    }

    /**
     * Resumes inbound consumption after [pauseReads]. Must be called on
     * the transport's I/O thread.
     */
    fun resumeReads() {
        readEnabled = true
    }

    /**
     * Callback invoked when the peer has closed its side for writing — an
     * orderly end of file, and nothing else.
     *
     * After this callback no further [onRead] call occurs; the connection is
     * still open and still writable, so the owner can answer a peer that
     * half-closed. At most once. The transport does NOT call [close] — the
     * callback owner decides whether the whole connection ends.
     *
     * A connection that ends for any other reason — a reset, a failed read
     * or write, an idle reclamation, a loop that stopped — is reported by
     * [onClosed] instead, not here: the two are different facts to a
     * listener (one can still be answered, the other cannot), and a transport
     * that folded them together left its listener unable to tell a peer that
     * finished sending from a peer that is gone.
     */
    var onReadClosed: (() -> Unit)?

    /**
     * Callback invoked when this transport has ended the connection on its
     * own: a reset or a failed read, a write the platform refused, an idle
     * timeout that reclaimed it, a loop that stopped under it. At most once,
     * and only for an end the transport forced — a close the caller asked
     * for is not reported, since the caller already knows.
     *
     * After this callback nothing is delivered and nothing can be written;
     * the owner's only remaining move is [close], which the transport does
     * not call for it. May follow [onReadClosed] — a peer that finished
     * sending and then went away, or a half-closed connection the idle
     * timeout reclaimed — and may arrive without it. A refused send is
     * reported to [onConnectionFailure] first, with the reason.
     */
    var onClosed: (() -> Unit)?

    /**
     * Whether this transport still reports every end the way it did before
     * the peer's end of file became an event of its own: one report, on
     * [onReadClosed], for a FIN and for a reset and for a failure alike.
     *
     * A channel reads such a report as the ending it was, so a transport
     * that has not been taught the difference behaves exactly as it did —
     * and one that has (it reports the peer's end of file with
     * `reportReadClosedOnce` and every other end with `reportEndOnce`)
     * leaves this `false` and its channel keeps the two apart. It goes when
     * the last transport has learned.
     */
    val reportsEveryEndAsReadClosed: Boolean get() = false

    /**
     * Callback invoked when this transport ends the connection over a
     * refused send, with that refusal — before [onClosed], so a listener
     * hears the reason while it can still act on it, and at most once. The
     * other failures that end a connection do so without it, and deliberately.
     * One kind has usually just come from the handler chain, so sending it
     * back down invites an answer that throws again. The other -- a read the
     * platform refuses -- is what the inactive report itself is for, and a
     * second notification saying the same thing is not a reason a handler can
     * act on. A caller waiting on a flush is told about both.
     *
     * Only for an end the transport forced. A close the caller asked for is
     * not reported here even when the closing drain meets a dead peer: the
     * caller asked for the queue to be discarded, and a peer found gone
     * while discarding is the outcome it asked for. Nor is a refusal met
     * after [onClosed] already went out — the connection can end first —
     * since a reason delivered after the end reaches nobody who can act on
     * it; the flush wait is still answered with it.
     *
     * The default accessors store nothing, so a transport that never raises
     * [io.github.fukusaka.keel.core.RefusedWriteException] is not obliged to
     * carry a field for it. Overriding with real storage is part of adopting
     * that failure, not an option alongside it.
     */
    var onConnectionFailure: ((Throwable) -> Unit)?
        get() = null
        set(@Suppress("UNUSED_PARAMETER") value) {}

    /**
     * Hook invoked by [io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel]
     * after [onRead], [onReadClosed], [onClosed] and [onWritabilityChanged]
     * have all been wired up. Engines that pre-arm their read primitive when the
     * caller selects [io.github.fukusaka.keel.core.IdleReadPolicy.DETECT_PEER_CLOSE]
     * should arm here (rather than in `init { }`) so any inbound bytes
     * the platform delivers via the read primitive's first event always
     * observe a non-null [onRead] — arming in `init { }` exposes a race
     * with the channel-construction sequence (`Engine.IoTransport(...)` →
     * `Engine.PipelinedChannel(...)` → `AbstractPipelinedChannel.init`)
     * where the platform's EventLoop / dispatch queue can deliver the
     * first read event before the channel constructor has finished
     * setting the callbacks.
     *
     * Also where the POSIX engines join their EventLoop's participant
     * registry. That registry decides who is told the loop stopped, and each
     * participant is told once — so joining before the callbacks exist spends
     * that one notification on a null and the connection never learns. For
     * those engines this hook is load-bearing, not optional.
     *
     * Default no-op for engines that arm read lazily on `readEnabled =
     * true` (the [io.github.fukusaka.keel.core.IdleReadPolicy.PRESERVE_BACKPRESSURE]
     * path) or that already deliver `onReadClosed` through a separate
     * channel (engine-nodejs / native netty transports).
     */
    fun onChannelAttached() {}

    /**
     * Enables or disables the read loop.
     *
     * When set to `true`, the transport registers platform-specific read
     * interest (kqueue EVFILT_READ, epoll EPOLLIN, NIO OP_READ, io_uring
     * multishot RECV, etc.) and starts delivering data via [onRead].
     *
     * When set to `false`, the transport deregisters read interest. Useful
     * for backpressure: stop reading when the pipeline is overloaded.
     *
     * Initial value: `false` (read loop not started until explicitly enabled).
     */
    var readEnabled: Boolean

    // === Write path ===

    /**
     * Buffers [buf] for a subsequent [flush].
     *
     * **Ownership (transfer)**: takes over the caller's reference. The caller
     * must not touch [buf] after this call returns — no further read/write,
     * no [IoBuf.release], and no index inspection. The transport releases
     * the buffer after [flush] completes (or on teardown). To keep a
     * reference alive, the caller must call [IoBuf.retain] **before** passing
     * the buffer in.
     */
    fun write(buf: IoBuf)

    /**
     * Sends all buffered writes to the network.
     *
     * @return true if the flush completed synchronously — this call's own
     *         drain emptied the queue (trivially true when nothing was
     *         pending). Bytes that completion callbacks write
     *         from inside the call are a new flush, not folded into this
     *         answer, so buffered data may be pending again by the time a
     *         `true` reaches the caller. false when the send is still
     *         pending (e.g. EAGAIN, an async submission) — implementations
     *         that always complete asynchronously always answer false.
     *         Implementations predating this rule may still answer true
     *         over a queue their own water-mark callback refilled;
     *         converging them is tracked follow-up work.
     *
     * **May raise.** A send the platform definitively refused is a failure,
     * not a completed flush, and reaches the caller as one — as does a
     * failure in the bookkeeping around it (releasing a buffer, resuming a
     * waiter). Whatever is unfinished stays queued for the close. A caller on
     * the pipeline route does not see the raise: the pipeline's head contains
     * a refused send, having already delivered it to the handlers as a
     * pipeline error — or recorded it, where they are not the ones being
     * told.
     */
    fun flush(): Boolean

    /**
     * Callback invoked when a pending flush completes.
     *
     * May fire later, from whatever completes the send — a readiness retry, a
     * scheduled drain, an I/O completion — and an implementation whose
     * [flush] drains in place may fire it synchronously, from inside that
     * call. Implementations that allow the synchronous firing must bound a
     * completion-driven pump (write the next chunk and flush from here)
     * rather than recurse through it. [AbstractPipelinedChannel] sets this and
     * routes it into the pipeline, so an implementation that fires
     * synchronously fires into handler code, and the bound above is what keeps
     * a handler that flushes from its own completion from calling itself.
     */
    var onFlushComplete: (() -> Unit)?

    /**
     * Suspends until all pending async flush operations complete.
     *
     * Returns immediately when no send is pending. Called by Coroutine
     * mode's [io.github.fukusaka.keel.core.Channel.awaitFlushComplete].
     *
     * **The wait is not exclusive.** Any number of callers may wait at once —
     * two coroutines flushing one channel overlap here naturally — and each
     * is answered: by the completion, by the failure, or by the teardown
     * that ends the wait. An implementation must not let one waiter's
     * arrival, answer, or cancellation cost another waiter its answer.
     * What *form* the answer takes — a completion, a typed failure, a bare
     * cancellation — still differs by engine, like the failure reporting
     * documented on [io.github.fukusaka.keel.core.Channel.awaitFlushComplete]:
     * a contract the implementations are converging on rather than one they
     * all meet.
     */
    suspend fun awaitPendingFlush()

    /**
     * Whether the transport can accept more writes without excessive
     * buffering.
     *
     * Becomes false when pending bytes exceed [DEFAULT_HIGH_WATER_MARK],
     * and true again when they drop below [DEFAULT_LOW_WATER_MARK].
     * Applications should check this before writing large responses.
     */
    val isWritable: Boolean

    /**
     * Callback invoked when [isWritable] changes state.
     *
     * Called with `false` when pending bytes cross [DEFAULT_HIGH_WATER_MARK]
     * (stop writing), and `true` when they drop below
     * [DEFAULT_LOW_WATER_MARK] (resume writing).
     *
     * **Where a throw from this goes.** It is user code, so it is a seam — but
     * the guard belongs to whoever set this, not to a `try` around this call,
     * and that is two different places.
     *
     * A channel with a pipeline sets it to the pipeline's own notification, and
     * a handler that throws there is caught and turned into an error event
     * travelling the pipeline — never reaching the `write` that drove it, and
     * never taking the surrounding frame with it. That is most callers, and for
     * them this seam is already contained.
     *
     * A consumer that sets it directly on an [IoTransport] gets the raw
     * behaviour, and the two directions differ. The `false` above the high-water
     * mark is raised from `write` itself, on every engine, so it propagates to
     * the caller doing the writing — which is where it belongs. The `true` below
     * the low-water mark is raised from whatever drove the drain: the two POSIX
     * readiness transports stage it, so it is carried and raised once the rest
     * of that group has run, and the others do not — there it takes the rest of
     * the frame with it, which can mean an arm not registered or a flush waiter
     * not resumed. Converging that is the same work as the rest of that
     * contract, and a handler that has to run on every engine should not throw
     * from here.
     */
    var onWritabilityChanged: ((Boolean) -> Unit)?

    // === Lifecycle ===

    /**
     * True when the caller is already on the context this transport's
     * non-suspend API must be used from — the EventLoop thread, or the serial
     * dispatch queue that stands in for one.
     *
     * Defaults to `true` so a transport that cannot tell — a test double, an
     * engine whose runtime is single-threaded — never trips a check built on
     * it. Engines that own an EventLoop override it.
     *
     * Cheap enough for the write path: a thread identity compare on the
     * thread-backed engines, a single queue-local lookup on the dispatch-queue
     * ones.
     *
     * **A transport that can return `false` must have an [ioDispatcher] that
     * accepts `dispatch`**, because that is where the pipeline routes outbound
     * work it may not run on the caller's thread. `Dispatchers.Unconfined`
     * does not qualify — it rejects `dispatch` outright — which is consistent
     * with the transports that use it never leaving the default `true`.
     */
    val inOwningContext: Boolean get() = true

    /**
     * Whether work handed to [ioDispatcher] will still run.
     *
     * `false` means the owning context has stopped for good: a `dispatch` is
     * accepted by the queue and never drained, so anything the pipeline routes
     * that way — and anything the closure captures, including a buffer whose
     * ownership was transferred — is stranded for the transport's lifetime.
     * The pipeline asks before dispatching so it can release instead.
     *
     * Default `true`, which is the *unchanged* answer rather than the correct
     * one everywhere. It is accurate for a transport whose dispatcher outlives
     * it or which never leaves [inOwningContext]; the other engines whose loops
     * can also stop while their transports stay reachable have simply not been
     * moved onto it yet, and strand the same work meanwhile. Overriding it is
     * what fixes that, engine by engine.
     *
     * This is not the negation of [isOpen]. A transport can be open with a dead
     * dispatcher (its loop stopped, nobody has closed the channel yet) — which
     * is exactly the case worth asking about.
     */
    val canDispatchToOwningContext: Boolean get() = true

    /**
     * Sends TCP FIN to the peer (half-close).
     *
     * The read side remains open so the peer's remaining data can be
     * consumed. Implementations must be idempotent.
     *
     * **Safe to call from any thread, and the FIN may be sent after this
     * returns.** Engines that own an EventLoop issue the syscall on that
     * thread, so an off-loop caller only queues the request. Observe the
     * effect through the peer, not through the call returning.
     *
     * Buffered writes are sent first: whatever [write] queued before this
     * call reaches the peer ahead of the FIN. Writes issued *after* it are
     * discarded — the caller declared it had nothing more to send.
     *
     * Ordering the FIN behind the data also makes it as slow as the data: a
     * peer that stops reading holds both back. `idleTimeoutMillis` bounds
     * that (the write-idle timer force-closes a peer that never drains) and
     * is disabled by default. A [close] before the drain finishes supersedes
     * the half-close and discards what was still queued.
     *
     * **A refused send is not raised from here.** Sending the buffered
     * writes first means this call can be the one that meets the refusal,
     * but only when the drain runs in place — an implementation that defers
     * it to a later tick, which the readiness engines do by default, meets
     * it there instead. The caller picked neither, so neither is what it
     * hears: on both paths the connection ends, no FIN follows the refused
     * bytes, and [awaitPendingFlush] is where the reason is asked for.
     *
     * Whatever else the flush throws is not contained — a drain that also
     * could not release its buffers re-raises the refusal carrying that as a
     * suppressed cause, and that cause is what leaves the half-close. Where
     * it goes follows the drain, exactly as the refusal does: this call
     * receives it only when the drain ran inside it — not when the
     * implementation defers the drain, which the readiness engines do by
     * default, and not when the half-close was handed to the transport's own
     * context and this call has already returned. The implementation reports
     * it in those cases. Unlike the refusal, this is a fault rather than an
     * answer to the caller's question, and the rule for those is to reach
     * whoever can act — never to be dropped. A failure of the wind-down that
     * follows the refusal is the exception: it happens after the refusal was
     * handed to its waiters, so it rides nothing and reaches no caller — the
     * implementation's own log is its record.
     */
    fun shutdownOutput()

    /**
     * Closes the transport and releases all resources.
     *
     * A flush already requested lands first — as much of it as the socket
     * takes at once — before the descriptor closes: a handler that answers
     * the peer's end of file from inside [onReadClosed] writes and flushes
     * on a channel that closes itself right after the call, and that answer
     * is the close's to deliver. What was only queued, never flushed, is
     * released unsent. Deregisters events and closes the underlying
     * fd/socket/connection. Implementations must be idempotent (use
     * [isOpen] flag to guard).
     */
    fun close()

    /**
     * Suspends until the transport is fully closed.
     *
     * Most transports close synchronously and return immediately.
     * Async transports (NWConnection, Netty) may need to wait for
     * pending callbacks or channel futures.
     */
    suspend fun awaitClosed()

    /**
     * Schedules [task] to run once after [delayMillis] on the owning EventLoop, for
     * codec/server-level completion deadlines (e.g. the header-complete timeout).
     * Returns a [TimerHandle] for cancellation when the phase completes in time, or
     * `null` if this transport has no EventLoop timer (test doubles, unwired engines).
     * Reuses the same per-EventLoop timer that backs the idle timeout.
     *
     * Unlike the idle timeout, this deadline is **absolute** — it is not refreshed by
     * I/O progress, so a trickle of bytes cannot defeat it (that is the point: it
     * bounds the time to *complete* a protocol phase, not the gap between bytes).
     *
     * **Thread safety**: must be called on the EventLoop thread (the pipeline handler
     * thread), like the idle-timeout arm/cancel calls.
     */
    fun scheduleDeadline(delayMillis: Long, task: () -> Unit): TimerHandle? = null

    // === Properties ===

    /**
     * The buffer allocator for this transport's I/O buffers — read buffers, and
     * any write buffers the codec layer allocates. Cheapest on the owning
     * EventLoop (with the default pooled allocator, a lock-free freelist fast
     * path); an off-EventLoop release is safe but takes the slower cross-context
     * path via the allocator's confinement.
     */
    val allocator: BufferAllocator

    /**
     * Whether the transport is open (not yet closed).
     *
     * Becomes false after [close] is called. Used as the idempotent
     * guard for [close] and as the source of truth for
     * [PipelinedChannel.isActive] / [PipelinedChannel.isOpen].
     */
    val isOpen: Boolean

    /**
     * Dispatcher for I/O operations on this transport.
     *
     * Typically the EventLoop thread that drives the engine's native I/O
     * primitive (kqueue / epoll / io_uring pthread, NIO Selector thread,
     * Netty `EventLoop`, GCD dispatch queue, Node.js event loop). Every
     * keel engine resumes coroutines on that same thread so
     * `channel.read` / `write` / `flush` do not cross threads.
     */
    val ioDispatcher: CoroutineDispatcher

    companion object {
        /** Default high water mark: 64 KB. Stop writing when this much data is buffered. */
        const val DEFAULT_HIGH_WATER_MARK = 65536

        /** Default low water mark: 32 KB. Resume writing when buffered data drops below this. */
        const val DEFAULT_LOW_WATER_MARK = 32768

        /** Default read buffer size: 8 KiB. Used by pull-model engines for read allocation. */
        const val DEFAULT_READ_BUFFER_SIZE = 8192
    }
}
