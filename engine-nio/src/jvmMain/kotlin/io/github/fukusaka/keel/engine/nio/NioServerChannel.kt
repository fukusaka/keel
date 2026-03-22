package io.github.fukusaka.keel.engine.nio

import io.github.fukusaka.keel.core.BufferAllocator
import io.github.fukusaka.keel.core.Channel
import io.github.fukusaka.keel.core.ServerChannel
import io.github.fukusaka.keel.core.SocketAddress
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.channels.SelectionKey
import java.nio.channels.ServerSocketChannel

/**
 * Java NIO [ServerSocketChannel]-based [ServerChannel] implementation for JVM.
 *
 * The [ServerSocketChannel] is in non-blocking mode. [accept] attempts
 * [ServerSocketChannel.accept] and if null (no pending connection),
 * registers with the [NioEventLoop] for [SelectionKey.OP_ACCEPT]
 * and suspends.
 *
 * ```
 * accept() flow:
 *   ServerSocketChannel.accept()
 *     if null: suspendCancellableCoroutine + eventLoop.register(ch, OP_ACCEPT)
 *     EventLoop select() fires → resume → retry accept
 *   → NioChannel(socketChannel, eventLoop, allocator, remoteAddr, localAddr)
 * ```
 *
 * @param serverChannel The listening ServerSocketChannel (non-blocking).
 * @param eventLoop     The [NioEventLoop] for readiness notification.
 * @param localAddress  Bind address of this server channel.
 * @param allocator     Passed to accepted [NioChannel]s.
 */
internal class NioServerChannel(
    private val serverChannel: ServerSocketChannel,
    private val eventLoop: NioEventLoop,
    override val localAddress: SocketAddress,
    private val allocator: BufferAllocator,
) : ServerChannel {

    private var _active = true

    override val isActive: Boolean get() = _active

    /**
     * Suspends until an incoming connection arrives, then returns a [NioChannel].
     *
     * Uses non-blocking [ServerSocketChannel.accept]. If no connection is
     * pending (returns null), registers with the [NioEventLoop] for
     * [SelectionKey.OP_ACCEPT] and suspends.
     */
    override suspend fun accept(): Channel {
        check(_active) { "ServerChannel is closed" }

        while (true) {
            val client = serverChannel.accept()
            if (client != null) {
                client.configureBlocking(false)
                val remoteAddr = NioChannel.toSocketAddress(client.remoteAddress)
                val localAddr = NioChannel.toSocketAddress(client.localAddress)
                return NioChannel(client, eventLoop, allocator, remoteAddr, localAddr)
            }

            // No pending connection, suspend until OP_ACCEPT fires
            suspendCancellableCoroutine<Unit> { cont ->
                eventLoop.register(serverChannel, SelectionKey.OP_ACCEPT, cont)
            }
        }
    }

    override fun close() {
        if (_active) {
            _active = false
            serverChannel.close()
        }
    }
}
