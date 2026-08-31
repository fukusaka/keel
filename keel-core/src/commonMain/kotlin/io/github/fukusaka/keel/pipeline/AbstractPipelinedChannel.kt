package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.pipeline.internal.DefaultPipeline
import kotlinx.coroutines.CoroutineDispatcher

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
 *   transport.onRead        → pipeline.notifyRead(buf)
 *   transport.onReadClosed  → pipeline.notifyInactive() + (Pipeline-mode) close()
 *   transport.onWritabilityChanged → pipeline.notifyWritabilityChanged()
 *   ensureBridge()          → installs SuspendBridgeHandler (no read arming)
 *   readEnabled             → transport.readEnabled
 *   shutdownOutput()        → transport.shutdownOutput()
 *   close()                 → pipeline.requestClose() → … → transport.close()
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
     * Closes this channel through its pipeline.
     *
     * Through it rather than past it: the handlers hold things the transport
     * cannot reach — a TLS session's native memory, buffers queued for a
     * reader — and `onClose` travelling to the head is what asks them to give
     * those back. The head's whole job at the end of that walk is the
     * `transport.close()` this used to call directly, so the descriptor is
     * released either way; what was missing was everything above it.
     *
     * A close asked for twice walks once ([Pipeline.requestClose] holds that
     * claim), and a close that cannot reach the chain at all still releases
     * the descriptor and says so.
     *
     * Takes effect for its caller before it returns — `isOpen` answers
     * `false` — on whichever thread asks, which [Pipeline.requestClose] keeps
     * even when the walk itself has to be handed to the owning context. The
     * teardown may still outlive the call; [awaitClosed] waits for that.
     */
    override fun close() {
        pipeline.requestClose()
    }
}
