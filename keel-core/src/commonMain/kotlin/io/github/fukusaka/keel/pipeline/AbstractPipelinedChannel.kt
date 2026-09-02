package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.logging.Logger
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
 *   transport.onReadClosed         → pipeline.notifyInactive() + (Pipeline-mode) close()
 *   transport.onConnectionFailure  → pipeline error path
 *   transport.onWritabilityChanged → pipeline.notifyWritabilityChanged()
 *   ensureBridge()                 → installs SuspendBridgeHandler (no read arming)
 *   readEnabled                    → transport.readEnabled
 *   shutdownOutput()               → transport.shutdownOutput()
 *   close()                        → pipeline.notifyInactive(), pipeline.requestClose() once,
 *                                    then transport.close() if the walk left it open
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

    /**
     * Whether [close] has already asked the pipeline to walk the handlers'
     * close, so a handler that closes from inside its own `onClose` does not
     * start the walk again.
     */
    private var closeWalkAsked: Boolean = false

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
        // lost: [DefaultPipeline] replays [SuspendBridgeHandler.onInactive]
        // from `callHandlerAdded` (its `inactiveObserved` flag), so the
        // bridge observes EOF and the next `read(buf)` returns `-1`. The
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
     * Closes this channel: tells its pipeline the connection has ended, asks
     * the handlers to close, and releases the transport.
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
     * than what it observed: a TLS codec's native session is released there.
     * Asked for once per channel, however many times a handler closes back.
     *
     * **Where the handlers hear it relative to the descriptor going away
     * depends on the thread.** Called on the transport's own loop, the walk
     * runs before the release, and a handler still has a transport while it
     * closes. Called from anywhere else, the walk is handed to the loop and the
     * release happens first, so the handlers hear their close afterwards,
     * against a transport already gone. A handler that must write during its
     * close — a TLS close_notify is the example — cannot rely on getting one
     * off the loop.
     *
     * Idempotent at the pipeline, which already expected this caller: a close
     * after a peer FIN finds the inactivation recorded and does nothing. Two
     * threads closing at once can both start a walk; the flag that guards it is
     * a plain read, like the transport's own, and for the same reason.
     */
    override fun close() {
        // On the loop: the ending, the walk, then the release the walk may
        // have left undone. Off it: everything the handlers hear is handed to
        // the loop — every pipeline delivery belongs to it, and the server's
        // own handler mutates a registry its loop also touches, lock-free, on
        // exactly that contract — while the descriptor is released here, as
        // promptly as when close() did nothing else.
        if (transport.inOwningContext) {
            pipeline.notifyInactive()
            // The handlers' own close, which nothing in production sent
            // before. `requestClose` has been on [Pipeline] since it was
            // written with no caller outside tests, so `onClose` never ran on
            // a connection and a handler holding something the connection
            // owns — a TLS codec's native session, most of all — was never
            // told to let go of it.
            //
            // Asked for once. The walk runs handler code, and a handler that
            // closes this channel from inside its own `onClose` re-enters
            // here: guarding on the transport instead would not hold, because
            // the transport is still open until the walk reaches its end.
            // Measured before this flag existed: 2620 frames deep, then a
            // stack overflow that `invokeOnClose`'s own catch swallowed into
            // an error report — and on Native, where these engines live,
            // stack exhaustion is not catchable at all.
            //
            // Not a compare-and-swap, like the transport's own `markClosing`
            // and for the same reason: two threads closing the same channel at
            // once can both read `false` and both walk. That is a race the
            // caller already has with the descriptor.
            if (!closeWalkAsked) {
                closeWalkAsked = true
                pipeline.requestClose()
            }
            // The descriptor is not left to the walk: it has just run, and
            // finding the transport still open means a handler swallowed the
            // close or threw — it would otherwise stay for the pipeline's
            // lifetime.
            if (transport.isOpen) transport.close()
            return
        }
        // Read before the hand-off, because two closers racing here can both
        // see `false` — the race the flag's comment above already accepts. The
        // flag and not the transport guards the walk, so a channel whose
        // transport died first still asks its handlers to release what they
        // hold.
        val walkNeeded = !closeWalkAsked
        if (walkNeeded) closeWalkAsked = true
        if (transport.canDispatchToOwningContext) {
            transport.ioDispatcher.dispatch(EmptyCoroutineContext) {
                pipeline.notifyInactive()
                if (walkNeeded) pipeline.requestClose()
            }
        } else {
            // The loop has stopped: it can neither take the hand-off nor race
            // this caller, so the ending runs in place and the walk falls to
            // the pipeline's head-only fallback.
            pipeline.notifyInactive()
            if (walkNeeded) pipeline.requestClose()
        }
        // The release does not wait on the loop. The walk it queued runs
        // after, in full — a transport answers `inOwningContext` by asking
        // which thread it is on — so the handlers hear their close against a
        // transport already gone, and the walk's own end closes an
        // already-closed transport. A handler that must write during its
        // close needs the walk to precede the release, which means deferring
        // it; that is filed, not done here.
        transport.close()
    }
}
