package io.github.fukusaka.keel.engine.netty

import io.github.fukusaka.keel.core.ServerChannel
import io.github.fukusaka.keel.core.SocketAddress
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import io.github.fukusaka.keel.core.Channel as KeelChannel
import io.netty.channel.Channel as NettyNativeChannel

/**
 * Netty-based [ServerChannel] implementation for JVM.
 *
 * Wraps a Netty server channel and accepts incoming connections via
 * [suspendCancellableCoroutine]. The Netty [ChannelInitializer] (set by
 * [NettyEngine.bind]) calls [onNewChannel] for each accepted connection,
 * which either resumes a waiting [accept] coroutine or buffers the channel
 * for the next [accept] call.
 *
 * Thread safety: [onNewChannel] is called from Netty's boss EventLoop
 * thread while [accept] runs on a coroutine thread. All access to
 * [pendingConnections] and [pendingAcceptCont] is protected by [lock]
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
internal class NettyServerChannel private constructor() : ServerChannel {

    private lateinit var serverChannel: NettyNativeChannel
    private lateinit var _localAddress: SocketAddress
    private val lock = Any()
    private val pendingConnections = ArrayDeque<NettyChannel>()
    private var pendingAcceptCont: CancellableContinuation<NettyChannel>? = null
    private var _active = true

    override val localAddress: SocketAddress get() = _localAddress
    override val isActive: Boolean get() = _active

    /**
     * Sets the underlying Netty server channel and local address.
     * Called by [NettyEngine.bind] after the bind future completes.
     */
    internal fun init(serverChannel: NettyNativeChannel, localAddress: SocketAddress) {
        this.serverChannel = serverChannel
        this._localAddress = localAddress
    }

    /**
     * Called by [NettyEngine.bind]'s ChannelInitializer when a new connection
     * arrives. If [accept] is already waiting, resumes the coroutine directly.
     * Otherwise, buffers the channel for the next [accept] call.
     *
     * Thread safety: called from Netty's boss EventLoop thread. Protected
     * by [lock] to synchronize with [accept] on coroutine threads.
     */
    internal fun onNewChannel(ch: NettyChannel) {
        synchronized(lock) {
            val cont = pendingAcceptCont
            if (cont != null) {
                pendingAcceptCont = null
                cont.resume(ch)
            } else {
                pendingConnections.addLast(ch)
            }
        }
    }

    /**
     * Suspends until a client connects, then returns the pre-initialized
     * [NettyChannel]. The handler is already in the Netty pipeline
     * (added in [NettyEngine.bind]'s ChannelInitializer) to avoid the
     * race condition where channelRead fires before accept() returns.
     */
    override suspend fun accept(): KeelChannel {
        check(_active) { "ServerChannel is closed" }

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
                    pendingAcceptCont = cont
                    cont.invokeOnCancellation {
                        synchronized(lock) { pendingAcceptCont = null }
                    }
                }
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (_active) {
                _active = false
                pendingAcceptCont?.resumeWithException(
                    CancellationException("ServerChannel closed")
                )
                pendingAcceptCont = null
            }
        }
        if (::serverChannel.isInitialized) {
            serverChannel.close()
        }
    }

    companion object {
        /**
         * Creates an uninitialized [NettyServerChannel]. Call [init] after
         * the Netty server channel is bound to complete initialization.
         */
        fun create(): NettyServerChannel = NettyServerChannel()
    }
}
