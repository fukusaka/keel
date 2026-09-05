package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.warn
import io.github.fukusaka.keel.pipeline.internal.DefaultPipeline
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.coroutines.EmptyCoroutineContext

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
 *   transport.onReadClosed         → pipeline.notifyInactive() + (Pipeline-mode) close()
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
            // Auto-close on peer-FIN only in Pipeline mode — a pipeline
            // with user handlers and no [SuspendBridgeHandler]. There keel
            // owns the connection lifecycle (no [Channel] handle is given
            // to the caller), so the fd must be released here or it leaks
            // in CLOSE-WAIT. A Coroutine-mode channel — a bridge is wired,
            // or the pipeline is still empty before the lazy bridge — is
            // the caller's resource: `read()` reports EOF as `-1` and the
            // caller closes the [Channel]. Auto-closing it would be
            // redundant, and would also sever a peer half-close (peer did
            // `shutdown(SHUT_WR)` but can still receive a final response).
            // Captured before `notifyInactive` in case a handler removes
            // itself while handling it.
            val pipelineMode = !pipeline.isEmpty && bridge == null
            pipeline.notifyInactive()
            if (pipelineMode) close()
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
        // lost: [DefaultPipeline] replays the ending it already delivered to a
        // late handler from inside the add, so the bridge observes EOF and
        // the next `read(buf)` returns `-1`. The channel is left open for the
        // caller to close — Coroutine-mode channels are not auto-closed (see
        // the `onReadClosed` wiring).
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
     * stopped loop implies; a second closer on the same stopped loop finds the
     * pipeline claimed and releases the transport only. A handler that must
     * write during its close — a TLS close_notify is the example — cannot rely
     * on getting one off the loop.
     *
     * Idempotent: every step of the sequence is a no-op once done, so a close
     * re-entered from a handler — inside the ending, inside the close walk,
     * inside a replayed read — and a second close from another thread both
     * find the finished steps done.
     */
    override fun close() {
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
            transport.ioDispatcher.dispatch(EmptyCoroutineContext) { defaultPipeline.closeOnOwningContext() }
            return
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
