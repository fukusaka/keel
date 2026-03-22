package io.github.fukusaka.keel.engine.nio

import io.github.fukusaka.keel.core.Channel
import io.github.fukusaka.keel.core.IoEngine
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.core.ServerChannel
import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel

/**
 * JVM NIO-based [IoEngine] implementation.
 *
 * Creates a [NioEventLoop] that drives all I/O for channels created
 * by this engine. The EventLoop owns a [java.nio.channels.Selector]
 * and runs a dedicated thread that calls [Selector.select][java.nio.channels.Selector.select]
 * to multiplex channel readiness events.
 *
 * All channels are in non-blocking mode. Read/accept operations
 * suspend via [suspendCancellableCoroutine] and are resumed by
 * the EventLoop when their channels become ready.
 *
 * ```
 * NioEngine (owns EventLoop)
 *   |
 *   +-- bind() --> NioServerChannel (non-blocking, registered on EventLoop)
 *   |                |
 *   |                +-- accept() --> NioChannel (non-blocking, shares EventLoop)
 *   |
 *   +-- connect() --> NioChannel (non-blocking, shares EventLoop)
 * ```
 *
 * @param config Engine-wide configuration (allocator, threads).
 */
class NioEngine(
    private val config: IoEngineConfig = IoEngineConfig(),
) : IoEngine {

    private val eventLoop = NioEventLoop()
    private var closed = false

    override suspend fun bind(host: String, port: Int): ServerChannel {
        check(!closed) { "Engine is closed" }

        val serverChannel = ServerSocketChannel.open()
        serverChannel.configureBlocking(false)
        serverChannel.bind(InetSocketAddress(host, port))

        val localAddr = NioChannel.toSocketAddress(serverChannel.localAddress)
            ?: error("Failed to get local address")

        return NioServerChannel(serverChannel, eventLoop, localAddr, config.allocator)
    }

    /**
     * Creates a TCP client connection.
     *
     * Connect is synchronous (blocking): the channel is temporarily set
     * to blocking mode for the connect call, then switched to non-blocking
     * for subsequent I/O. Non-blocking connect (OP_CONNECT) is deferred
     * because synchronous connect is sufficient for current use cases.
     */
    override suspend fun connect(host: String, port: Int): Channel {
        check(!closed) { "Engine is closed" }

        val socketChannel = SocketChannel.open()
        // Synchronous connect in blocking mode, then switch to non-blocking
        socketChannel.configureBlocking(true)
        socketChannel.connect(InetSocketAddress(host, port))
        socketChannel.configureBlocking(false)

        val remoteAddr = NioChannel.toSocketAddress(socketChannel.remoteAddress)
        val localAddr = NioChannel.toSocketAddress(socketChannel.localAddress)

        return NioChannel(socketChannel, eventLoop, config.allocator, remoteAddr, localAddr)
    }

    override fun close() {
        if (!closed) {
            closed = true
            eventLoop.close()
        }
    }
}
