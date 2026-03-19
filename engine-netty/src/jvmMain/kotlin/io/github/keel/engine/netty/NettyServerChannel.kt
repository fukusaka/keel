package io.github.keel.engine.netty

import io.github.keel.core.ServerChannel
import io.github.keel.core.SocketAddress
import java.util.concurrent.LinkedBlockingQueue
import io.github.keel.core.Channel as KeelChannel
import io.netty.channel.Channel as NettyNativeChannel

/**
 * Netty-based [ServerChannel] implementation for JVM.
 *
 * Wraps a Netty server channel and accepts incoming connections via a
 * [LinkedBlockingQueue]. The Netty [ChannelInitializer] (set by
 * [NettyEngine.bind]) pushes each accepted [NettyNativeChannel] into
 * [acceptQueue].
 *
 * Phase (a): [accept] blocks on the queue until a connection arrives.
 *
 * ```
 * accept() flow:
 *   Netty EventLoop: childHandler.channelActive(ctx) --> acceptQueue.put(ch)
 *   keel thread:     accept() --> acceptQueue.take() --> NettyChannel(ch)
 * ```
 *
 * @param serverChannel The Netty server channel.
 * @param localAddress  Bind address of this server channel.
 * @param allocator     Passed to accepted [NettyChannel]s.
 * @param acceptQueue   Shared queue with [NettyEngine.bind]'s ChannelInitializer.
 */
internal class NettyServerChannel(
    private val serverChannel: NettyNativeChannel,
    override val localAddress: SocketAddress,
    internal val acceptQueue: LinkedBlockingQueue<NettyChannel> = LinkedBlockingQueue(),
) : ServerChannel {

    private var _active = true

    override val isActive: Boolean get() = _active

    /**
     * Blocks until a client connects, then returns the pre-initialized
     * [NettyChannel]. The handler is already in the Netty pipeline
     * (added in [NettyEngine.bind]'s ChannelInitializer) to avoid the
     * race condition where channelRead fires before accept() returns.
     */
    override suspend fun accept(): KeelChannel {
        check(_active) { "ServerChannel is closed" }

        return acceptQueue.poll(5, java.util.concurrent.TimeUnit.SECONDS)
            ?: error("accept() timed out — no connection within 5 seconds")
    }

    override fun close() {
        if (_active) {
            _active = false
            serverChannel.close()
        }
    }
}
