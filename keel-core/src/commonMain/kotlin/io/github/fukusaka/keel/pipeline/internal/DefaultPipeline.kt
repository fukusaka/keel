package io.github.fukusaka.keel.pipeline.internal

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.debug
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
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
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
 *
 * **State.** Everything the pipeline decides on is a record of something that
 * happened, kept as a small state machine rather than as a set of flags:
 *
 * - [activationPhase] / [readClosedPhase] / [endingPhase]: `NONE → OBSERVED →
 *   DELIVERED`. Observed when the transport reported it while the journal was
 *   still collecting; delivered when the sweep from the head started. The
 *   peer's end of file sits between the other two, and is journalled and
 *   replayed the same way ([readClosedCursor] alongside the other cursors).
 * - [journal]: `FILLING → DRAIN_SCHEDULED | DRAIN_OWED → DRAINED`, or
 *   `→ DISCARD_OWED | DISCARDED` when nothing will ever drain it. The pre-attach
 *   journal holds what arrived before the first inbound handler.
 * - [closeWalk]: `NONE → RUNNING ⇄ DONE`, counted by the nesting of the close
 *   deliveries that actually invoked a handler; every `DONE` runs the release
 *   ([afterCloseWalk]).
 * - [life]: `LIVE → TERMINATE_OWED → ENDING → DESTROYING → DESTROYED`, the
 *   pipeline's end of life ([terminate]): the ending is delivered if it was not,
 *   then every handler is removed (`handlerRemoved`), as Netty's `destroy` does.
 * - [frameDepth]: how many handler frames are on the stack. Work that must not
 *   run inside a handler's callback — a drain owed to an inline dispatcher, the
 *   delivery a discard owes, the end of life — is owed to the epilogue of the
 *   outermost frame, where the depth returns to zero ([runOwed]).
 *
 * Each context keeps its own lifecycle (`PENDING → ACTIVE → ENDED`, or
 * `REMOVED`), so a lifecycle event reaches a context at most once, whichever
 * path brought it: the sweep, the replay a late handler gets, or a handler
 * that raised the ending itself.
 *
 * **Delivery** is transition-then-invoke: a delivery function moves the
 * context's state, invokes the handler, and continues through the handler's
 * own propagation. A handler that throws on a lifecycle event does not hide it
 * from the handlers below: the throw travels as an error and the event is
 * propagated on the handler's behalf. A handler that consumes a close ends
 * that walk (Netty); resources are released by the end of life, not by the
 * walk reaching the head.
 *
 * **Threads.** Every field is confined to the transport's owning context. A
 * close after that context has stopped runs in place on the caller's thread
 * under a per-pipeline [claim], the same quiescence the transport's own close
 * from that thread rests on.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class DefaultPipeline(
    override val channel: PipelinedChannel,
    transport: IoTransport,
    internal val logger: Logger,
) : Pipeline {

    private val transport: IoTransport = transport

    private val head: DefaultContext = DefaultContext(this, "HEAD", HeadHandler(transport, this))
    private val tail: DefaultContext = DefaultContext(this, "TAIL", TailHandler(logger, this))

    /**
     * Dispatcher captured from the underlying [IoTransport]. Used to schedule
     * the journal's drain on the next tick after the first user
     * [InboundHandler] is added, and to hand off work from other threads.
     */
    private val ioDispatcher: CoroutineDispatcher = transport.ioDispatcher

    // --- State ---

    /** The pipeline's end of life; see [terminate]. */
    internal enum class Life { LIVE, TERMINATE_OWED, ENDING, DESTROYING, DESTROYED }

    /**
     * How a lifecycle event is being delivered: by the sweep through the
     * chain, or replayed to a late handler alone — where a throw is only
     * logged, since the handlers below are not owed the event by this path.
     */
    internal enum class Mode { SWEEP, REPLAY }

    /** A context's lifecycle. `REMOVED` is terminal and the only state in which `handlerRemoved` has run. */
    internal enum class Lifecycle { PENDING, ACTIVE, ENDED, REMOVED }

    private var activationPhase: Phase = Phase.NONE

    /**
     * The peer's end of file: observed by [notifyReadClosed], delivered by
     * the read-closed sweep. Sits between the activation and the ending and
     * is never delivered after the ending — a read side that closes after
     * the connection ended is no news.
     */
    private var readClosedPhase: Phase = Phase.NONE

    /**
     * Runs right after the read-closed sweep delivered the peer's end of
     * file to the chain — the channel decides there whether the FIN ends the
     * connection (Pipeline mode, nobody else owns it) or is the caller's to
     * act on. At delivery rather than at the transport's report: a FIN
     * journalled ahead of the first handler is decided when it reaches one.
     */
    internal var onReadClosedDelivered: ((pipelineMode: Boolean) -> Unit)? = null

    /**
     * Whether the channel is one keel owns the lifecycle of, asked before the
     * walk that delivers the peer's end of file. The walk can change the
     * answer — a handler removing itself, or installing a bridge, from inside
     * its own `onReadClosed` — and an answer read afterwards is the one that
     * handler made, not the one the report arrived to.
     */
    internal var pipelineModeNow: (() -> Boolean)? = null
    private var endingPhase: Phase = Phase.NONE
    private var closeWalk: CloseWalk = CloseWalk.NONE

    /**
     * Whether this side has begun a close of its own — asked of the pipeline,
     * or walked to the head by a handler, or finished after the walk when a
     * handler consumed it; those are the places the transport is released. Read by the channel: a transport reporting the end after
     * that is catching up with a close this side performed, not ending a
     * connection under its caller. A walk can only start from `requestClose`
     * or the channel's own close, and the first of those records it when it
     * is asked for rather than when it lands — a handler writing its farewell
     * from its own close can have the transport refuse the write and report
     * the end while the walk is still travelling.
     */
    @Volatile
    internal var closeReachedHead = false

    /** Nesting of close deliveries that invoked a handler; `0 → 1` is RUNNING, `1 → 0` is DONE. */
    private var closeDepth: Int = 0

    /**
     * A close requested while the walk was running. Served at DONE as a new
     * walk from the tail, so that a handler-initiated walk — which covers only
     * the head side of its initiator — is completed by the tail side before
     * the end of life removes it.
     */
    private var tailWalkOwed: Boolean = false

    private var journal: Journal = Journal.FILLING

    internal var life: Life = Life.LIVE
        private set

    /**
     * Handler frames on the stack: every inbound and outbound event delivery,
     * `handlerAdded` and `handlerRemoved`. When it returns to zero the owed
     * work runs ([runOwed]). Netty keeps handlers from being removed inside a
     * callback by deferring `destroy` to a fresh loop task; the epilogue does
     * the same without a task that a stopping loop could fail to run.
     */
    private var frameDepth: Int = 0

    /**
     * The per-pipeline claim under which a close runs in place after the
     * owning context stopped. Not re-entrant: a second closer — another
     * thread, or a handler closing back from inside the in-place walk — finds
     * it taken and does not touch the pipeline; the walk that holds it started
     * at the tail and reaches everyone.
     */
    private val claim = AtomicInt(0)

    /**
     * The latest writability the chain was told, or null before the first.
     * Writability is a state, so a late handler is told the current value
     * rather than a replay of every change.
     */
    private var writabilityCurrent: Boolean? = null

    private var activeCursor: Cursor? = null
    private var readClosedCursor: Cursor? = null
    private var inactiveCursor: Cursor? = null
    private var writabilityCursor: Cursor? = null
    private var closeCursor: Cursor? = null

    // --- Pre-attach journal ---

    /**
     * Pre-attach event journal: holds inbound events that arrive before the
     * pipeline acquires its first user [InboundHandler] and replays them once
     * such a handler is installed.
     *
     * **Why**: when an engine arms its read primitive eagerly (e.g.
     * `IdleReadPolicy.DETECT_PEER_CLOSE` on `engine-nio` / `engine-netty` NIO
     * fallback / `engine-nwconnection`, where the underlying API forces an
     * active read to observe peer FIN), bytes the peer sends between channel
     * construction and the first [PipelinedChannel.ensureBridge] /
     * `pipeline.addLast` call would otherwise reach [TailHandler.onRead] and be
     * released with a `WARN` log. The journal captures those events and drains
     * them onto the (now fully-constructed) pipeline after the user's
     * synchronous setup block completes.
     *
     * **Drain timing — dispatcher tick, not first addX**: a synchronous codec
     * stack setup adds decoder, aggregator and handler back to back. Draining
     * on the first add would replay through a partial pipeline, so the first
     * inbound add schedules the drain on the dispatcher instead
     * (`DRAIN_SCHEDULED`), the keel equivalent of Netty's `ChannelInitializer`
     * deferred-event model. On an inline dispatcher the drain is owed to the
     * outermost handler frame's epilogue (`DRAIN_OWED`), so a handler that
     * installs the rest of the stack from `handlerAdded` is drained after the
     * whole stack is in place.
     *
     * **Per-event replay strategy**: activation and ending are phases
     * (idempotent); reads are a bounded queue ([MAX_PRE_ATTACH_READS], overflow
     * releases the oldest with a `WARN`); read completes coalesce; flush
     * completions are counted; writability is latest-only; errors and user
     * events are bounded queues.
     *
     * **After the connection ended, no data is delivered.** Each stage and
     * element of the drain is delivered only while the ending has not been
     * delivered and the transport is open; otherwise reads are released, errors
     * logged, and only the ending sweep remains.
     */
    private val pendingReads: ArrayDeque<Any> = ArrayDeque()
    private var pendingReadComplete: Boolean = false

    /**
     * Flush completions raised before the drain, replayed by it. A count and
     * not a flag: each completion answers one flush, and the handler that
     * issued them is entitled to as many as it caused.
     */
    private var pendingFlushCompletions: Int = 0
    private var pendingWritability: Boolean? = null
    private val pendingUserEvents: ArrayDeque<Any> = ArrayDeque()
    private val pendingErrors: ArrayDeque<Throwable> = ArrayDeque()

    /**
     * The failure a transport reported for this connection, whatever became
     * of it afterwards.
     *
     * Two frames ask about it, and they ask different things. The end of the
     * pipeline asks *whose* failure this is: a refusal a handler threw, or
     * one an application injected through the public error entrance, is not
     * the connection's own end, and a handler throwing anything is the case
     * that frame exists to report. Identity answers that, and answers it the
     * same whether the cause arrives now or by a replay later — which is why
     * this is set whenever the transport reports, and not only when the
     * handlers are getting it.
     *
     * The head asks the other question — whether anyone will receive it —
     * through [handlersAreGettingTransportFailure].
     *
     * Only [notifyTransportFailure] moves it, so an application injecting a
     * cause of its own cannot make its exception look like the connection's.
     */
    internal var reportedTransportFailure: Throwable? = null
        private set

    init {
        head.next = tail
        tail.prev = head
    }

    override val isEmpty: Boolean get() = head.next === tail

    /** Whether the connection is over as far as data goes: the ending was delivered, or the descriptor is gone. */
    private val ended: Boolean get() = endingPhase == Phase.DELIVERED || !transport.isOpen

    private val destroying: Boolean get() = life == Life.DESTROYING || life == Life.DESTROYED

    private val journalSettled: Boolean get() = journal == Journal.DRAINED || journal == Journal.DISCARDED

    // --- Composition ---

    override fun addFirst(name: String, handler: PipelineHandler): Pipeline {
        checkDuplicateName(name)
        val newCtx = DefaultContext(this, name, handler)
        val after = head.next!!
        validateInboundTypeChain(head, head.handler, handler, name)
        validateInboundTypeChain(head, handler, after.handler, after.name)
        insertBetween(head, newCtx, after)
        callHandlerAdded(newCtx)
        return this
    }

    override fun addLast(name: String, handler: PipelineHandler): Pipeline {
        checkDuplicateName(name)
        val newCtx = DefaultContext(this, name, handler)
        val before = tail.prev!!
        validateInboundTypeChain(head, before.handler, handler, name)
        validateInboundTypeChain(head, handler, tail.handler, "TAIL")
        insertBetween(before, newCtx, tail)
        callHandlerAdded(newCtx)
        return this
    }

    override fun addBefore(baseName: String, name: String, handler: PipelineHandler): Pipeline {
        val base = getContext(baseName)
        checkDuplicateName(name)
        val newCtx = DefaultContext(this, name, handler)
        val before = base.prev!!
        validateInboundTypeChain(head, before.handler, handler, name)
        validateInboundTypeChain(head, handler, base.handler, baseName)
        insertBetween(before, newCtx, base)
        callHandlerAdded(newCtx)
        return this
    }

    override fun addAfter(baseName: String, name: String, handler: PipelineHandler): Pipeline {
        val base = getContext(baseName)
        checkDuplicateName(name)
        val newCtx = DefaultContext(this, name, handler)
        val after = base.next!!
        validateInboundTypeChain(head, base.handler, handler, name)
        validateInboundTypeChain(head, handler, after.handler, after.name)
        insertBetween(base, newCtx, after)
        callHandlerAdded(newCtx)
        return this
    }

    override fun remove(name: String): PipelineHandler {
        val ctx = getContext(name)
        val prev = ctx.prev!!
        val next = ctx.next!!
        validateInboundTypeChain(head, prev.handler, next.handler, next.name)
        removeContext(ctx)
        // A mutation on a stopped loop is the last chance to notice that the
        // journal's drain is never going to run — the same as for an add.
        if (!transport.canDispatchToOwningContext && !journalSettled) {
            discardJournal(JournalDiscard.OWNING_CONTEXT_STOPPED)
        }
        return ctx.handler
    }

    /**
     * The one transition to `REMOVED`, shared by [remove], [replace], the end
     * of life ([destroy]) and a post-mortem add — so `handlerRemoved` runs at
     * most once per context, however many of those reach it.
     *
     * Only the neighbours forget the context; the context keeps them. A walk
     * that is passing through the handler when it removes itself — from its
     * own `onInactive`, `onRead`, `onClose` — propagates from a context that
     * still knows where next is, and reaches it. Nothing is routed *to* a
     * removed context, from any direction: the chain the next walk starts
     * from no longer links to it, and a stale link from another removed
     * context skips it — its `handlerRemoved` has run, so its handler may
     * already have released what it held. Netty keeps the links and skips
     * removed handlers the same way.
     */
    private fun removeContext(ctx: DefaultContext) {
        if (ctx.lifecycle == Lifecycle.REMOVED) return
        unlink(ctx)
        ctx.lifecycle = Lifecycle.REMOVED
        callHandlerRemoved(ctx)
    }

    private fun unlink(ctx: DefaultContext) {
        val prev = ctx.prev
        val next = ctx.next
        if (prev != null && prev.next === ctx) prev.next = next
        if (next != null && next.prev === ctx) next.prev = prev
    }

    override fun replace(oldName: String, newName: String, newHandler: PipelineHandler): PipelineHandler {
        val oldCtx = getContext(oldName)
        if (oldName != newName) checkDuplicateName(newName)
        val prev = oldCtx.prev!!
        val next = oldCtx.next!!
        validateInboundTypeChain(head, prev.handler, newHandler, newName)
        validateInboundTypeChain(head, newHandler, next.handler, next.name)
        val newCtx = DefaultContext(this, newName, newHandler)
        prev.next = newCtx
        newCtx.prev = prev
        newCtx.next = next
        next.prev = newCtx
        // The old context is pointed at its replacement in both directions,
        // as in Netty: what the replaced handler forwards after replacing
        // itself — an upgrade decoder handing on the bytes it did not
        // consume — reaches the handler that took its place.
        oldCtx.prev = newCtx
        oldCtx.next = newCtx
        oldCtx.lifecycle = Lifecycle.REMOVED
        callHandlerRemoved(oldCtx)
        callHandlerAdded(newCtx)
        return oldCtx.handler
    }

    override fun get(name: String): PipelineHandler? = findContext(name)?.handler

    override fun context(name: String): PipelineHandlerContext? = findContext(name)

    // --- Inbound entry ---

    override fun notifyActive(): Pipeline {
        if (destroying) return this
        when (journal) {
            Journal.DRAINED, Journal.DISCARDED -> if (activationPhase != Phase.DELIVERED) startActivationSweep()
            else -> if (activationPhase == Phase.NONE) activationPhase = Phase.OBSERVED
        }
        return this
    }

    override fun notifyRead(msg: Any): Pipeline {
        if (destroying) {
            ReferenceCountUtil.safeRelease(msg)
            return this
        }
        when (journal) {
            Journal.DRAINED -> head.invokeOnRead(msg)
            // Nothing will ever hand it to a handler: the drain nothing was
            // going to run has been given up on.
            Journal.DISCARDED, Journal.DISCARD_OWED -> ReferenceCountUtil.safeRelease(msg)
            else -> {
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
        }
        return this
    }

    override fun notifyReadComplete(): Pipeline {
        if (destroying) return this
        when (journal) {
            Journal.DRAINED -> head.invokeOnReadComplete()
            Journal.DISCARDED, Journal.DISCARD_OWED -> {}
            // Coalesce consecutive readComplete events into a single
            // drain-time invocation — handlers treat readComplete as a
            // best-effort "batch boundary" hint, not a per-message signal.
            else -> pendingReadComplete = true
        }
        return this
    }

    override fun notifyFlushComplete(): Pipeline {
        if (destroying) return this
        when (journal) {
            Journal.DRAINED -> head.invokeOnFlushComplete()
            Journal.DISCARDED, Journal.DISCARD_OWED -> {}
            // Held, not dropped. "Before the drain" is not the same as "before
            // any handler": the drain is deferred so a codec stack added back
            // to back accumulates into one replay, and nothing gates a write or
            // a flush in the meantime. A handler that responds on its
            // activation issues a flush inside that window, and dropping its
            // answer loses it for good.
            else -> pendingFlushCompletions++
        }
        return this
    }

    override fun notifyReadClosed(): Pipeline {
        if (destroying || readClosedPhase == Phase.DELIVERED || endingPhase == Phase.DELIVERED) return this
        when (journal) {
            // Not after the descriptor is gone — there is no connection left
            // to answer on, so what the chain is owed there is the ending, and
            // it is owed it: a handler releases what it holds on being
            // removed, and the removal comes with the ending.
            Journal.DRAINED, Journal.DISCARDED ->
                if (ended) startEndingSweep() else startReadClosedSweep()
            // A chain with handlers but none inbound never asks for a drain,
            // so a FIN journalled for it would wait forever: it is swept now
            // — the sweep reaches nobody, and the channel decides on it.
            Journal.FILLING -> if (isEmpty) readClosedPhase = Phase.OBSERVED else startReadClosedSweep()
            else -> readClosedPhase = Phase.OBSERVED
        }
        return this
    }

    override fun notifyInactive(): Pipeline {
        if (destroying || endingPhase == Phase.DELIVERED) return this
        when (journal) {
            Journal.DRAINED, Journal.DISCARDED -> startEndingSweep()
            else -> endingPhase = Phase.OBSERVED
        }
        return this
    }

    /**
     * Whether [cause] is the reported failure *and* these handlers are
     * getting it — now, or by a replay already scheduled.
     *
     * The head reads it to decide whether to record what it silences: a
     * failure on its way to the handlers has a reporter, and one journalled
     * with no replay scheduled — a pipeline whose handlers are all outbound
     * never asks for one — does not.
     */
    internal fun handlersAreGettingTransportFailure(cause: Throwable): Boolean =
        cause === reportedTransportFailure &&
            (journal == Journal.DRAINED || journal == Journal.DRAIN_SCHEDULED || journal == Journal.DRAIN_OWED)

    internal fun notifyTransportFailure(cause: Throwable) {
        reportedTransportFailure = cause
        notifyError(cause)
    }

    override fun notifyError(cause: Throwable): Pipeline {
        if (destroying) return this
        when (journal) {
            Journal.DRAINED -> head.invokeOnError(cause)
            Journal.DISCARDED, Journal.DISCARD_OWED -> logger.warn(cause) {
                "Error reported after the journal was discarded; no handler can act on it"
            }
            else -> {
                if (pendingErrors.size < MAX_PRE_ATTACH_ERRORS) {
                    pendingErrors.addLast(cause)
                } else {
                    logger.warn(cause) {
                        "Pre-attach error journal overflow (cap=$MAX_PRE_ATTACH_ERRORS); dropping additional error"
                    }
                }
            }
        }
        return this
    }

    override fun notifyUserEvent(event: Any): Pipeline {
        if (destroying) return this
        when (journal) {
            Journal.DRAINED -> head.invokeOnUserEvent(event)
            Journal.DISCARDED, Journal.DISCARD_OWED -> {}
            else -> {
                if (pendingUserEvents.size < MAX_PRE_ATTACH_USER_EVENTS) {
                    pendingUserEvents.addLast(event)
                } else {
                    logger.warn { "Pre-attach user-event journal overflow (cap=$MAX_PRE_ATTACH_USER_EVENTS); dropping" }
                }
            }
        }
        return this
    }

    override fun notifyWritabilityChanged(isWritable: Boolean): Pipeline {
        if (destroying) return this
        when (journal) {
            Journal.DRAINED -> {
                writabilityCurrent = isWritable
                head.deliverWritability(isWritable, Mode.SWEEP)
            }
            // Writability is a state, not data: a report after the discard
            // still tells a late handler the current value.
            Journal.DISCARDED, Journal.DISCARD_OWED -> writabilityCurrent = isWritable
            // Latest-only — only the most recent value is meaningful when a
            // handler joins. Published as the current value by the drain, and
            // only if no real report came first.
            else -> pendingWritability = isWritable
        }
        return this
    }

    private fun startActivationSweep() {
        activationPhase = Phase.DELIVERED
        head.deliverActive(Mode.SWEEP)
    }

    private fun startReadClosedSweep() {
        readClosedPhase = Phase.DELIVERED
        val pipelineMode = pipelineModeNow?.invoke() ?: false
        head.deliverReadClosed(Mode.SWEEP)
        onReadClosedDelivered?.invoke(pipelineMode)
    }

    private fun startEndingSweep() {
        endingPhase = Phase.DELIVERED
        head.deliverInactive(Mode.SWEEP)
    }

    // --- Outbound entry ---

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
     * layer.
     *
     * Returns `false` when the transport is already closed and [block] was
     * abandoned, so a caller that transferred buffer ownership can release it
     * rather than leak it. A stopped owning context is asked about separately
     * from a closed transport: the one that strands work is a *live* transport
     * whose dispatcher has stopped — its queue accepts the task and nothing
     * ever drains it. The `false` return does not close the window entirely: a
     * transport that closes, or whose loop stops, *after* the dispatch still
     * leaves the task queued.
     *
     * A write or flush a handler issues from inside an in-place close walk
     * takes this path too and is released: the walk rests on the loop being
     * quiescent, and letting outbound work run inline would lift the loop's
     * confinement for any thread that wrote during the teardown.
     */
    private inline fun onOwningContext(crossinline block: () -> Unit): Boolean {
        if (transport.inOwningContext) {
            block()
            return true
        }
        if (!transport.isOpen || !transport.canDispatchToOwningContext) return false
        try {
            ioDispatcher.dispatch(EmptyCoroutineContext) { block() }
        } catch (refused: Throwable) {
            // A loop that said it could take the hand-off and then would not.
            // The caller is told the same as for one that said it could not,
            // so what it handed over is released rather than left with
            // nobody: the throw travels on, and the caller has nothing to
            // release for it.
            logger.warn(refused) { "the loop refused a hand-off it said it could take" }
            return false
        }
        return true
    }

    /**
     * Runs [block] — a close request — where it can run.
     *
     * A close is about what the handlers own rather than about the transport,
     * so a closed transport does not cancel it: the step is handed to the loop
     * whenever the loop can take it. A loop that cannot — stopped, per
     * [IoTransport.canDispatchToOwningContext] — has the request run in place
     * on the caller's thread under the [claim], after the journal nothing will
     * drain is discarded. A caller that cannot take the claim does nothing:
     * the closer that holds it walks from the tail and reaches everyone.
     */
    private inline fun onOwningContextForClose(crossinline block: () -> Unit) {
        if (transport.inOwningContext) {
            block()
            return
        }
        if (transport.canDispatchToOwningContext) {
            ioDispatcher.dispatch(EmptyCoroutineContext) { block() }
            return
        }
        runInPlace { block() }
    }

    /**
     * Runs [block] on the caller's thread under the per-pipeline [claim], the
     * premise being that the owning context has stopped and nothing else runs
     * this pipeline. Returns `false` without running it when another closer
     * holds the claim. The journal is discarded first: it is this pipeline's
     * own state, the drain that would have delivered it is exactly what is not
     * going to happen on this loop, and the discard delivers the observed
     * lifecycle in place.
     */
    internal inline fun runInPlace(block: () -> Unit): Boolean {
        if (!claim.compareAndSet(0, 1)) return false
        try {
            settleJournalOnStoppedLoop()
            block()
        } finally {
            claim.store(0)
        }
        return true
    }

    override fun requestWrite(msg: Any): Pipeline {
        if (!onOwningContext { tail.invokeOnWrite(msg) }) ReferenceCountUtil.safeRelease(msg)
        return this
    }

    override fun requestFlush(): Pipeline {
        if (!onOwningContext { tail.invokeOnFlush() }) reportDroppedFlush()
        return this
    }

    override fun requestClose(): Pipeline {
        closeReachedHead = true
        onOwningContextForClose { startTailWalk() }
        return this
    }

    /**
     * Starts a close walk at the tail, or owes one to the walk that is
     * running. Returns whether a handler was invoked — a walk that finds every
     * outbound context already delivered or removed starts no frame, and the
     * channel then runs the release itself ([closeOnOwningContext]).
     */
    private fun startTailWalk(): Boolean {
        if (destroying) return false
        if (closeWalk == CloseWalk.RUNNING) {
            tailWalkOwed = true
            return false
        }
        return deliverCloseFrom(tail.prev)
    }

    /**
     * Delivers the close to the first outbound context at or before [start]
     * that has not heard it. Delivered and removed contexts are skipped, so
     * each outbound context hears its close at most once and a later walk
     * passes an earlier consumer. The walk then continues through the
     * handler's own `propagateClose`, and ends where a handler does not
     * propagate — consuming a close is a legitimate decision, as in Netty.
     * Returns whether a handler was invoked.
     */
    private fun deliverCloseFrom(start: DefaultContext?): Boolean {
        if (destroying) return false
        var ctx = start
        while (ctx != null && !ctx.acceptsClose()) ctx = ctx.prev
        if (ctx == null) return false
        ctx.invokeOnClose()
        return true
    }

    /**
     * Runs when the outermost close delivery returns (DONE), whoever started
     * the walk, and from the channel's close when the walk started no frame.
     * Idempotent on facts, so a nested walk running it again does nothing
     * more: (i) the walk owed to this one, from the tail, first, so the tail
     * side of a handler-initiated close hears its close before the end of
     * life removes it; (ii) the descriptor, if a handler consumed the close
     * before the head or the head's own close threw; (iii) the end of life —
     * owed to the outermost frame's epilogue, since the delivery that ran this
     * is itself a frame.
     */
    private fun afterCloseWalk() {
        if (tailWalkOwed) {
            tailWalkOwed = false
            startTailWalk()
        }
        if (transport.isOpen) {
            // The other place this side releases the transport: a handler
            // consumed the walk, so it never reached the head, and the close
            // is finished here instead. Recorded for the same reason the head
            // records it — a report arriving afterwards is the transport
            // catching up, not the connection ending under its caller.
            closeReachedHead = true
            try {
                transport.close()
            } catch (e: Throwable) {
                logger.warn(e) { "transport.close() threw after the close walk" }
            }
        }
        terminate()
    }

    /**
     * The channel's close, on the owning context (or in place under the
     * [claim]). Every step is idempotent on facts, so a close re-entered from
     * anywhere — a drain, a sweep, a walk, a `handlerRemoved` — runs the same
     * sequence and finds the finished steps done:
     *
     * 1. A journal whose drain is still queued is drained first, so the reads
     *    the peer sent reach the assembled chain before the ending, and the
     *    ending precedes the close. Inside a handler frame the drain is owed to
     *    the epilogue instead — the one case in which the close precedes the
     *    ending.
     * 2. The ending ([notifyInactive]), idempotent.
     * 3. The close walk from the tail; owed if one is running.
     * 4. When the walk started no frame — every outbound context had already
     *    heard its close — the release the walk's end would have run.
     */
    internal fun closeOnOwningContext() {
        if (journal == Journal.DRAIN_SCHEDULED) {
            if (frameDepth == 0) drainJournal() else journal = Journal.DRAIN_OWED
        }
        notifyInactive()
        val framed = startTailWalk()
        if (!framed && closeWalk != CloseWalk.RUNNING) afterCloseWalk()
    }

    /**
     * The pipeline's end of life. Nothing runs inside a handler frame: called
     * with frames on the stack it is owed to the outermost frame's epilogue.
     * Then, in order: the journal is settled — drained on a live loop (the
     * transport is closed by now, so only the ending stage has anything left
     * to deliver), discarded on a stopped one; the ending is delivered if it
     * was not (a handler-initiated close reports no read-closed on most
     * engines, so this is where its ending comes from — Netty likewise fires
     * `channelInactive` before `destroy`); and every handler is removed, tail
     * to head, `handlerRemoved` once each ([destroy]). From `DESTROYING` on no
     * sweep or walk starts, and a handler added is served post-mortem
     * ([callHandlerAdded]). Idempotent, never throws.
     */
    internal fun terminate() {
        if (life != Life.LIVE && life != Life.TERMINATE_OWED) return
        if (frameDepth > 0) {
            life = Life.TERMINATE_OWED
            return
        }
        life = Life.ENDING
        settleJournal()
        if (endingPhase != Phase.DELIVERED) startEndingSweep()
        life = Life.DESTROYING
        destroy()
        life = Life.DESTROYED
    }

    private fun settleJournal() {
        when (journal) {
            Journal.DRAINED, Journal.DISCARDED -> {}
            Journal.DISCARD_OWED -> deliverDiscardedLifecycle()
            Journal.FILLING -> discardJournal(JournalDiscard.END_OF_LIFE)
            Journal.DRAIN_SCHEDULED, Journal.DRAIN_OWED -> {
                if (transport.canDispatchToOwningContext) {
                    drainJournal()
                } else {
                    discardJournal(JournalDiscard.OWNING_CONTEXT_STOPPED)
                }
            }
        }
    }

    private fun settleJournalOnStoppedLoop() {
        when (journal) {
            Journal.DRAINED, Journal.DISCARDED -> {}
            Journal.DISCARD_OWED -> deliverDiscardedLifecycle()
            else -> discardJournal(JournalDiscard.OWNING_CONTEXT_STOPPED)
        }
    }

    /**
     * Removes every handler, tail to head — Netty's `destroy`. Each context's
     * `handlerRemoved` runs once, on its transition to `REMOVED`; a context a
     * `handlerRemoved` removed ahead of the walk is skipped. Not interrupted
     * by a throw. Runs with the journal settled and [life] past
     * `TERMINATE_OWED`, so the epilogue that fires between the frames finds
     * nothing owed and never re-enters this walk.
     */
    private fun destroy() {
        var ctx = tail.prev
        while (ctx != null && ctx !== head) {
            val before = ctx.prev
            removeContext(ctx)
            ctx = before
        }
        head.next = tail
        tail.prev = head
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

    /** Latch for [reportDroppedFlush]; owning-context-confined like the rest. */
    private var droppedFlushReported: Boolean = false

    // --- Frames ---

    /**
     * Runs [block] as a handler frame. When the outermost frame returns, the
     * work owed to the epilogue runs ([runOwed]) — after a throw too.
     */
    private inline fun <T> frame(block: () -> T): T {
        frameDepth++
        try {
            return block()
        } finally {
            frameDepth--
            if (frameDepth == 0) runOwed()
        }
    }

    /**
     * The epilogue at frame depth zero, in order: the journal's owed work,
     * then the end of life. Each item is consumed by a state transition
     * before it runs, so the frames it runs itself find nothing left when
     * their own epilogues fire.
     */
    private fun runOwed() {
        when (journal) {
            Journal.DRAIN_OWED -> drainJournal()
            Journal.DISCARD_OWED -> deliverDiscardedLifecycle()
            else -> {}
        }
        if (life == Life.TERMINATE_OWED) terminate()
    }

    // --- Internal ---

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

    /**
     * `handlerAdded` first, before any lifecycle event can reach the handler
     * — a handler sets itself up here, its context most of all. Then the
     * journal: the first inbound handler asks for the drain, on the
     * dispatcher's next tick, or owed to the outermost frame's epilogue on an
     * inline dispatcher, or discarded outright on a stopped loop. Then the
     * replay of what the chain already heard, to this handler alone
     * ([replayLifecycleTo]).
     *
     * **Post-mortem.** A handler added once the end of life is destroying the
     * pipeline (or has) is served a complete lifecycle inside this call:
     * `handlerAdded`, the ending, `handlerRemoved` — and is unlinked again.
     * The callers that add after a close hold their handler by reference (the
     * upgrade bridges), and see it end. Only `addFirst` / `addLast` get here
     * then: the pipeline is empty, so `addBefore` / `addAfter` / `replace`
     * find no base handler and throw before reaching this.
     */
    private fun callHandlerAdded(ctx: DefaultContext) {
        frame {
            try {
                ctx.handler.handlerAdded(ctx)
            } catch (e: Throwable) {
                logger.error(e) { "handlerAdded() threw for '${ctx.name}'" }
            }
        }
        if (destroying) {
            if (ctx.handler is InboundHandler) ctx.deliverInactive(Mode.REPLAY)
            removeContext(ctx)
            return
        }
        // Any add that finds the loop stopped gives the journal up, whatever
        // state it is in: a drain already scheduled there is never going to
        // run either, and this handler is the last chance to notice.
        if (!transport.canDispatchToOwningContext && !journalSettled) {
            discardJournal(JournalDiscard.OWNING_CONTEXT_STOPPED)
        } else if (journal == Journal.FILLING && ctx.handler is InboundHandler) {
            askForDrain()
        }
        if (ctx.handler is InboundHandler) replayLifecycleTo(ctx)
    }

    /**
     * The first inbound handler is in: the journal has someone to drain to.
     * Deferred onto the dispatcher so any add remaining in the current
     * synchronous block accumulates before the drain replays through the
     * assembled chain; on an inline dispatcher (`Dispatchers.Unconfined`
     * throws from `dispatch()` by design) run now, or owed to the epilogue
     * when a handler frame — a `handlerAdded` installing the rest of its stack
     * — is still on the stack. A stopped loop gets no drain: the reads are
     * released rather than stranded, and the observed lifecycle is delivered
     * in place.
     */
    private fun askForDrain() {
        if (!transport.canDispatchToOwningContext) {
            discardJournal(JournalDiscard.OWNING_CONTEXT_STOPPED)
        } else if (ioDispatcher.isDispatchNeeded(EmptyCoroutineContext)) {
            journal = Journal.DRAIN_SCHEDULED
            ioDispatcher.dispatch(EmptyCoroutineContext, Runnable { drainJournal() })
        } else {
            journal = Journal.DRAIN_OWED
            if (frameDepth == 0) drainJournal()
        }
    }

    private fun callHandlerRemoved(ctx: DefaultContext) {
        frame {
            try {
                ctx.handler.handlerRemoved(ctx)
            } catch (e: Throwable) {
                logger.error(e) { "handlerRemoved() threw for '${ctx.name}'" }
            }
        }
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
     * from head toward tail, skipping non-[InboundHandler] and removed nodes.
     *
     * **Outbound navigation** ([findPrevOutbound]): follows [prev] pointers
     * from tail toward head, skipping non-[OutboundHandler] and removed nodes.
     *
     * **Delivery** (`deliverX`): transition-then-invoke for the lifecycle
     * events, keyed on this context's own state — so each reaches the handler
     * at most once. **Invoke methods** (`invokeOnX`): wrap handler callbacks
     * with try-catch to prevent IoBuf leaks on exceptions; [invokeOnRead]
     * releases the message on exception; [invokeOnError] logs the secondary
     * exception to avoid infinite error propagation loops.
     */
    internal class DefaultContext(
        private val pipelineRef: DefaultPipeline,
        override val name: String,
        override val handler: PipelineHandler,
    ) : PipelineHandlerContext {

        /**
         * Previous node toward HEAD (outbound direction). Kept after the
         * context is removed, so a walk passing through the handler when it
         * removes itself still finds its way; see [DefaultPipeline.removeContext].
         */
        var prev: DefaultContext? = null

        /** Next node toward TAIL (inbound direction). Kept after removal, like [prev]. */
        var next: DefaultContext? = null

        /**
         * This context's lifecycle. `PENDING → ACTIVE → ENDED`, or straight
         * to `ENDED` when the ending is the first thing it hears; `REMOVED`
         * is terminal. An outbound-only context stays `PENDING` until removed.
         */
        var lifecycle: Lifecycle = Lifecycle.PENDING

        /** Whether this context heard the peer's end of file; once, between activation and ending. */
        var readClosedHeard: Boolean = false

        /** Whether this (outbound) context has heard its close; once. */
        var closeDelivered: Boolean = false

        /**
         * The last writability the chain delivered to this handler, or null.
         * A replay tells a handler the current value only if it has not heard
         * it — a genuine change raised from inside its replayed activation
         * already did.
         */
        var writabilityHeard: Boolean? = null

        override val channel: PipelinedChannel get() = pipelineRef.channel
        override val pipeline: Pipeline get() = pipelineRef
        override val allocator: BufferAllocator get() = channel.allocator

        // --- Inbound propagation ---

        override fun propagateActive() {
            if (heldBack(pipelineRef.activeCursor)) return
            findNextInbound()?.deliverActive(Mode.SWEEP)
        }

        override fun propagateRead(msg: Any) {
            val nextCtx = findNextInbound() ?: return
            nextCtx.invokeOnRead(msg)
        }

        override fun propagateReadComplete() {
            val nextCtx = findNextInbound() ?: return
            nextCtx.invokeOnReadComplete()
        }

        override fun propagateFlushComplete() {
            val nextCtx = findNextInbound() ?: return
            nextCtx.invokeOnFlushComplete()
        }

        override fun propagateReadClosed() {
            if (heldBack(pipelineRef.readClosedCursor)) return
            findNextInbound()?.deliverReadClosed(Mode.SWEEP)
        }

        override fun propagateInactive() {
            if (heldBack(pipelineRef.inactiveCursor)) return
            findNextInbound()?.deliverInactive(Mode.SWEEP)
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
            if (heldBack(pipelineRef.writabilityCursor)) return
            findNextInbound()?.deliverWritability(isWritable, Mode.SWEEP)
        }

        /**
         * Records on the sweep's cursor that this handler propagated, and
         * answers whether the propagation is held back: it is when the event
         * is being replayed to this handler alone. For the once-only
         * activation and ending the contexts below would stop it anyway; for
         * writability, gated by value, it is what keeps a replayed value from
         * arriving below after a genuine change raised from inside the replay.
         */
        private fun heldBack(cursor: Cursor?): Boolean {
            if (cursor == null || cursor.ctx !== this) return false
            cursor.propagated = true
            return cursor.mode == Mode.REPLAY
        }

        // --- Outbound propagation ---

        // The chain walk (findPrevOutbound) runs *inside* onOwningContext, not
        // before it. prev/next are non-volatile and EventLoop-confined, so an
        // off-loop emitter that resolved the previous context on its own thread
        // could read a link the loop is concurrently mutating. Resolving on the
        // owning context closes that. A removed context keeps its links, so a
        // handler writing after its removal — an asynchronous completion — still
        // reaches the transport, as it does in Netty; only the head's own
        // context resolves to no previous, and a write from there is released
        // rather than dropped.

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

        /**
         * From inside this handler's own close — the walk is on the stack, on
         * this thread — the next hop runs inline. From anywhere else it is a
         * close the handler initiates from its own context (the Netty
         * `ctx.close()` idiom): the walk from here toward the head, run where a
         * close request can run.
         */
        override fun propagateClose() {
            val cursor = pipelineRef.closeCursor
            if (cursor != null && cursor.ctx === this) {
                cursor.propagated = true
                pipelineRef.deliverCloseFrom(prev)
                return
            }
            pipelineRef.onOwningContextForClose { pipelineRef.deliverCloseFrom(prev) }
        }

        // --- Lifecycle delivery (transition-then-invoke) ---
        //
        // A lifecycle sweep — activation, ending, close — does not stop at a
        // handler that throws: the throw goes on as an error, and the event
        // goes on from there, propagated by the pipeline if the handler had
        // not done it. Netty stops (`fireChannelInactive` catches and only
        // reports), and can, since nothing in a Netty pipeline joins a
        // registry the server drains on stop; here the server's handler does
        // exactly that on these two events, so a throw above it that ended
        // the sweep left the connection registered after it was gone, or
        // never registered at all. Data events stay as they are: a read the
        // handler threw on is released and reaches nobody below. A replay is
        // to this handler alone, so a throw there is only logged.

        /**
         * The activation. Not delivered once the connection is over: after
         * the ending was delivered, or after the transport closed — an
         * activation after either would be a lie. An ending merely observed
         * ahead of the drain is neither: the drain delivers the activation
         * first and the ending after.
         */
        fun deliverActive(mode: Mode) {
            if (lifecycle != Lifecycle.PENDING) return
            if (pipelineRef.endingPhase == Phase.DELIVERED || !pipelineRef.transport.isOpen) return
            val h = handler as? InboundHandler ?: return
            lifecycle = Lifecycle.ACTIVE
            val cursor = Cursor(this, mode)
            val outer = pipelineRef.activeCursor
            pipelineRef.activeCursor = cursor
            pipelineRef.frame {
                try {
                    h.onActive(this)
                } catch (e: Throwable) {
                    if (mode == Mode.REPLAY) {
                        pipelineRef.logger.error(e) { "onActive() replay threw for '$name'" }
                    } else {
                        propagateError(e)
                        if (!cursor.propagated) propagateActive()
                    }
                } finally {
                    pipelineRef.activeCursor = outer
                }
            }
        }

        /**
         * The peer's end of file: once per context, only to a context that
         * heard the activation and not the ending, and not once the
         * descriptor is gone — then the ending is what there is to say.
         */
        fun deliverReadClosed(mode: Mode) {
            if (lifecycle != Lifecycle.ACTIVE || readClosedHeard) return
            if (pipelineRef.endingPhase == Phase.DELIVERED || !pipelineRef.transport.isOpen) return
            val h = handler as? InboundHandler ?: return
            readClosedHeard = true
            val cursor = Cursor(this, mode)
            val outer = pipelineRef.readClosedCursor
            pipelineRef.readClosedCursor = cursor
            pipelineRef.frame {
                try {
                    h.onReadClosed(this)
                } catch (e: Throwable) {
                    if (mode == Mode.REPLAY) {
                        pipelineRef.logger.error(e) { "onReadClosed() replay threw for '$name'" }
                    } else {
                        propagateError(e)
                        if (!cursor.propagated) propagateReadClosed()
                    }
                } finally {
                    pipelineRef.readClosedCursor = outer
                }
            }
        }

        /** The ending: once per context, and possibly the first thing it hears. */
        fun deliverInactive(mode: Mode) {
            if (lifecycle == Lifecycle.ENDED || lifecycle == Lifecycle.REMOVED) return
            val h = handler as? InboundHandler ?: return
            lifecycle = Lifecycle.ENDED
            val cursor = Cursor(this, mode)
            val outer = pipelineRef.inactiveCursor
            pipelineRef.inactiveCursor = cursor
            pipelineRef.frame {
                try {
                    h.onInactive(this)
                } catch (e: Throwable) {
                    if (mode == Mode.REPLAY) {
                        pipelineRef.logger.error(e) { "onInactive() replay threw for '$name'" }
                    } else {
                        // The reason first, then the ending it interrupted.
                        propagateError(e)
                        if (!cursor.propagated) propagateInactive()
                    }
                } finally {
                    pipelineRef.inactiveCursor = outer
                }
            }
        }

        /**
         * A writability the handler has not heard. The same value again stops
         * the walk: transports report transitions only, so a context that
         * already holds the value has nothing newer below it.
         */
        fun deliverWritability(isWritable: Boolean, mode: Mode) {
            if (lifecycle == Lifecycle.REMOVED || writabilityHeard == isWritable) return
            val h = handler as? InboundHandler ?: return
            writabilityHeard = isWritable
            val cursor = Cursor(this, mode)
            val outer = pipelineRef.writabilityCursor
            pipelineRef.writabilityCursor = cursor
            pipelineRef.frame {
                try {
                    h.onWritabilityChanged(this, isWritable)
                } catch (e: Throwable) {
                    if (mode == Mode.REPLAY) {
                        pipelineRef.logger.error(e) { "onWritabilityChanged() replay threw for '$name'" }
                    } else {
                        propagateError(e)
                        if (!cursor.propagated) propagateWritabilityChanged(isWritable)
                    }
                } finally {
                    pipelineRef.writabilityCursor = outer
                }
            }
        }

        /** Whether the close walk delivers to this context: outbound, linked, and not yet told. */
        fun acceptsClose(): Boolean =
            lifecycle != Lifecycle.REMOVED && handler is OutboundHandler && !closeDelivered

        /**
         * The close, to an outbound context that [acceptsClose]. Counts as a
         * frame of the walk; the outermost one's return is DONE and runs the
         * release ([DefaultPipeline.afterCloseWalk]).
         */
        fun invokeOnClose() {
            val h = handler as OutboundHandler
            closeDelivered = true
            val cursor = Cursor(this, Mode.SWEEP)
            val outer = pipelineRef.closeCursor
            pipelineRef.closeCursor = cursor
            pipelineRef.closeDepth++
            if (pipelineRef.closeDepth == 1) pipelineRef.closeWalk = CloseWalk.RUNNING
            pipelineRef.frame {
                try {
                    h.onClose(this)
                } catch (e: Throwable) {
                    propagateError(e)
                    if (!cursor.propagated) propagateClose()
                } finally {
                    pipelineRef.closeCursor = outer
                    pipelineRef.closeDepth--
                    if (pipelineRef.closeDepth == 0) {
                        pipelineRef.closeWalk = CloseWalk.DONE
                        pipelineRef.afterCloseWalk()
                    }
                }
            }
        }

        // --- Invoke with try-catch (leak prevention) ---

        internal fun invokeOnRead(msg: Any) {
            val h = handler
            if (h is InboundHandler) {
                pipelineRef.frame {
                    try {
                        h.onRead(this, msg)
                    } catch (e: Throwable) {
                        ReferenceCountUtil.safeRelease(msg)
                        propagateError(e)
                    }
                }
            } else {
                propagateRead(msg)
            }
        }

        internal fun invokeOnReadComplete() {
            val h = handler
            if (h is InboundHandler) {
                pipelineRef.frame {
                    try {
                        h.onReadComplete(this)
                    } catch (e: Throwable) {
                        propagateError(e)
                    }
                }
            } else {
                propagateReadComplete()
            }
        }

        internal fun invokeOnFlushComplete() {
            val h = handler
            if (h is InboundHandler) {
                pipelineRef.frame {
                    try {
                        h.onFlushComplete(this)
                    } catch (e: Throwable) {
                        propagateError(e)
                    }
                }
            } else {
                propagateFlushComplete()
            }
        }

        internal fun invokeOnError(cause: Throwable) {
            val h = handler
            if (h is InboundHandler) {
                pipelineRef.frame {
                    try {
                        h.onError(this, cause)
                    } catch (e: Throwable) {
                        pipelineRef.logger.error(e) {
                            "onError() threw in '$name' while handling: $cause"
                        }
                    }
                }
            } else {
                propagateError(cause)
            }
        }

        internal fun invokeOnUserEvent(event: Any) {
            val h = handler
            if (h is InboundHandler) {
                pipelineRef.frame {
                    try {
                        h.onUserEvent(this, event)
                    } catch (e: Throwable) {
                        propagateError(e)
                    }
                }
            } else {
                propagateUserEvent(event)
            }
        }

        internal fun invokeOnWrite(msg: Any) {
            val h = handler
            if (h is OutboundHandler) {
                pipelineRef.frame {
                    try {
                        h.onWrite(this, msg)
                    } catch (e: Throwable) {
                        ReferenceCountUtil.safeRelease(msg)
                        propagateError(e)
                    }
                }
            } else {
                propagateWrite(msg)
            }
        }

        internal fun invokeOnFlush() {
            val h = handler
            if (h is OutboundHandler) {
                pipelineRef.frame {
                    try {
                        h.onFlush(this)
                    } catch (e: Throwable) {
                        propagateError(e)
                    }
                }
            } else {
                propagateFlush()
            }
        }

        // --- Navigation ---

        private fun findNextInbound(): DefaultContext? {
            var ctx = next
            while (ctx != null) {
                if (ctx.lifecycle != Lifecycle.REMOVED && ctx.handler is InboundHandler) return ctx
                ctx = ctx.next
            }
            return null
        }

        private fun findPrevOutbound(): DefaultContext? {
            var ctx = prev
            while (ctx != null) {
                if (ctx.lifecycle != Lifecycle.REMOVED && ctx.handler is OutboundHandler) return ctx
                ctx = ctx.prev
            }
            return null
        }

        /** Whether following [next] from here reaches [target]. */
        fun leadsTo(target: DefaultContext): Boolean {
            var ctx = next
            while (ctx != null) {
                if (ctx === target) return true
                ctx = ctx.next
            }
            return false
        }
    }

    /**
     * Replays the lifecycle the chain already heard to a late-added inbound
     * handler, alone: its default propagation of the replayed event is held
     * back, since the handlers below heard it when it swept the chain. The
     * ending wins over the activation — a handler joining an ended connection
     * observes `onInactive` and cleans up at once. After a replayed activation
     * the current writability is told too, if the handler has not heard it and
     * the connection did not end from inside the activation.
     *
     * Nothing is replayed while the journal will deliver it (the drain sweeps
     * the assembled chain), or while a running sweep has yet to reach the new
     * context — the sweep delivers it, in sweep mode, throw and all.
     */
    private fun replayLifecycleTo(ctx: DefaultContext) {
        when (journal) {
            Journal.DRAINED, Journal.DISCARDED -> {}
            else -> return
        }
        val cursor = when {
            endingPhase == Phase.DELIVERED -> inactiveCursor
            activationPhase == Phase.DELIVERED -> activeCursor
            else -> null
        }
        if (cursor != null && cursor.stillReaches(ctx)) return
        if (endingPhase == Phase.DELIVERED) {
            ctx.deliverInactive(Mode.REPLAY)
            return
        }
        if (activationPhase != Phase.DELIVERED) return
        ctx.deliverActive(Mode.REPLAY)
        if (ctx.lifecycle != Lifecycle.ACTIVE || endingPhase == Phase.DELIVERED) return
        // The peer's end of file, if the chain heard it and no running sweep
        // will still bring it here.
        if (readClosedPhase == Phase.DELIVERED && readClosedCursor?.stillReaches(ctx) != true) {
            // Who owns the connection is asked again, and asked before the
            // handler hears the event — the same order the sweep uses, and
            // for the same reason: a handler that removes itself from inside
            // its own callback must not answer for the chain it is joining.
            // It was answered once when the sweep ran, and a handler arriving
            // after that changes it: a chain that was empty then — nobody's,
            // so nothing closed — is keel's now, and the descriptor is
            // keel's to release. Asked here rather than left to the ending,
            // because there is no ending coming; the close delivers one.
            val joinedPipelineMode = pipelineModeNow?.invoke() ?: false
            ctx.deliverReadClosed(Mode.REPLAY)
            onReadClosedDelivered?.invoke(joinedPipelineMode)
            if (ctx.lifecycle != Lifecycle.ACTIVE || endingPhase == Phase.DELIVERED) return
        }
        // Not while a writability sweep still reaches the context: the sweep
        // delivers the value, and a replay ahead of it would make the sweep
        // stop at this context's own record, hiding the change from the
        // handlers below.
        if (writabilityCursor?.stillReaches(ctx) == true) return
        writabilityCurrent?.let { ctx.deliverWritability(it, Mode.REPLAY) }
    }

    /**
     * Drains the pre-attach event journal onto the assembled chain: the
     * activation, the reads in arrival order, the batch boundary, the flush
     * completions, the writability, the user events, the errors, the peer's
     * end of file if it was observed, and the ending if it was observed. Marked `DRAINED` at the *start*, so an add
     * from inside a replayed event bypasses the journal — it is one-shot — and
     * a read arriving meanwhile goes straight through the head.
     *
     * The one drain: the dispatched task, the channel's close, the epilogue
     * owed on an inline dispatcher and the end of life all run this. Each
     * data stage and element is delivered only while the connection is not
     * over ([ended]); after that reads are released, the rest dropped, and only
     * the ending is delivered — no data after the descriptor is gone. A
     * journalled error is delivered unless the ending already was: it is the
     * reason, and precedes the end. The journalled writability is published only if no real report
     * arrived since the drain began: a handler told the new value from inside
     * the drain is not told the old one after it.
     */
    private fun drainJournal() {
        when (journal) {
            Journal.DRAIN_SCHEDULED, Journal.DRAIN_OWED -> {}
            else -> return
        }
        journal = Journal.DRAINED
        if (activationPhase == Phase.OBSERVED) startActivationSweep()
        drainReadSide()
        drainWriteSide()
        drainUserEvents()
        drainErrors()
        // The peer's end of file last, after everything else the journal
        // holds: in Pipeline mode its delivery closes the channel, and a
        // flush completion, a user event or a reason journalled before the
        // FIN was reported would otherwise be dropped by the ending it
        // brings — the reason is owed to the handlers before the end.
        if (readClosedPhase == Phase.OBSERVED && !ended) startReadClosedSweep()
        if (endingPhase == Phase.OBSERVED) startEndingSweep()
    }

    /** The reads in arrival order, then the batch boundary. */
    private fun drainReadSide() {
        while (pendingReads.isNotEmpty()) {
            val msg = pendingReads.removeFirst()
            if (ended) ReferenceCountUtil.safeRelease(msg) else head.invokeOnRead(msg)
        }
        if (pendingReadComplete) {
            pendingReadComplete = false
            if (!ended) head.invokeOnReadComplete()
        }
    }

    /** The flush completions, then the journalled writability if no real report came first. */
    private fun drainWriteSide() {
        while (pendingFlushCompletions > 0) {
            pendingFlushCompletions--
            if (!ended) head.invokeOnFlushComplete()
        }
        pendingWritability?.let { writable ->
            pendingWritability = null
            if (writabilityCurrent == null) {
                writabilityCurrent = writable
                if (!ended) head.deliverWritability(writable, Mode.SWEEP)
            }
        }
    }

    private fun drainUserEvents() {
        while (pendingUserEvents.isNotEmpty()) {
            val event = pendingUserEvents.removeFirst()
            if (!ended) head.invokeOnUserEvent(event)
        }
    }

    /**
     * The journalled errors. Gated by the ending's delivery alone, not by
     * the descriptor: a reason is not data. The transport reports the
     * failure that ends a connection before it reports the ending, and
     * closes itself in between; the head stayed quiet about that failure
     * because this drain was going to hand it over, so it is handed over,
     * still before the end.
     */
    private fun drainErrors() {
        while (pendingErrors.isNotEmpty()) {
            val cause = pendingErrors.removeFirst()
            if (endingPhase == Phase.DELIVERED) {
                logger.warn(cause) { "A journalled error arrived after the connection ended; no handler can act on it" }
            } else {
                head.invokeOnError(cause)
            }
        }
    }

    /**
     * Gives up on the journal: nothing is going to drain it. The reads are
     * released, the errors reported with their cause (dropping the only record
     * of why a connection failed is the silent failure this codebase forbids),
     * the rest dropped, and the observed lifecycle delivered from the head in
     * place — the activation only if no ending was observed and the transport
     * is still open, then the ending. Inside a handler frame the delivery is
     * owed to the epilogue (`DISCARD_OWED`), so a handler is not swept from
     * inside its own `handlerAdded`.
     *
     * Only the reads are *released*; the user events are dropped without one.
     * The difference is ownership: [notifyRead] takes over its message,
     * whereas [notifyUserEvent] does not, and releasing an event here would
     * free something the emitter may still hold.
     */
    private fun discardJournal(reason: JournalDiscard) {
        when (journal) {
            Journal.FILLING, Journal.DRAIN_SCHEDULED, Journal.DRAIN_OWED -> {}
            else -> return
        }
        pendingWritability?.let { writable ->
            pendingWritability = null
            writabilityCurrent = writable
        }
        val reads = pendingReads.size
        val events = pendingUserEvents.size
        val errors = pendingErrors.size
        while (pendingReads.isNotEmpty()) {
            ReferenceCountUtil.safeRelease(pendingReads.removeFirst())
        }
        pendingUserEvents.clear()
        pendingReadComplete = false
        pendingFlushCompletions = 0
        while (pendingErrors.isNotEmpty()) {
            val cause = pendingErrors.removeFirst()
            logger.warn(cause) { "Discarded a journalled error — ${reason.because}" }
        }
        if (reads > 0 || events > 0 || errors > 0) {
            // A stopped loop is worth a warning: the connection was still
            // being assembled. The end of life is not — a channel closed
            // before anything read from it is an ordinary way for one to end.
            val line = {
                "Discarded the pre-attach journal ($reads read(s), $events user event(s), $errors error(s)) " +
                    "— ${reason.because}"
            }
            if (reason == JournalDiscard.OWNING_CONTEXT_STOPPED) logger.warn { line() } else logger.debug { line() }
        }
        if (frameDepth > 0) journal = Journal.DISCARD_OWED else deliverDiscardedLifecycle()
    }

    /** The delivery a discard owes: the observed lifecycle, swept from the head. */
    private fun deliverDiscardedLifecycle() {
        journal = Journal.DISCARDED
        // Stated here as well as in `deliverActive`'s own gate: no activation
        // on a connection that ended or whose descriptor is gone.
        if (activationPhase == Phase.OBSERVED && endingPhase == Phase.NONE && transport.isOpen) startActivationSweep()
        if (readClosedPhase == Phase.OBSERVED && endingPhase == Phase.NONE && transport.isOpen) startReadClosedSweep()
        if (endingPhase == Phase.OBSERVED) startEndingSweep()
    }
}

// --- File-level state and helpers of DefaultPipeline (kept out of the class for its size) ---

/** Whether the transport reported the event, and whether the sweep from the head has started. */
private enum class Phase { NONE, OBSERVED, DELIVERED }

/** The close walk: running while a close delivery that invoked a handler is on the stack. */
private enum class CloseWalk { NONE, RUNNING, DONE }

/**
 * The pre-attach journal. `DRAIN_SCHEDULED`: a drain is queued on the
 * dispatcher. `DRAIN_OWED`: an inline dispatcher, the drain runs when the
 * outermost handler frame returns. `DISCARD_OWED`: the reads are already
 * released, the lifecycle delivery is owed to the same epilogue.
 */
private enum class Journal { FILLING, DRAIN_SCHEDULED, DRAIN_OWED, DRAINED, DISCARD_OWED, DISCARDED }

/** Why the pre-attach journal was released without being drained. */
private enum class JournalDiscard(val because: String) {
    OWNING_CONTEXT_STOPPED(
        "a handler was added, or a close requested, after this connection's owning context stopped, " +
            "so the deferred replay would never have run",
    ),
    END_OF_LIFE("the channel's close released the transport before any inbound handler was installed"),
}

/**
 * Where a lifecycle sweep is: the context whose handler is being invoked,
 * how (sweep or replay), and whether it has propagated yet. A handler
 * installed below a sweep that has not propagated is reached by the
 * sweep and not replayed to as well; a handler that throws without
 * propagating has the event propagated on its behalf; a replayed event's
 * default propagation is held back ([DefaultContext.heldBack]). Nested
 * sweeps restore the outer cursor.
 */
private class Cursor(val ctx: DefaultPipeline.DefaultContext, val mode: DefaultPipeline.Mode) {
    var propagated: Boolean = false

    /** Whether this sweep has yet to reach [target]: it lies ahead of a handler that has not propagated. */
    fun stillReaches(target: DefaultPipeline.DefaultContext): Boolean =
        mode == DefaultPipeline.Mode.SWEEP && !propagated && ctx.leadsTo(target)
}

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

private fun insertBetween(
    before: DefaultPipeline.DefaultContext,
    new: DefaultPipeline.DefaultContext,
    after: DefaultPipeline.DefaultContext,
) {
    before.next = new
    new.prev = before
    new.next = after
    after.prev = new
}

private fun nameOf(head: DefaultPipeline.DefaultContext, handler: PipelineHandler): String {
    var ctx: DefaultPipeline.DefaultContext? = head
    while (ctx != null) {
        if (ctx.handler === handler) return ctx.name
        ctx = ctx.next
    }
    return handler::class.simpleName ?: "unknown"
}

/**
 * Validates inbound type chain between adjacent handlers.
 *
 * Skipped when either handler is not a [InboundHandler] or when
 * either type is [Any] (opt-out default).
 */
private fun validateInboundTypeChain(
    head: DefaultPipeline.DefaultContext,
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
            "Type mismatch in pipeline: '${nameOf(head, prevHandler)}' produces " +
                "${produced.simpleName} but '$nextName' accepts ${accepted.simpleName}",
        )
    }
}
