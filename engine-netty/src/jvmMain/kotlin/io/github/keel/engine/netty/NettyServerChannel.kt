package io.github.keel.engine.netty

import io.github.keel.core.BufferAllocator
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
    private val allocator: BufferAllocator,
    internal val acceptQueue: LinkedBlockingQueue<NettyNativeChannel> = LinkedBlockingQueue(),
) : ServerChannel {

    private var _active = true

    override val isActive: Boolean get() = _active

    /**
     * Blocks until a client connects, then wraps the Netty channel
     * in a [NettyChannel].
     */
    override suspend fun accept(): KeelChannel {
        check(_active) { "ServerChannel is closed" }

        val ch = acceptQueue.poll(5, java.util.concurrent.TimeUnit.SECONDS)
            ?: error("accept() timed out — no connection within 5 seconds")

        val remoteAddr = NettyChannel.toSocketAddress(ch.remoteAddress())
        val localAddr = NettyChannel.toSocketAddress(ch.localAddress())

        val keelChannel = NettyChannel(ch, allocator, remoteAddr, localAddr)
        // Add the push-to-pull handler to the Netty pipeline
        ch.pipeline().addLast(keelChannel.handler)

        return keelChannel
    }

    override fun close() {
        if (_active) {
            _active = false
            serverChannel.close()
        }
    }
}
