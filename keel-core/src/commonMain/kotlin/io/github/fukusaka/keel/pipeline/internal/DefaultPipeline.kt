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
    private val logger: Logger,
) : Pipeline {

    private val head: DefaultContext = DefaultContext(this, "HEAD", HeadHandler(transport))
    private val tail: DefaultContext = DefaultContext(this, "TAIL", TailHandler(logger))

    /**
     * Dispatcher captured from the underlying [IoTransport]. Used by the
     * pre-attach event journal to schedule [drainPreAttachJournal] on the
     * next dispatcher tick after the first user [InboundHandler] is
     * added — see the [PreAttachJournal] doc below for the rationale.
     */
    private val ioDispatcher: CoroutineDispatcher = transport.ioDispatcher

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
     * Tracks whether `head.invokeOnInactive` has actually propagated
     * through the inbound chain. Distinct from [inactiveObserved],
     * which only records "the engine reported inactive". Per-handler
     * inactive replay in [callHandlerAdded] fires only when both flags
     * are true — i.e. inactive arrived *and* the chain has already been
     * notified, so the late-added handler genuinely missed the
     * propagation. When inactive arrives during the pre-attach window
     * (`inactiveObserved` set but [drainPreAttachJournal] has not yet
     * fired the head), the drain will deliver `onInactive` through
     * the now-assembled chain and the per-handler replay would
     * otherwise double-fire.
     */
    private var inactiveHeadFired: Boolean = false

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
            head.invokeOnActive()
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
            inactiveHeadFired = true
            head.invokeOnInactive()
        }
        // Pre-attach: the inactiveObserved flag is sufficient; drain replays
        // it via head.invokeOnInactive at drain time and sets
        // [inactiveHeadFired].
        return this
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
        tail.invokeOnWrite(msg)
        return this
    }

    override fun requestFlush(): Pipeline {
        tail.invokeOnFlush()
        return this
    }

    override fun requestClose(): Pipeline {
        tail.invokeOnClose()
        return this
    }

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
            // Defer drain via the dispatcher so any addX calls remaining
            // in the current synchronous block (e.g. codec stack setup
            // adding decoder + aggregator + handler back-to-back) all
            // accumulate before drain replays through the assembled
            // chain. Test transports backed by `Dispatchers.Unconfined`
            // — which throws from `dispatch()` by design (Unconfined is
            // meant for inline execution) — fall back to inline drain;
            // unit tests typically add a single handler before
            // `notifyXxx`, so partial-chain replay does not arise.
            if (ioDispatcher.isDispatchNeeded(EmptyCoroutineContext)) {
                ioDispatcher.dispatch(EmptyCoroutineContext, Runnable { drainPreAttachJournal() })
            } else {
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
            if (inactiveObserved && inactiveHeadFired && !firedDrainInline) {
                // The chain has already received head.invokeOnInactive
                // (either via notifyInactive after drain, or via drain
                // itself). Replay onInactive on this lone late-added
                // handler so it does not miss the lifecycle event.
                // Pre-drain notifyInactive (inactiveObserved without
                // inactiveHeadFired) skips this branch — the journal
                // drain will deliver onInactive through the now-assembled
                // chain including this handler. The [firedDrainInline]
                // exclusion handles the inline-drain path: the drain
                // that just ran above already propagated onInactive
                // through this handler via head, so per-handler replay
                // would double-fire.
                val handler = ctx.handler
                if (handler is InboundHandler) {
                    try {
                        handler.onInactive(ctx)
                    } catch (e: Throwable) {
                        logger.error(e) { "onInactive() replay threw for '${ctx.name}'" }
                    }
                }
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

        override fun propagateWrite(msg: Any) {
            val prevCtx = findPrevOutbound() ?: return
            prevCtx.invokeOnWrite(msg)
        }

        override fun propagateFlush() {
            val prevCtx = findPrevOutbound() ?: return
            prevCtx.invokeOnFlush()
        }

        override fun propagateClose() {
            val prevCtx = findPrevOutbound() ?: return
            prevCtx.invokeOnClose()
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
            inactiveHeadFired = true
            head.invokeOnInactive()
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
