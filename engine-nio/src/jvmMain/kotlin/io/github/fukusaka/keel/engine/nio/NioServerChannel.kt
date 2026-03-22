package io.github.fukusaka.keel.engine.nio

import io.github.fukusaka.keel.core.BufferAllocator
import io.github.fukusaka.keel.core.Channel
import io.github.fukusaka.keel.core.ServerChannel
import io.github.fukusaka.keel.core.SocketAddress
import java.nio.channels.ServerSocketChannel

/**
 * Java NIO [ServerSocketChannel]-based [ServerChannel] implementation for JVM.
 *
 * Phase (a): blocking mode. [accept] calls [ServerSocketChannel.accept]
 * directly, which blocks until a connection arrives. No Selector needed;
 * Phase (b) will switch to non-blocking + Selector with OP_ACCEPT.
 *
 * ```
 * accept() flow:
 *   ServerSocketChannel.accept()  -- blocks until connection arrives
 *     --> SocketChannel (blocking mode)
 *     --> NioChannel(socketChannel, allocator, remoteAddr, localAddr)
 * ```
 *
 * @param serverChannel The listening ServerSocketChannel.
 * @param localAddress  Bind address of this server channel.
 * @param allocator     Passed to accepted [NioChannel]s.
 */
internal class NioServerChannel(
    private val serverChannel: ServerSocketChannel,
    override val localAddress: SocketAddress,
    private val allocator: BufferAllocator,
) : ServerChannel {

    private var _active = true

    override val isActive: Boolean get() = _active

    /**
     * Waits for an incoming connection and returns a [NioChannel].
     *
     * Phase (a): blocking mode. The ServerSocketChannel is in blocking
     * mode, so [ServerSocketChannel.accept] blocks until a client connects.
     */
    override suspend fun accept(): Channel {
        check(_active) { "ServerChannel is closed" }

        val client = serverChannel.accept()
        // 5-second read timeout on accepted connections to prevent test hangs
        client.socket().soTimeout = 5000
        val remoteAddr = NioChannel.toSocketAddress(client.remoteAddress)
        val localAddr = NioChannel.toSocketAddress(client.localAddress)

        return NioChannel(client, allocator, remoteAddr, localAddr)
    }

    override fun close() {
        if (_active) {
            _active = false
            serverChannel.close()
        }
    }
}
