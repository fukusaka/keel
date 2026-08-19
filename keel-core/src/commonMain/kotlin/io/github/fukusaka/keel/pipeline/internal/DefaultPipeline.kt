package io.github.fukusaka.keel.pipeline.internal

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.error
import io.github.fukusaka.keel.logging.warn
import io.github.fukusaka.keel.pipeline.InboundHandler
import io.github.fukusaka.keel.pipeline.IoTransport
import io.github.fukusaka.keel.pipeline.OutboundHandler
import io.github.fukusaka.keel.pipeline.Pipeline
import io.github.fukusaka.keel.pipeline.PipelineHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import io.github.fukusaka.keel.pipeline.PipelineTypeException
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Default [Pipeline] implementation using a doubly-linked list of handler contexts.
 *
 * ```
 * head ↔ ctx1 ↔ ctx2 ↔ ... ↔ tail
 *
 * Inbound:  head → ctx1 → ctx2 → tail
 * Outbound: tail → ctx2 → ctx1 → head
 * ```
 *
 * [HeadHandler] connects the pipeline to the [IoTransport] (actual I/O).
 * [TailHandler] releases unreferenced messages and logs warnings.
 *
 * **Type chain validation**: [addLast], [addBefore], [addAfter], and [replace]
 * validate that adjacent [InboundHandler]s have compatible
 * [acceptedType]/[producedType] declarations. Validation is skipped when
 * either type is [Any] (the default).
 */
internal class DefaultPipeline(
    override val channel: PipelinedChannel,
    transport: IoTransport,
    internal val logger: Logger,
) : Pipeline {

    private val transport: IoTransport = transport

    private val head: DefaultContext = DefaultContext(this, "HEAD", HeadHandler(transport, this))
    private val tail: DefaultContext = DefaultContext(this, "TAIL", TailHandler(logger))

    /**
     * Dispatcher captured from the underlying [IoTransport]. Used by the
     * pre-attach event journal to schedule [drainPreAttachJournal] on the
     * next dispatcher tick after the first user [InboundHandler] is
     * added — see the [PreAttachJournal] doc below for the rationale.
     */
    private val ioDispatcher: CoroutineDispatcher = transport.ioDispatcher

    /**
     * Runs [block] on the transport's owning context: inline when the caller
     * is already there, dispatched otherwise.
     *
     * Outbound work touches state only the owning context may touch — the
     * transport's `pendingWrites` deque above all — so an off-context caller
     * cannot be allowed to walk the chain itself. Netty answers the same
     * question the same way (`AbstractChannelHandlerContext.write` runs inline
     * when `executor.inEventLoop()` and queues a task otherwise), and it is
     * the shape keel already uses for `close` / `shutdownOutput` at the engine
     * layer. Enforcing the old "caller must already be on the EventLoop"
     * contract instead would have turned a silent corruption into a crash
     * without making any caller correct.
     *
     * Returns `false` when the transport is already closed and [block] was
     * abandoned, so a caller that transferred buffer ownership can release it
     * rather than leak it. A boolean rather than a drop-handler lambda because
     * the handler would be allocated on the fast path too; the dispatched
     * closure is built only in the branch that uses it.
     *
     * A stopped owning context is asked about separately from a closed
     * transport: the two are not the same state, and the one that strands work
     * is a *live* transport whose dispatcher has stopped — its queue accepts
     * the task and nothing ever drains it.
     *
     * The `false` return does not close the window entirely: a transport that
     * closes, or whose loop stops, *after* the dispatch still leaves the task
     * queued.
     */
    private inline fun onOwningContext(crossinline block: () -> Unit): Boolean {
        if (transport.inOwningContext) {
            block()
            return true
        }
        if (!transport.isOpen || !transport.canDispatchToOwningContext) return false
        ioDispatcher.dispatch(EmptyCoroutineContext) { block() }
        return true
    }

    /**
     * Tracks whether [notifyInactive] has been observed at the pipeline level.
     *
     * Set once on the first [notifyInactive] call. Used by [callHandlerAdded]
     * to replay [PipelineHandler.onInactive] to handlers installed *after*
     * the inactivation event so engine-driven peer-FIN detection (kqueue
     * `EV_EOF`, epoll `EPOLLRDHUP`, etc.) does not race with the lazy
     * install of [io.github.fukusaka.keel.pipeline.SuspendBridgeHandler]
     * inside [io.github.fukusaka.keel.pipeline.PipelinedChannel.read].
     *
     * Single-threaded read/write on the EventLoop thread, so no `@Volatile`
     * is required (pipeline composition + lifecycle dispatch are both
     * EventLoop-thread-only by contract).
     */
    private var inactiveObserved: Boolean = false

    /**
     * Lifecycle "head fired" flags — track whether each lifecycle
     * event has actually propagated through the inbound chain
     * (distinct from "the engine reported the event", which is
     * recorded by [inactiveObserved] / [pendingActive] / etc.).
     * Per-handler lifecycle replay in [callHandlerAdded] fires only
     * when the corresponding "fired" flag is true — i.e. the event
     * has already swept the chain and the late-added handler genuinely
     * missed it. When an event arrives during the pre-attach window
     * (observed but [drainPreAttachJournal] has not yet fired through
     * head), the drain will deliver it via the now-assembled chain,
     * so per-handler replay must skip to avoid double-firing.
     *
     * [writabilityCurrent] is the latest value seen by head — kept
     * because writability is stateful, so a late handler needs to
     * receive the *current* boolean rather than a replay of every
     * past transition.
     */
    private var activeFired: Boolean = false
    private var inactiveFired: Boolean = false
    private var writabilityCurrent: Boolean? = null

    /**
     * Pre-attach event journal: holds inbound events that arrive before
     * the pipeline acquires its first user [InboundHandler] and replays
     * them once such a handler is installed.
     *
     * **Why**: when an engine arms its read primitive eagerly (e.g.
     * `IdleReadPolicy.DETECT_PEER_CLOSE` on `engine-nio` /
     * `engine-netty` NIO fallback / `engine-nwconnection`, where the
     * underlying API forces an active read to observe peer FIN), bytes
     * the peer sends between channel construction and the first
     * [PipelinedChannel.ensureBridge] / `pipeline.addLast` call would
     * otherwise reach [TailHandler.onRead] and be released with a `WARN`
     * log. The journal captures those events and drains them onto the
     * (now fully-constructed) pipeline after the user's synchronous
     * setup block completes.
     *
     * **Drain timing — dispatcher tick, not first addX**: a synchronous
     * codec-stack setup typically calls `addLast(decoder)`,
     * `addLast(aggregator)`, `addLast(handler)` back-to-back. Draining
     * the journal on the *first* `addX` would replay events through a
     * partial pipeline (decoder → tail), bypassing aggregator and
     * handler that are added afterwards in the same call site. To avoid
     * this, the first user-inbound `addX` schedules the drain via
     * [ioDispatcher] (`dispatch(EmptyCoroutineContext, Runnable {
     * drainPreAttachJournal() })`); the current synchronous block
     * (containing the remaining `addX` calls) runs to completion before
     * the dispatcher picks up the drain task, so replay sees the fully
     * assembled handler chain. This is the keel equivalent of Netty's
     * `ChannelInitializer` deferred-event model.
     *
     * **Per-event replay strategy**:
     * - `notifyActive` → flag (idempotent).
     * - `notifyRead(msg)` → bounded queue (each message matters; cap at
     *   [MAX_PRE_ATTACH_READS] elements; overflow releases the oldest
     *   and logs `WARN` — overflow indicates the user's handler-add
     *   path is too slow relative to peer write rate).
     * - `notifyReadComplete` → flag (consecutive completes coalesce
     *   into one drain-time invocation).
     * - `notifyWritabilityChanged(b)` → latest-only (stateful: only
     *   the most recent value is meaningful).
     * - `notifyError(cause)` → bounded queue (errors retained;
     *   [MAX_PRE_ATTACH_ERRORS] cap protects against pathological
     *   error storms).
     * - `notifyUserEvent(event)` → bounded queue ([MAX_PRE_ATTACH_USER_EVENTS]).
     * - `notifyInactive` → reuses the existing [inactiveObserved] flag;
     *   per-handler replay through [callHandlerAdded] continues to
     *   apply. Drain time invokes `head.invokeOnInactive` so the entire
     *   chain processes the event, not just the first handler.
     *
     * **Single-thread invariant**: all journal mutations happen on the
     * EventLoop thread (engine `notifyXxx` callbacks and pipeline
     * `addX` are both EventLoop-bound by contract). The drain task is
     * dispatched onto the same dispatcher, so it runs on the EventLoop
     * thread serially with subsequent events.
     */
    private var drainScheduled: Boolean = false
    private var preAttachJournalDrained: Boolean = false

    private val pendingReads: ArrayDeque<Any> = ArrayDeque()
    private var pendingActive: Boolean = false
    private var pendingReadComplete: Boolean = false
    private var pendingWritability: Boolean? = null
    private val pendingUserEvents: ArrayDeque<Any> = ArrayDeque()
    private val pendingErrors: ArrayDeque<Throwable> = ArrayDeque()

    init {
        head.next = tail
        tail.prev = head
    }

    override val isEmpty: Boolean get() = head.next === tail

    // --- Composition ---

    override fun addFirst(name: String, handler: PipelineHandler): Pipeline {
        checkDuplicateName(name)
        val newCtx = DefaultContext(this, name, handler)
        val after = head.next!!
        validateInboundTypeChain(head.handler, handler, name)
        validateInboundTypeChain(handler, after.handler, after.name)
        insertBetween(head, newCtx, after)
        callHandlerAdded(newCtx)
        return this
    }

    override fun addLast(name: String, handler: PipelineHandler): Pipeline {
        checkDuplicateName(name)
        val newCtx = DefaultContext(this, name, handler)
        val before = tail.prev!!
        validateInboundTypeChain(before.handler, handler, name)
        validateInboundTypeChain(handler, tail.handler, "TAIL")
        insertBetween(before, newCtx, tail)
        callHandlerAdded(newCtx)
        return this
    }

    override fun addBefore(baseName: String, name: String, handler: PipelineHandler): Pipeline {
        val base = getContext(baseName)
        checkDuplicateName(name)
        val newCtx = DefaultContext(this, name, handler)
        val before = base.prev!!
        validateInboundTypeChain(before.handler, handler, name)
        validateInboundTypeChain(handler, base.handler, baseName)
        insertBetween(before, newCtx, base)
        callHandlerAdded(newCtx)
        return this
    }

    override fun addAfter(baseName: String, name: String, handler: PipelineHandler): Pipeline {
        val base = getContext(baseName)
        checkDuplicateName(name)
        val newCtx = DefaultContext(this, name, handler)
        val after = base.next!!
        validateInboundTypeChain(base.handler, handler, name)
        validateInboundTypeChain(handler, after.handler, after.name)
        insertBetween(base, newCtx, after)
        callHandlerAdded(newCtx)
        return this
    }

    override fun remove(name: String): PipelineHandler {
        val ctx = getContext(name)
        val prev = ctx.prev!!
        val next = ctx.next!!
        validateInboundTypeChain(prev.handler, next.handler, next.name)
        prev.next = next
        next.prev = prev
        ctx.prev = null
        ctx.next = null
        callHandlerRemoved(ctx)
        return ctx.handler
    }

    override fun replace(oldName: String, newName: String, newHandler: PipelineHandler): PipelineHandler {
        val oldCtx = getContext(oldName)
        if (oldName != newName) checkDuplicateName(newName)
        val prev = oldCtx.prev!!
        val next = oldCtx.next!!
        validateInboundTypeChain(prev.handler, newHandler, newName)
        validateInboundTypeChain(newHandler, next.handler, next.name)
        val newCtx = DefaultContext(this, newName, newHandler)
        prev.next = newCtx
        newCtx.prev = prev
        newCtx.next = next
        next.prev = newCtx
        oldCtx.prev = null
        oldCtx.next = null
        callHandlerRemoved(oldCtx)
        callHandlerAdded(newCtx)
        return oldCtx.handler
    }

    override fun get(name: String): PipelineHandler? = findContext(name)?.handler

    override fun context(name: String): PipelineHandlerContext? = findContext(name)

    // --- Inbound entry ---

    override fun notifyActive(): Pipeline {
        if (preAttachJournalDrained) {
            // Idempotent: only the first observation fires through the
            // chain; subsequent calls are dropped. Late-added handlers
            // pick up the active state via [callHandlerAdded]'s
            // per-handler replay using [activeFired].
            if (!activeFired) {
                activeFired = true
                head.invokeOnActive()
            }
        } else {
            // Idempotent flag — multiple notifyActive calls coalesce.
            pendingActive = true
        }
        return this
    }

    override fun notifyRead(msg: Any): Pipeline {
        if (preAttachJournalDrained) {
            head.invokeOnRead(msg)
        } else {
            if (pendingReads.size >= MAX_PRE_ATTACH_READS) {
                // Overflow: release the oldest queued message to make room.
                // Stream protocols cannot recover from out-of-order delivery,
                // so the WARN here flags a likely user-side bug — handler
                // add is too slow relative to peer write rate, and peer
                // bytes are being lost.
                val dropped = pendingReads.removeFirst()
                logger.warn {
                    "Pre-attach read journal overflow (cap=$MAX_PRE_ATTACH_READS); released oldest " +
                        "${dropped::class.simpleName} to enqueue new message — install user inbound handler " +
                        "earlier or pre-allocate the codec stack inside BindConfig.initializeConnection"
                }
                ReferenceCountUtil.safeRelease(dropped)
            }
            pendingReads.addLast(msg)
        }
        return this
    }

    override fun notifyReadComplete(): Pipeline {
        if (preAttachJournalDrained) {
            head.invokeOnReadComplete()
        } else {
            // Coalesce consecutive readComplete events into a single
            // drain-time invocation — handlers treat readComplete as a
            // best-effort "batch boundary" hint, not a per-message signal.
            pendingReadComplete = true
        }
        return this
    }

    override fun notifyInactive(): Pipeline {
        // Idempotent: only the first [notifyInactive] propagates through the
        // chain. Subsequent calls (e.g. [AbstractPipelinedChannel.close]
        // running after an `onReadClosed`-driven `notifyInactive`, or a
        // user-initiated `ch.close()` after peer FIN) become no-ops so
        // existing handlers continue to receive `onInactive` exactly once.
        if (inactiveObserved) return this
        // Record the inactivation so handlers installed after this point
        // receive a replayed [PipelineHandler.onInactive] from
        // [callHandlerAdded]. Without the replay, an engine-driven peer-FIN
        // event delivered before [SuspendBridgeHandler] is lazily installed
        // (e.g. inside [PipelinedChannel.read]) would be lost — the bridge
        // would suspend forever waiting for `eof = true`.
        inactiveObserved = true
        if (preAttachJournalDrained) {
            inactiveFired = true
            head.invokeOnInactive()
        }
        // Pre-attach: the inactiveObserved flag is sufficient; drain replays
        // it via head.invokeOnInactive at drain time and sets
        // [inactiveFired].
        return this
    }

    /**
     * The transport failure this pipeline is reporting to its handlers.
     *
     * [HeadHandler] reads it to tell a refusal these handlers get — riders
     * attached — from one that reached nobody, whose riders would otherwise
     * vanish with the head's swallow. Identity, not equality: the funnel
     * rethrows the very instance it reported.
     *
     * Set when these handlers are getting it — now, or by a replay already
     * scheduled — and not when one of them runs. Waiting for a handler to
     * run would never see a journalled failure at all: the replay happens
     * *after* the head has swallowed the rethrow and decided. A journal with
     * no replay on its way is not that: a pipeline whose handlers are all
     * outbound never asks for the drain, and nothing there will hand the
     * cause over, so the head is left to record what it silences.
     *
     * It answers for the moment it is read, which is all the head can act
     * on. A handler attached *after* the head has recorded the refusal gets
     * the replay as well, so it is named twice — chosen over the
     * alternative, which names it nowhere when no handler ever arrives.
     *
     * What it deliberately is not is "the last error seen": only
     * [notifyTransportFailure] moves it, so an application injecting its
     * own cause through the public [notifyError] cannot make a refusal
     * these handlers are being told about look unreported.
     */
    internal var reportedTransportFailure: Throwable? = null
        private set

    /**
     * Entry for the failure a transport reports as it ends the connection,
     * as opposed to one this pipeline's own handlers raised.
     *
     * Routes exactly like [notifyError] — delivered now, or journalled until
     * handlers attach — and additionally records it for the head's check
     * above, which no other entrance may move.
     */
    internal fun notifyTransportFailure(cause: Throwable) {
        if (preAttachJournalDrained || drainScheduled) reportedTransportFailure = cause
        notifyError(cause)
    }

    override fun notifyError(cause: Throwable): Pipeline {
        if (preAttachJournalDrained) {
            head.invokeOnError(cause)
        } else {
            if (pendingErrors.size < MAX_PRE_ATTACH_ERRORS) {
                pendingErrors.addLast(cause)
            } else {
                logger.warn(cause) {
                    "Pre-attach error journal overflow (cap=$MAX_PRE_ATTACH_ERRORS); dropping additional error"
                }
            }
        }
        return this
    }

    override fun notifyUserEvent(event: Any): Pipeline {
        if (preAttachJournalDrained) {
            head.invokeOnUserEvent(event)
        } else {
            if (pendingUserEvents.size < MAX_PRE_ATTACH_USER_EVENTS) {
                pendingUserEvents.addLast(event)
            } else {
                logger.warn { "Pre-attach user-event journal overflow (cap=$MAX_PRE_ATTACH_USER_EVENTS); dropping" }
            }
        }
        return this
    }

    override fun notifyWritabilityChanged(isWritable: Boolean): Pipeline {
        if (preAttachJournalDrained) {
            // Record the latest value so [callHandlerAdded]'s
            // per-handler replay can deliver the current state to a
            // late-added handler. Writability is stateful — only the
            // most recent value is meaningful when joining.
            writabilityCurrent = isWritable
            head.invokeOnWritabilityChanged(isWritable)
        } else {
            // Latest-only — only the most recent value is meaningful when
            // a handler joins.
            pendingWritability = isWritable
        }
        return this
    }

    // --- Outbound entry ---

    override fun requestWrite(msg: Any): Pipeline {
        if (!onOwningContext { tail.invokeOnWrite(msg) }) ReferenceCountUtil.safeRelease(msg)
        return this
    }

    override fun requestFlush(): Pipeline {
        if (!onOwningContext { tail.invokeOnFlush() }) reportDroppedFlush()
        return this
    }

    override fun requestClose(): Pipeline {
        if (!onOwningContext { tail.invokeOnClose() }) closeWithoutChain()
        return this
    }

    /**
     * Closes the transport when a close could not reach the chain.
     *
     * The walk ends at [HeadHandler], whose whole job is to call
     * `transport.close()` — the only thing that releases the descriptor. When
     * the walk cannot run, reproducing just that terminus here recovers the
     * descriptor, which would otherwise stay open for the pipeline's lifetime.
     * It is **not** a pipeline close: the handlers' `onClose` is genuinely
     * skipped, and anything a handler releases there — a TLS codec's native
     * session, for one — is skipped with it. That is why this is reported
     * rather than done quietly, and why "better" here means the fd, not
     * everything.
     *
     * Silent when the transport is already closed. That is the ordinary
     * close-after-close, and `close()` is idempotent, so there is nothing to
     * report and nothing to do.
     *
     * **This calls `close()` from whatever thread the caller is on, and that is
     * only safe while "cannot dispatch" implies the loop is fully quiescent.**
     * The engines that answer `false` do so on quiescence, so their `close()`
     * takes its caller-thread teardown branch. An engine that answered `false`
     * during a shutdown *in progress* would instead send this caller into the
     * loop hand-off's wait — turning an ordinary `close()` into a spin on an
     * arbitrary thread, inside application teardown.
     */
    private fun closeWithoutChain() {
        if (transport.isOpen) {
            logger.warn {
                "close could not reach the pipeline — the owning context has stopped, so the handlers' " +
                    "onClose is skipped; closing the transport directly so its descriptor is released"
            }
        }
        // The journal goes with the descriptor. Nothing else releases it — it
        // is this pipeline's own state, not the transport's, so the teardown
        // that runs inside close() cannot reach it, and the drain that would
        // have is exactly what is not going to happen.
        discardPreAttachJournal()
        // Invoked rather than re-implemented: the head *is* the terminus, and a
        // second responsibility added there later has to land on this path too.
        // No re-entry — the head is a DuplexHandler, so this takes the outbound
        // branch straight into its own onClose.
        head.invokeOnClose()
    }

    /**
     * Reports a flush that could not reach the chain.
     *
     * Nothing is recovered here: a flush carries no ownership, and whatever is
     * already buffered stays in the transport's queue until its teardown
     * releases it. What would be wrong is saying nothing — the caller asked for
     * bytes to go out and they will not.
     *
     * Silent on a closed transport, where a dropped flush is expected.
     */
    private fun reportDroppedFlush() {
        if (!transport.isOpen) return
        // Once per pipeline. A streaming response flushes per frame, and some
        // callers reach this without the channel's own `isOpen` guard, so an
        // engine shutdown with responses in flight would otherwise emit a line
        // per frame per connection. The condition is per-connection, not
        // per-frame; saying it once says all of it.
        if (droppedFlushReported) return
        droppedFlushReported = true
        logger.warn {
            "flush could not reach the pipeline — the owning context has stopped; anything already " +
                "buffered stays queued until the transport is closed (reported once per connection)"
        }
    }

    /** Latch for [reportDroppedFlush]; owning-context-confined like the lifecycle flags. */
    private var droppedFlushReported: Boolean = false

    // --- Internal ---

    private fun insertBetween(before: DefaultContext, new: DefaultContext, after: DefaultContext) {
        before.next = new
        new.prev = before
        new.next = after
        after.prev = new
    }

    private fun findContext(name: String): DefaultContext? {
        var ctx = head.next
        while (ctx != null && ctx !== tail) {
            if (ctx.name == name) return ctx
            ctx = ctx.next
        }
        return null
    }

    private fun getContext(name: String): DefaultContext =
        findContext(name) ?: throw NoSuchElementException("Handler '$name' not found in pipeline")

    private fun checkDuplicateName(name: String) {
        require(findContext(name) == null) { "Duplicate handler name: '$name'" }
    }

    private fun callHandlerAdded(ctx: DefaultContext) {
        // Schedule the pre-attach journal drain on the *first* user
        // [InboundHandler] addition. Subsequent addX calls in the same
        // synchronous block (e.g. codec stack setup adding decoder +
        // aggregator + handler back-to-back) must accumulate before the
        // drain fires, which is why the drain is deferred onto
        // [ioDispatcher] rather than executed inline. See the journal
        // KDoc above for the full rationale.
        var firedDrainInline = false
        if (!preAttachJournalDrained && !drainScheduled && ctx.handler is InboundHandler) {
            drainScheduled = true
            if (!transport.canDispatchToOwningContext) {
                // The dispatcher will not run again, so a deferred drain would
                // sit in a queue nobody reads, holding the journal's pooled
                // buffers for as long as this pipeline is reachable. Draining
                // inline is not the alternative: that runs handler code off the
                // owning context, on the assembly path every connection takes.
                // The connection is over and these reads can no longer be
                // handled by anyone, so they are released instead of stranded.
                //
                // [firedDrainInline] stays false deliberately — nothing was
                // propagated through head, so the lifecycle replay below is
                // still owed to this handler. The discard hands it the
                // inactivation flag it needs; without that the replay matches
                // no branch and silently does nothing.
                discardPreAttachJournal()
            } else if (ioDispatcher.isDispatchNeeded(EmptyCoroutineContext)) {
                // Defer drain via the dispatcher so any addX calls remaining
                // in the current synchronous block (e.g. codec stack setup
                // adding decoder + aggregator + handler back-to-back) all
                // accumulate before drain replays through the assembled chain.
                ioDispatcher.dispatch(EmptyCoroutineContext, Runnable { drainPreAttachJournal() })
            } else {
                // Test transports backed by `Dispatchers.Unconfined` — which
                // throws from `dispatch()` by design (Unconfined is meant for
                // inline execution) — fall back to inline drain; unit tests
                // typically add a single handler before `notifyXxx`, so
                // partial-chain replay does not arise.
                drainPreAttachJournal()
                firedDrainInline = true
            }
        }
        try {
            ctx.handler.handlerAdded(ctx)
            // Replay a previously-observed inactivation so handlers installed
            // after [notifyInactive] still receive the lifecycle event. The
            // canonical case is the lazy [SuspendBridgeHandler] installed by
            // [PipelinedChannel.read]: an engine that reports peer FIN from
            // the always-armed read filter (kqueue `EV_EOF`, epoll
            // `EPOLLRDHUP`) before the user code calls `read` would
            // otherwise leave the bridge waiting forever for `eof = true`.
            // Per-handler lifecycle replay for late-added handlers.
            // The [firedDrainInline] guard skips replay when the drain
            // that just ran inline above already propagated lifecycle
            // events through this handler via head — replaying again
            // would double-fire.
            if (!firedDrainInline && ctx.handler is InboundHandler) {
                replayLifecycleTo(ctx, ctx.handler)
            }
        } catch (e: Throwable) {
            logger.error(e) { "handlerAdded() threw for '${ctx.name}'" }
        }
    }

    private fun callHandlerRemoved(ctx: DefaultContext) {
        try {
            ctx.handler.handlerRemoved(ctx)
        } catch (e: Throwable) {
            logger.error(e) { "handlerRemoved() threw for '${ctx.name}'" }
        }
    }

    /**
     * Validates inbound type chain between adjacent handlers.
     *
     * Skipped when either handler is not a [InboundHandler] or when
     * either type is [Any] (opt-out default).
     */
    private fun validateInboundTypeChain(
        prevHandler: PipelineHandler,
        nextHandler: PipelineHandler,
        nextName: String,
    ) {
        if (prevHandler !is InboundHandler) return
        if (nextHandler !is InboundHandler) return
        val produced = prevHandler.producedType
        val accepted = nextHandler.acceptedType
        if (produced == Any::class || accepted == Any::class) return
        // KMP limitation: no reflective supertype traversal (Class.isAssignableFrom
        // is JVM-only). Validate exact type match only. Subtype relationships
        // (e.g., HttpObject → HttpRequest) are not detected; handlers should
        // declare the exact type they produce/accept.
        if (produced != accepted) {
            throw PipelineTypeException(
                "Type mismatch in pipeline: '${nameOf(prevHandler)}' produces " +
                    "${produced.simpleName} but '$nextName' accepts ${accepted.simpleName}",
            )
        }
    }

    private fun nameOf(handler: PipelineHandler): String {
        var ctx: DefaultContext? = head
        while (ctx != null) {
            if (ctx.handler === handler) return ctx.name
            ctx = ctx.next
        }
        return handler::class.simpleName ?: "unknown"
    }

    // --- DefaultContext ---

    /**
     * A node in the doubly-linked list that forms the [DefaultPipeline].
     *
     * Each context wraps a single [PipelineHandler] and provides the
     * [PipelineHandlerContext] interface for that handler to propagate
     * events to the next handler in the chain.
     *
     * **Inbound navigation** ([findNextInbound]): follows [next] pointers
     * from head toward tail, skipping non-[InboundHandler] nodes.
     *
     * **Outbound navigation** ([findPrevOutbound]): follows [prev] pointers
     * from tail toward head, skipping non-[OutboundHandler] nodes.
     *
     * **Invoke methods** (`invokeOn*`): wrap handler callbacks with try-catch
     * to prevent IoBuf leaks on exceptions. [invokeOnRead] releases the message
     * on exception; [invokeOnError] logs the secondary exception to avoid
     * infinite error propagation loops.
     */
    internal class DefaultContext(
        private val pipelineRef: DefaultPipeline,
        override val name: String,
        override val handler: PipelineHandler,
    ) : PipelineHandlerContext {

        /** Previous node toward HEAD (outbound direction). Null when detached. */
        var prev: DefaultContext? = null

        /** Next node toward TAIL (inbound direction). Null when detached. */
        var next: DefaultContext? = null

        override val channel: PipelinedChannel get() = pipelineRef.channel
        override val pipeline: Pipeline get() = pipelineRef
        override val allocator: BufferAllocator get() = channel.allocator

        // --- Inbound propagation ---

        override fun propagateActive() {
            val nextCtx = findNextInbound() ?: return
            nextCtx.invokeOnActive()
        }

        override fun propagateRead(msg: Any) {
            val nextCtx = findNextInbound() ?: return
            nextCtx.invokeOnRead(msg)
        }

        override fun propagateReadComplete() {
            val nextCtx = findNextInbound() ?: return
            nextCtx.invokeOnReadComplete()
        }

        override fun propagateInactive() {
            val nextCtx = findNextInbound() ?: return
            nextCtx.invokeOnInactive()
        }

        override fun propagateError(cause: Throwable) {
            val nextCtx = findNextInbound() ?: return
            nextCtx.invokeOnError(cause)
        }

        override fun propagateUserEvent(event: Any) {
            val nextCtx = findNextInbound() ?: return
            nextCtx.invokeOnUserEvent(event)
        }

        override fun propagateWritabilityChanged(isWritable: Boolean) {
            val nextCtx = findNextInbound() ?: return
            nextCtx.invokeOnWritabilityChanged(isWritable)
        }

        // --- Outbound propagation ---

        // The chain walk (findPrevOutbound) runs *inside* onOwningContext, not
        // before it. prev/next are non-volatile and EventLoop-confined, so an
        // off-loop emitter that resolved the previous context on its own thread
        // could read a link the loop is concurrently mutating — and a context
        // detached between the resolve and the dispatched run would surface a
        // null prev whose write leaks. Resolving on the owning context closes
        // both: a null prev there releases the message rather than dropping it.

        override fun propagateWrite(msg: Any) {
            if (!pipelineRef.onOwningContext {
                    val prevCtx = findPrevOutbound()
                    if (prevCtx != null) prevCtx.invokeOnWrite(msg) else ReferenceCountUtil.safeRelease(msg)
                }
            ) {
                ReferenceCountUtil.safeRelease(msg)
            }
        }

        override fun propagateFlush() {
            if (!pipelineRef.onOwningContext { findPrevOutbound()?.invokeOnFlush() }) {
                pipelineRef.reportDroppedFlush()
            }
        }

        override fun propagateClose() {
            if (!pipelineRef.onOwningContext { findPrevOutbound()?.invokeOnClose() }) {
                pipelineRef.closeWithoutChain()
            }
        }

        // --- Invoke with try-catch (leak prevention) ---

        internal fun invokeOnActive() {
            val h = handler
            if (h is InboundHandler) {
                try {
                    h.onActive(this)
                } catch (e: Throwable) {
                    propagateError(e)
                }
            } else {
                propagateActive()
            }
        }

        internal fun invokeOnRead(msg: Any) {
            val h = handler
            if (h is InboundHandler) {
                try {
                    h.onRead(this, msg)
                } catch (e: Throwable) {
                    ReferenceCountUtil.safeRelease(msg)
                    propagateError(e)
                }
            } else {
                propagateRead(msg)
            }
        }

        internal fun invokeOnReadComplete() {
            val h = handler
            if (h is InboundHandler) {
                try {
                    h.onReadComplete(this)
                } catch (e: Throwable) {
                    propagateError(e)
                }
            } else {
                propagateReadComplete()
            }
        }

        internal fun invokeOnInactive() {
            val h = handler
            if (h is InboundHandler) {
                try {
                    h.onInactive(this)
                } catch (e: Throwable) {
                    propagateError(e)
                }
            } else {
                propagateInactive()
            }
        }

        internal fun invokeOnError(cause: Throwable) {
            val h = handler
            if (h is InboundHandler) {
                try {
                    h.onError(this, cause)
                } catch (e: Throwable) {
                    pipelineRef.logger.error(e) {
                        "onError() threw in '$name' while handling: $cause"
                    }
                }
            } else {
                propagateError(cause)
            }
        }

        internal fun invokeOnUserEvent(event: Any) {
            val h = handler
            if (h is InboundHandler) {
                try {
                    h.onUserEvent(this, event)
                } catch (e: Throwable) {
                    propagateError(e)
                }
            } else {
                propagateUserEvent(event)
            }
        }

        internal fun invokeOnWritabilityChanged(isWritable: Boolean) {
            val h = handler
            if (h is InboundHandler) {
                try {
                    h.onWritabilityChanged(this, isWritable)
                } catch (e: Throwable) {
                    propagateError(e)
                }
            } else {
                propagateWritabilityChanged(isWritable)
            }
        }

        internal fun invokeOnWrite(msg: Any) {
            val h = handler
            if (h is OutboundHandler) {
                try {
                    h.onWrite(this, msg)
                } catch (e: Throwable) {
                    ReferenceCountUtil.safeRelease(msg)
                    propagateError(e)
                }
            } else {
                propagateWrite(msg)
            }
        }

        internal fun invokeOnFlush() {
            val h = handler
            if (h is OutboundHandler) {
                try {
                    h.onFlush(this)
                } catch (e: Throwable) {
                    propagateError(e)
                }
            } else {
                propagateFlush()
            }
        }

        internal fun invokeOnClose() {
            val h = handler
            if (h is OutboundHandler) {
                try {
                    h.onClose(this)
                } catch (e: Throwable) {
                    propagateError(e)
                }
            } else {
                propagateClose()
            }
        }

        // --- Navigation ---

        private fun findNextInbound(): DefaultContext? {
            var ctx = next
            while (ctx != null) {
                if (ctx.handler is InboundHandler) return ctx
                ctx = ctx.next
            }
            return null
        }

        private fun findPrevOutbound(): DefaultContext? {
            var ctx = prev
            while (ctx != null) {
                if (ctx.handler is OutboundHandler) return ctx
                ctx = ctx.prev
            }
            return null
        }
    }

    /**
     * Replays the current lifecycle state to a late-added inbound
     * handler. Replay precedence is "inactive wins" because the
     * channel's terminal state takes priority — a handler joining an
     * already-closed channel should observe `onInactive` (not
     * `onActive`) so it can clean up immediately, regardless of the
     * `activeFired` history.
     */
    private fun replayLifecycleTo(ctx: DefaultContext, handler: InboundHandler) {
        when {
            inactiveObserved && inactiveFired -> invokeReplayCatching(ctx, "onInactive") {
                handler.onInactive(ctx)
            }
            activeFired -> {
                invokeReplayCatching(ctx, "onActive") { handler.onActive(ctx) }
                writabilityCurrent?.let { writable ->
                    invokeReplayCatching(ctx, "onWritabilityChanged") {
                        handler.onWritabilityChanged(ctx, writable)
                    }
                }
            }
        }
    }

    private inline fun invokeReplayCatching(ctx: DefaultContext, eventName: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            logger.error(e) { "$eventName() replay threw for '${ctx.name}'" }
        }
    }

    /**
     * Drains the pre-attach event journal onto the now fully-constructed
     * pipeline. Scheduled by [callHandlerAdded] on the first user
     * [InboundHandler] addition via [ioDispatcher.dispatch]; runs on the
     * EventLoop thread serially with subsequent `notifyXxx` calls.
     *
     * **Replay order**: `onActive` (if pending) → buffered reads in
     * arrival order → `onReadComplete` (if any read completed before
     * the drain) → `onWritabilityChanged` with the latest value (if
     * any) → buffered user events → buffered errors → `onInactive` (if
     * the channel transitioned to inactive before the drain).
     *
     * The flag flip `preAttachJournalDrained = true` happens at the
     * *start* of the drain so any `addX` invoked from within a replay
     * handler (e.g. a codec that installs another handler in
     * `handlerAdded`) bypasses the journal and propagates events
     * directly through the head — the journal is one-shot.
     */
    private fun drainPreAttachJournal() {
        if (preAttachJournalDrained) return
        preAttachJournalDrained = true

        if (pendingActive) {
            pendingActive = false
            activeFired = true
            head.invokeOnActive()
        }
        while (pendingReads.isNotEmpty()) {
            head.invokeOnRead(pendingReads.removeFirst())
        }
        if (pendingReadComplete) {
            pendingReadComplete = false
            head.invokeOnReadComplete()
        }
        pendingWritability?.let { writable ->
            pendingWritability = null
            writabilityCurrent = writable
            head.invokeOnWritabilityChanged(writable)
        }
        while (pendingUserEvents.isNotEmpty()) {
            head.invokeOnUserEvent(pendingUserEvents.removeFirst())
        }
        while (pendingErrors.isNotEmpty()) {
            head.invokeOnError(pendingErrors.removeFirst())
        }
        if (inactiveObserved) {
            // Replay the inactivation through the head so the entire
            // chain (not just the first handler via the per-handler
            // [callHandlerAdded] replay) processes onInactive.
            inactiveFired = true
            head.invokeOnInactive()
        }
    }

    /**
     * Releases the journalled reads when the owning context has stopped, so
     * they are not held by a drain that will never run.
     *
     * Only the reads are *released*; the queued user events and errors are
     * dropped without one. The difference is ownership, not type: [notifyRead]
     * takes over its message — its own overflow path releases what it drops —
     * whereas [notifyUserEvent] does not, and its overflow drops unreleased.
     * Releasing a user event here would free something the emitter may still
     * hold.
     *
     * **The lifecycle bookkeeping is exactly [drainPreAttachJournal]'s**, minus
     * the head invocations. Not because the chain is unassembled — [addLast]
     * links the new context before calling here, which is why the inline-drain
     * branch has to set `firedDrainInline` at all — but because a head sweep
     * now would reach only the handlers added so far, and deferring past that
     * is the whole reason the drain is dispatched. On a stopped loop there is
     * no later tick to defer to, so the per-handler replay carries what it can.
     *
     * That replay fires on `inactiveObserved && inactiveFired` or on
     * `activeFired` — flags the drain promotes and this path must promote too.
     * Leaving them as they are makes it match no branch at all and do nothing,
     * silently: a bridge installed after a peer close would wait for an EOF it
     * has already missed. Nothing sets them later, either — [notifyInactive]
     * returns early once observed, and the drain early-returns once the journal
     * is marked.
     *
     * **What the replay cannot carry** is anything with no branch of its own:
     * a journalled writability change (the drain delivers it through head
     * unconditionally; the replay only inside the `activeFired` branch), the
     * queued user events, and the errors — which is why the errors are at least
     * reported with their cause below rather than dropped.
     *
     * Marking the journal drained stops later reads from re-filling a queue
     * with the same fate.
     */
    private fun discardPreAttachJournal() {
        // Guarded like [drainPreAttachJournal]: there are two callers now, and
        // a close after the journal already went would otherwise re-run the
        // flag promotion below.
        if (preAttachJournalDrained) return
        preAttachJournalDrained = true
        // Promoted before anything below can return early. These are what the
        // per-handler replay reads, and they are the only delivery left.
        if (pendingActive) {
            pendingActive = false
            activeFired = true
        }
        pendingWritability?.let { writable ->
            pendingWritability = null
            writabilityCurrent = writable
        }
        if (inactiveObserved) inactiveFired = true
        val reads = pendingReads.size
        val events = pendingUserEvents.size
        val errors = pendingErrors.size
        while (pendingReads.isNotEmpty()) {
            ReferenceCountUtil.safeRelease(pendingReads.removeFirst())
        }
        pendingUserEvents.clear()
        pendingReadComplete = false
        // Errors are reported individually, with their cause, the way the
        // journal's own overflow path reports them. Dropping the only record of
        // why a connection failed is the silent failure this codebase forbids.
        while (pendingErrors.isNotEmpty()) {
            val cause = pendingErrors.removeFirst()
            logger.warn(cause) { "Discarded a journalled error — this connection's owning context has stopped" }
        }
        if (reads > 0 || events > 0 || errors > 0) {
            logger.warn {
                "Discarded the pre-attach journal ($reads read(s), $events user event(s), $errors error(s)) " +
                    "— a handler was added after this connection's owning context stopped, so the deferred " +
                    "replay would never have run"
            }
        }
    }

    private companion object {
        /**
         * Per-pipeline cap on buffered inbound messages waiting for the
         * first user [InboundHandler]. Sized to handle realistic codec
         * setup races (HTTP/1 request line + headers + small body
         * chunks) without unbounded growth if the user forgets to
         * install a handler. Overflow drops the oldest queued message
         * with a `WARN` log — protocol streams cannot recover from
         * out-of-order delivery, so the WARN flags a likely user-side
         * bug.
         */
        private const val MAX_PRE_ATTACH_READS = 64

        /** Cap on buffered errors. Pathological error storms get truncated. */
        private const val MAX_PRE_ATTACH_ERRORS = 8

        /** Cap on buffered user events. */
        private const val MAX_PRE_ATTACH_USER_EVENTS = 16
    }
}
