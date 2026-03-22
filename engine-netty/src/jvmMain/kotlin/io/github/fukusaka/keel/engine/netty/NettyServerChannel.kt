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
    private val pendingConnections = ArrayDeque<NettyChannel>()
    @Volatile
    private var pendingAcceptCont: CancellableContinuation<NettyChannel>? = null
    @Volatile
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
     * Thread safety: called from Netty's boss EventLoop thread.
     */
    internal fun onNewChannel(ch: NettyChannel) {
        val cont = pendingAcceptCont
        if (cont != null) {
            pendingAcceptCont = null
            cont.resume(ch)
        } else {
            pendingConnections.addLast(ch)
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

        return if (pendingConnections.isNotEmpty()) {
            pendingConnections.removeFirst()
        } else {
            suspendCancellableCoroutine { cont ->
                pendingAcceptCont = cont
                cont.invokeOnCancellation { pendingAcceptCont = null }
            }
        }
    }

    override fun close() {
        if (_active) {
            _active = false
            pendingAcceptCont?.resumeWithException(
                CancellationException("ServerChannel closed")
            )
            pendingAcceptCont = null
            if (::serverChannel.isInitialized) {
                serverChannel.close()
            }
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
