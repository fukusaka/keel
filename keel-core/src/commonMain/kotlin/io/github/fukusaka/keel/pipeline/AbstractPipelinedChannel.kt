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
 *   val transport = KqueueIoTransport(fd, eventLoop, allocator)
 *   val channel = KqueuePipelinedChannel(transport, logger, remote, local)
 *
 * AbstractPipelinedChannel wires:
 *   transport.onRead        → pipeline.notifyRead(buf)
 *   transport.onReadClosed  → pipeline.notifyInactive() + close()
 *   transport.onWritabilityChanged → pipeline.notifyWritabilityChanged()
 *   ensureBridge()          → installs SuspendBridgeHandler (no read arming)
 *   readEnabled             → transport.readEnabled
 *   shutdownOutput()        → transport.shutdownOutput()
 *   close()                 → transport.close()
 * ```
 */
abstract class AbstractPipelinedChannel(
    val transport: IoTransport,
    protected val logger: Logger,
    override val remoteAddress: SocketAddress? = null,
    override val localAddress: SocketAddress? = null,
) : PipelinedChannel {

    override val pipeline: Pipeline = DefaultPipeline(this, transport, logger)
    override val allocator: BufferAllocator get() = transport.allocator
    override val isActive: Boolean get() = transport.isOpen
    override val isOpen: Boolean get() = transport.isOpen
    override val isWritable: Boolean get() = transport.isOpen && transport.isWritable
    override val ioDispatcher: CoroutineDispatcher get() = transport.ioDispatcher
    override val supportsDeferredFlush: Boolean get() = transport.supportsDeferredFlush

    override var readEnabled: Boolean
        get() = transport.readEnabled
        set(value) { transport.readEnabled = value }

    private var bridge: SuspendBridgeHandler? = null

    /**
     * Tracks an `onReadClosed` event that arrived before [SuspendBridgeHandler]
     * was lazily installed. When `true`, [ensureBridge] runs the deferred
     * `close()` after the bridge has had a chance to receive the replayed
     * `onInactive` from [io.github.fukusaka.keel.pipeline.internal.DefaultPipeline]
     * (see its `inactiveObserved` flag), so a pending suspend reader resolves
     * with `-1` instead of dying on the engine-driven peer-close auto-close.
     *
     * Single-threaded read/write on the EventLoop thread; no `@Volatile`
     * required (callbacks and `ensureBridge` are dispatched onto the same
     * `ioDispatcher`).
     */
    private var pendingClose: Boolean = false

    init {
        transport.onWritabilityChanged = { writable ->
            pipeline.notifyWritabilityChanged(writable)
        }
        transport.onRead = { buf ->
            pipeline.notifyRead(buf)
        }
        transport.onReadClosed = {
            pipeline.notifyInactive()
            // Auto-close on peer-FIN matches keel's existing contract
            // ("after EOF is observed, the channel is closed"). Defer the
            // close when no [SuspendBridgeHandler] is installed yet — closing
            // the transport before the bridge is wired would race with a
            // user-initiated `read(buf)` call: the read would either throw
            // `IllegalStateException` (`check(isOpen)`) or suspend forever
            // because `armRead()` skips when `opened = false`. The pending
            // close is replayed from [ensureBridge] once the bridge has
            // observed the inactivation via the pipeline-level replay.
            if (bridge != null) {
                close()
            } else {
                pendingClose = true
            }
        }
        // Notify the transport that all callbacks are wired up. Engines
        // that pre-arm their read primitive (IdleReadPolicy.DETECT_PEER_CLOSE)
        // arm here instead of in their own init { } block — arming earlier
        // races with the channel-construction sequence and can deliver
        // bytes through a still-null [onRead].
        transport.onChannelAttached()
    }

    override fun ensureBridge(): SuspendBridgeHandler {
        bridge?.let { return it }
        val handler = SuspendBridgeHandler()
        pipeline.addLast(PipelinedChannel.SUSPEND_BRIDGE_NAME, handler)
        bridge = handler
        // Replay a peer-close that arrived before the bridge was installed.
        // [DefaultPipeline] has already called [SuspendBridgeHandler.onInactive]
        // from `callHandlerAdded` (so `bridge.eof = true`); the deferred
        // `close()` here matches the pre-existing "auto-close on EOF" contract
        // without losing the EOF event. Subsequent `read(buf)` calls observe
        // `-1` from the bridge and then throw on `check(isOpen)`, mirroring
        // the behaviour of a peer-close that was detected during an active
        // `read`.
        if (pendingClose) {
            pendingClose = false
            close()
        }
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

    override fun close() {
        // Clear any pending peer-FIN-triggered close: this active close
        // already disposes the transport, so a later [ensureBridge] must
        // not run a second [transport.close]. Idempotent transports are
        // assumed but the redundant call is wasteful and shows up as an
        // unexpected side effect in pipeline-handler unit tests.
        pendingClose = false
        transport.close()
    }
}
