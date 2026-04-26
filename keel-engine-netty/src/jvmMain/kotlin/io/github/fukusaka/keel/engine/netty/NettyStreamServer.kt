package io.github.fukusaka.keel.engine.netty

import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.core.StreamServer
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import io.netty.channel.Channel as NettyNativeChannel

/**
 * Netty-based [StreamServer] implementation for JVM.
 *
 * Wraps a Netty server channel and accepts incoming connections via
 * [suspendCancellableCoroutine]. The Netty [ChannelInitializer] (set by
 * [NettyEngine.bind]) calls [onNewChannel] for each accepted connection,
 * which either resumes a waiting [accept] coroutine or buffers the channel
 * for the next [accept] call.
 *
 * Thread safety: [onNewChannel] is called from Netty's boss EventLoop
 * thread while [accept] runs on a coroutine thread. All access to
 * [pendingConnections] and [pendingAcceptConts] is protected by [lock]
 * to prevent TOCTOU races. The lock is uncontended in practice since
 * accept is called once per new TCP connection.
 *
 * Created via [create] factory to support two-phase initialization:
 * the instance is created before [ServerBootstrap.bind] so that the
 * ChannelInitializer closure can reference [onNewChannel], then
 * [init] sets the underlying Netty server channel and local address
 * after bind completes.
 *
 * ```
 * accept() flow:
 *   keel coroutine: accept() --> suspendCancellableCoroutine
 *   Netty EventLoop: initChannel(ch) --> onNewChannel(keelCh) --> resume
 * ```
 */
internal class NettyStreamServer private constructor() : StreamServer {

    private lateinit var serverChannel: NettyNativeChannel
    private lateinit var _localAddress: SocketAddress
    private lateinit var bindConfig: BindConfig
    private val lock = Any()
    private val pendingConnections = ArrayDeque<NettyPipelinedChannel>()

    // FIFO queue of suspended accept() callers waiting for the next
    // connection. The previous single-slot design (`pendingAcceptCont:
    // CancellableContinuation<...>?`) silently overwrote earlier waiters
    // when two concurrent `accept()` calls both passed the empty-queue
    // check and both assigned the slot — the lost continuation never
    // resumed and the corresponding `accept()` hung forever. Counterpart
    // of the POSIX engines' register chain (PR #367) and the io-uring
    // queue (PR #368); identity-based `ArrayDeque.remove(cont)` works
    // because `CancellableContinuation` inherits `Object.equals`
    // (reference identity).
    private val pendingAcceptConts = ArrayDeque<CancellableContinuation<NettyPipelinedChannel>>()
    // @Volatile for isActive property getter read outside lock.
    @Volatile
    private var _active = true

    override val localAddress: SocketAddress get() = _localAddress
    override val isActive: Boolean get() = _active

    /**
     * Sets the underlying Netty server channel, local address, and bind config.
     * Called by [NettyEngine.bind] after the bind future completes.
     */
    internal fun init(serverChannel: NettyNativeChannel, localAddress: SocketAddress, bindConfig: BindConfig) {
        this.serverChannel = serverChannel
        this._localAddress = localAddress
        this.bindConfig = bindConfig
    }

    /**
     * Called by [NettyEngine.bind]'s ChannelInitializer when a new connection
     * arrives. If an [accept] coroutine is already waiting, resumes the
     * head of the FIFO queue. Otherwise, buffers the channel for the next
     * [accept] call.
     *
     * Thread safety: called from Netty's boss EventLoop thread. Protected
     * by [lock] to synchronize with [accept] on coroutine threads.
     */
    internal fun onNewChannel(ch: NettyPipelinedChannel) {
        synchronized(lock) {
            val cont = pendingAcceptConts.removeFirstOrNull()
            if (cont != null) {
                cont.resume(ch)
            } else {
                pendingConnections.addLast(ch)
            }
        }
    }

    /**
     * Suspends until a client connects, then returns the pre-initialized
     * [NettyPipelinedChannel]. The handler is already in the Netty pipeline
     * (added in [NettyEngine.bind]'s ChannelInitializer) to avoid the
     * race condition where channelRead fires before accept() returns.
     *
     * Multiple coroutines may call [accept] concurrently — each gets its
     * own slot in the FIFO queue and is resumed in arrival order as
     * connections arrive on the boss EventLoop.
     */
    override suspend fun accept(): PipelinedChannel {
        check(_active) { "StreamServer is closed" }

        // Fast path: buffered connection available
        synchronized(lock) {
            if (pendingConnections.isNotEmpty()) {
                return pendingConnections.removeFirst()
            }
        }

        // Slow path: suspend until onNewChannel is called
        return suspendCancellableCoroutine { cont ->
            synchronized(lock) {
                // Double-check: connection may have arrived between the
                // fast path check and this lock acquisition.
                if (pendingConnections.isNotEmpty()) {
                    cont.resume(pendingConnections.removeFirst())
                } else {
                    pendingAcceptConts.addLast(cont)
                    cont.invokeOnCancellation {
                        // Identity-based remove via CancellableContinuation's
                        // default Object.equals (reference equality).
                        synchronized(lock) { pendingAcceptConts.remove(cont) }
                    }
                }
            }
        }
    }

    /**
     * Closes the server channel and stops accepting connections.
     *
     * Idempotent: subsequent calls are no-ops. Every queued [accept]
     * coroutine is resumed with [CancellationException].
     *
     * **Thread safety**: uses [synchronized] on [lock] because [close]
     * may be called from any thread while [onNewChannel] runs on the
     * Netty boss EventLoop thread.
     */
    override fun close() {
        synchronized(lock) {
            if (_active) {
                _active = false
                while (pendingAcceptConts.isNotEmpty()) {
                    pendingAcceptConts.removeFirst()
                        .resumeWithException(CancellationException("StreamServer closed"))
                }
            }
        }
        if (::serverChannel.isInitialized) {
            serverChannel.close()
        }
    }

    companion object {
        /**
         * Creates an uninitialized [NettyStreamServer]. Call [init] after
         * the Netty server channel is bound to complete initialization.
         */
        fun create(): NettyStreamServer = NettyStreamServer()
    }
}
