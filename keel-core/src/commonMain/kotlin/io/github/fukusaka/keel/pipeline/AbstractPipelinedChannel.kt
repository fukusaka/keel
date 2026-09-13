package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.warn
import io.github.fukusaka.keel.pipeline.internal.DefaultPipeline
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.concurrent.Volatile
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Which side ended a connection, as the channel recorded it.
 *
 * A connection ends once, but both sides say so afterwards, so the channel
 * keeps the first answer and a reader gets the cause rather than the order
 * the reports happened to arrive in.
 */
internal enum class EndCause {
    /** Still open, or ended without either side recording it. */
    NONE,

    /** This side asked: the caller's close, the pipeline's, or a handler's. */
    THIS_SIDE,

    /** The transport reported it: a reset, a failure, an idle reclamation, a stopped loop. */
    TRANSPORT,
}

/**
 * Base class for all engine [PipelinedChannel] implementations.
 *
 * Wires the [IoTransport] callbacks to the [Pipeline] and provides
 * default implementations for all [PipelinedChannel] methods by delegating
 * to the transport. Engine subclasses only need to override engine-specific
 * members (if any).
 *
 * ```
 * Engine creates:
 *   val transport = ReadinessIoTransport(fd, eventLoop, allocator)
 *   val channel = ReadinessPipelinedChannel(transport, logger, remote, local)
 *
 * AbstractPipelinedChannel wires:
 *   (construction)                 → pipeline.notifyActive()
 *   transport.onRead               → pipeline.notifyRead(buf)
 *   transport.onReadComplete       → pipeline.notifyReadComplete()
 *   transport.onFlushComplete      → pipeline.notifyFlushComplete()
 *   transport.onReadClosed         → pipeline.notifyReadClosed()
 *                                    — the descriptor already gone → close()
 *                                    — from a transport that reports every
 *                                      end this way (each one in this tree
 *                                      but its own tests' doubles)
 *                                      → pipeline.notifyInactive() +
 *                                        (Pipeline-mode) close()
 *   pipeline delivered onReadClosed → (Pipeline-mode) close()
 *   transport.onClosed             → close()
 *   transport.onConnectionFailure  → pipeline error path
 *   transport.onWritabilityChanged → pipeline.notifyWritabilityChanged()
 *   ensureBridge()                 → installs SuspendBridgeHandler (no read arming)
 *   readEnabled                    → transport.readEnabled
 *   shutdownOutput()               → transport.shutdownOutput()
 *   close()                        → the pipeline's close sequence: drain a still-queued
 *                                    journal, the ending, the close walk, the release,
 *                                    then the pipeline's end of life (every handler removed)
 * ```
 */
abstract class AbstractPipelinedChannel(
    val transport: IoTransport,
    override val logger: Logger,
    override val remoteAddress: SocketAddress? = null,
    override val localAddress: SocketAddress? = null,
) : PipelinedChannel {

    private val defaultPipeline: DefaultPipeline = DefaultPipeline(this, transport, logger)

    override val pipeline: Pipeline get() = defaultPipeline
    override val allocator: BufferAllocator get() = transport.allocator
    override val isActive: Boolean get() = transport.isOpen
    override val isOpen: Boolean get() = transport.isOpen

    /**
     * What ended this connection, written by whichever side ended it first
     * and not overwritten afterwards.
     *
     * A connection ends once. Both sides can then go on to say so — a close
     * this side began still reaches the transport, and a transport whose
     * descriptor is already going still reports what it saw — so the first
     * writer is the one that answers, and everything after it is the other
     * side catching up. Written at the ask on this side ([close], the
     * pipeline's `requestClose`, a handler's `propagateClose`) and at the
     * report on the transport's, both before [isOpen] turns false, so a
     * reader that arrives afterwards reads a cause that is already settled.
     *
     * Volatile because [read] reads it from the caller's thread, the same
     * way [isOpen] reads the transport's own flag.
     */
    @Volatile
    internal var endCause: EndCause = EndCause.NONE

    /**
     * A read after the connection ended under the caller — a reset, a failed
     * read or write, a reclamation, a stopped loop, after which the channel
     * closed itself — is the end of file: the reader that was away for that
     * moment is told what the parked one was, nothing more to read. A read
     * after a close this side performed — the caller's own [close], a
     * handler closing the channel from the chain, or the channel's own
     * close after the peer's end of file in Pipeline mode — is the misuse
     * the base refuses. The base decides from one reading of [isOpen]; this
     * is the other half of that reading.
     */
    override val endedByTransport: Boolean get() = endCause == EndCause.TRANSPORT
    override val isWritable: Boolean get() = transport.isOpen && transport.isWritable
    override val ioDispatcher: CoroutineDispatcher get() = transport.ioDispatcher

    /** Delegates to the transport's EventLoop timer (the idle-timeout scheduler). */
    override fun scheduleDeadline(delayMillis: Long, task: () -> Unit): TimerHandle? =
        transport.scheduleDeadline(delayMillis, task)

    override var readEnabled: Boolean
        get() = transport.readEnabled
        set(value) { transport.readEnabled = value }

    override fun pauseReads() = transport.pauseReads()

    override fun resumeReads() = transport.resumeReads()

    private var bridge: SuspendBridgeHandler? = null

    init {
        transport.onWritabilityChanged = { writable ->
            pipeline.notifyWritabilityChanged(writable)
        }
        transport.onRead = { buf ->
            pipeline.notifyRead(buf)
        }
        // The batch boundary. `notifyReadComplete` has been on [Pipeline]
        // since it was written and had no caller, so `onReadComplete` never
        // ran on any connection — a handler that wanted to answer a burst
        // with one flush had nothing to hang it on.
        transport.onReadComplete = {
            pipeline.notifyReadComplete()
        }
        // The answer to a flush, and until now every transport that raised one
        // raised it into a null: nothing in production ever assigned this, so a
        // handler that wanted to know its bytes had gone — to release what it
        // held for them, to let a producer continue — had nothing to ask.
        //
        // Which flushes get an answer is the transport's business and is not
        // uniform: the readiness engines report per drained episode and fold a
        // reentrant one, io-uring's synchronous fast path returns without
        // reporting, nio reports from the tick its coalescing schedules and so
        // not at all with coalescing off. A handler treats a completion as an
        // opportunity, never as a turn it is owed.
        transport.onFlushComplete = {
            pipeline.notifyFlushComplete()
        }
        transport.onConnectionFailure = { cause ->
            // Marked here, before the reason reaches a handler. This report is
            // only for an end the transport forced, and it comes before the
            // end itself, so it is the last moment the answer is this side's
            // alone: a handler offered the reason closes on it — which is what
            // the reason is offered for — and that close is this side's, so
            // the mark taken at the end would say the connection ended under
            // nobody. Same predicate as the two later marks, so a caller that
            // had already begun a close still keeps its own answer.
            //
            // Not for a transport that reports every end on the read side.
            // That arm answers as it did before this event existed and takes
            // no mark anywhere else, and it is the arm every engine here is
            // on — including the one engine family that raises this report.
            // Marking from here alone would turn a refused send's documented
            // refusal into an end of file on a connection nothing else about
            // this change touches.
            if (!transport.reportsEveryEndAsReadClosed) markEndedByTransport()
            // The transport invokes this before its inactive report and at
            // most once, so ordering and count are its obligations; this
            // wiring only chooses the destination. The destination is the
            // pipeline's existing error entrance -- the same one a handler
            // failure reaches -- not a new subscription point; it is entered
            // by the transport-failure route so the head can tell a failure
            // these handlers heard from one they did not.
            //
            // Offered wherever there is a pipeline to offer it to. A
            // Coroutine-mode channel is included: its caller is answered by
            // the suspending wait, and the reason travelling to the end
            // costs nothing now that the end knows this send from a bug --
            // where excluding it left the head recording that the reason had
            // reached no handler, on a channel whose caller was being told.
            // An empty pipeline is not, since a failure journalled with
            // nobody to replay it to is one the head must record instead.
            if (!pipeline.isEmpty) defaultPipeline.notifyTransportFailure(cause)
        }
        transport.onReadClosed = {
            if (transport.reportsEveryEndAsReadClosed) {
                // A transport that has not been taught the difference: this
                // one report is every way its connection could be over, so
                // the channel answers it as it did before the event existed
                // — the ending, and the close that a chain of its own has
                // nobody else to perform.
                // The field alone, exactly as it was read before the split:
                // a bridge put in the chain by name is not seen here, and was
                // not seen then either.
                val pipelineMode = !pipeline.isEmpty && bridge == null
                pipeline.notifyInactive()
                if (pipelineMode) close()
            } else if (!transport.isOpen) {
                // The peer's end of file found the descriptor already gone —
                // an engine that released it before reporting, a loop that
                // stopped. There is no connection left to answer on, so what
                // the chain is owed is the whole ending: the close delivers
                // it, walks the handlers' close and removes them, which is
                // where a handler gives back what it holds.
                //
                // Marked first, and only where this side did not choose the
                // close the descriptor went with: a reader that was away for
                // the moment reads the end of file, where the close alone
                // would have it read its own close and be refused — but a
                // caller that closed and then had a queued report land must
                // still be told it closed.
                markEndedByTransport()
                close()
            } else {
                // The peer's end of file: the read side is over, the
                // connection is not. The pipeline hears it as `onReadClosed`,
                // not as the ending — a handler can still answer, and a
                // Coroutine-mode reader still drains what was queued before
                // it gets `-1`.
                pipeline.notifyReadClosed()
            }
        }
        transport.onClosed = {
            // The transport ended the connection itself — a reset, a failed
            // read or write, an idle reclamation, a stopped loop. Nothing is
            // left to answer, in either mode: the close delivers the ending,
            // walks the handlers' close, releases what the transport has
            // not, and ends the pipeline's life. Idempotent, so a transport
            // that closes its descriptor before reporting is not closed
            // twice. Remembered first, so a reader that was away for this
            // moment reads the end of file and not a misuse — and only for a
            // connection this side had not already ended, since a report
            // landing after a close this side performed (a timer that was
            // still armed, a loop noticing later) would turn that caller's
            // misuse into an end of file.
            markEndedByTransport()
            close()
        }
        // The channel is assembled and can carry traffic, so its pipeline is
        // told. Nothing sent this before, so `onActive` never ran on any
        // connection — and it is the only thing that puts a connection into
        // the registry a server reads to shut down gracefully, which stayed
        // empty as a result.
        //
        // The pipeline has no handlers yet — a channel builds its own, and a
        // subclass cannot install one before this runs — so the activation
        // lands in the pre-attach journal and waits there for the first
        // handler.
        pipeline.notifyActive()
        // Notify the transport that all callbacks are wired up. Engines
        // that pre-arm their read primitive (IdleReadPolicy.DETECT_PEER_CLOSE)
        // arm here instead of in their own init { } block — arming earlier
        // races with the channel-construction sequence and can deliver
        // bytes through a still-null [onRead]. The POSIX engines join their
        // loop's participant registry here for the same reason, so this call
        // must stay last; `AbstractPipelinedChannelTest` pins that.
        transport.onChannelAttached()
    }

    override fun ensureBridge(): SuspendBridgeHandler {
        bridge?.let { return it }
        val handler = SuspendBridgeHandler()
        pipeline.addLast(PipelinedChannel.SUSPEND_BRIDGE_NAME, handler)
        bridge = handler
        // A peer-close that arrived before the bridge was installed is not
        // lost: it sits in the journal, whose drain this add triggers, and
        // reaches the bridge from inside the add, so the bridge observes EOF
        // and the next `read(buf)` returns `-1` once the queue is drained. The
        // channel is left open for the caller to close — Coroutine-mode
        // channels are not auto-closed (see the `onReadClosed` wiring).
        return handler
    }

    override fun shutdownOutput() {
        transport.shutdownOutput()
    }

    override suspend fun awaitFlushComplete() {
        transport.awaitPendingFlush()
    }

    override suspend fun awaitClosed() {
        transport.awaitClosed()
    }

    /**
     * Records that this side asked for the close, unless the transport
     * already reported the end. Called at each ask — this channel's [close],
     * the pipeline's `requestClose`, a handler's `propagateClose` from
     * outside a walk — and at the ask rather than where the descriptor is
     * released: off the loop the walk is handed over and lands a turn later,
     * and a handler writing its farewell from its own close can have the
     * write refused and the end reported while the walk is still travelling.
     */
    internal fun markClosedByThisSide() {
        if (endCause == EndCause.NONE) endCause = EndCause.THIS_SIDE
    }

    /**
     * Records that the transport ended this connection, unless this side
     * already asked. Called from each of the transport's reports before the
     * close that follows it.
     */
    private fun markEndedByTransport() {
        if (endCause == EndCause.NONE) endCause = EndCause.TRANSPORT
    }

    /**
     * Closes this channel: drains a journal whose drain is still queued, tells
     * the pipeline the connection has ended, asks the handlers to close,
     * releases the transport, and ends the pipeline's life — every handler
     * removed, `handlerRemoved` once each.
     *
     * The ending is the other half of the activation sent at construction, and
     * not separable from it. A handler that registers something on `onActive`
     * unregisters it on `onInactive`, and no transport signal reports this
     * ending: only one of them treats a local close as a read close, so
     * without this a connection the *server* drops — which is what the
     * deadline handlers do to a client that stalls — would be registered and
     * never removed.
     *
     * The handlers' close is the outbound walk, for what a handler owns rather
     * than what it observed. Each handler hears it at most once, however many
     * times a handler closes back; a handler that consumes it ends the walk,
     * and what a handler owns is released by `handlerRemoved` at the end of
     * life regardless.
     *
     * **Where the handlers hear it relative to the descriptor going away
     * depends on the thread.** Called on the transport's own loop, the walk
     * runs before the release, and a handler still has a transport while it
     * closes. Called from anywhere else, the descriptor is released first —
     * strictly before the hand-off, so the loop sees it gone — and the
     * handlers hear their close afterwards. Called after the loop has stopped,
     * the whole sequence runs in place on this thread, under the quiescence a
     * stopped loop implies; a second closer on the same stopped loop — another
     * thread, or this pipeline's own delivery of a journalled FIN under the
     * first closer's claim — finds the pipeline claimed and releases the
     * transport only, so the closer that holds the claim walks with the
     * descriptor already gone. A handler that must
     * write during its close — a TLS close_notify is the example — cannot rely
     * on getting one off the loop.
     *
     * Idempotent: every step of the sequence is a no-op once done, so a close
     * re-entered from a handler — inside the ending, inside the close walk,
     * inside a replayed read — and a second close from another thread both
     * find the finished steps done.
     */
    override fun close() {
        markClosedByThisSide()
        if (transport.inOwningContext) {
            defaultPipeline.closeOnOwningContext()
            return
        }
        if (transport.canDispatchToOwningContext) {
            // The release does not wait on the loop, and precedes the hand-off:
            // what the loop then runs — the ending, the walk, the end of life
            // — sees the descriptor gone, and the journal's drain-first
            // delivers no data after it.
            releaseTransport()
            try {
                transport.ioDispatcher.dispatch(EmptyCoroutineContext) { defaultPipeline.closeOnOwningContext() }
                return
            } catch (refused: Throwable) {
                // The loop said it could take the rest of the close and then
                // would not, so it has stopped. Falling through runs the
                // sequence here; the descriptor is already gone and every
                // step of it is a no-op once done.
                logger.warn(refused) { "the loop refused a close it said it could take" }
            }
        }
        // The loop has stopped: it can neither take the hand-off nor race this
        // caller, so the sequence runs here. Another closer already holding
        // the pipeline reaches every handler; this one only makes sure of the
        // descriptor.
        if (!defaultPipeline.runInPlace { defaultPipeline.closeOnOwningContext() }) releaseTransport()
    }

    /**
     * Closes the transport without letting a throw skip what follows — the
     * hand-off, or the end of life — the same guard the pipeline's own
     * release applies at the end of the close walk. A transport that throws
     * here (a loop rejecting the close it dispatches, after answering that it
     * could take it) is logged; the walk's end tries again.
     */
    private fun releaseTransport() {
        try {
            transport.close()
        } catch (e: Throwable) {
            logger.warn(e) { "transport.close() threw; the pipeline's close continues" }
        }
    }
}
