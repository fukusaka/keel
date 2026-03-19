package io.github.keel.engine.nio

import io.github.keel.core.Channel
import io.github.keel.core.IoEngine
import io.github.keel.core.IoEngineConfig
import io.github.keel.core.ServerChannel
import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel

/**
 * JVM NIO-based [IoEngine] implementation.
 *
 * Uses [java.nio.channels.ServerSocketChannel] and [SocketChannel] for TCP I/O.
 * Phase (a): blocking mode. All channels operate in blocking mode; no Selector
 * is used. Phase (b) will introduce non-blocking mode + Selector for async I/O.
 *
 * ```
 * NioEngine
 *   |
 *   +-- bind() --> NioServerChannel (wraps ServerSocketChannel)
 *   |                |
 *   |                +-- accept() --> NioChannel (wraps SocketChannel)
 *   |
 *   +-- connect() --> NioChannel (wraps SocketChannel)
 * ```
 *
 * @param config Engine-wide configuration (allocator, threads).
 */
class NioEngine(
    private val config: IoEngineConfig = IoEngineConfig(),
) : IoEngine {

    private var closed = false

    override suspend fun bind(host: String, port: Int): ServerChannel {
        check(!closed) { "Engine is closed" }

        val serverChannel = ServerSocketChannel.open()
        // Phase (a): blocking mode for simple accept()
        serverChannel.configureBlocking(true)
        serverChannel.bind(InetSocketAddress(host, port))
        // 5-second accept timeout to prevent indefinite blocking in tests
        serverChannel.socket().soTimeout = 5000

        val localAddr = NioChannel.toSocketAddress(serverChannel.localAddress)
            ?: error("Failed to get local address")

        return NioServerChannel(serverChannel, localAddr, config.allocator)
    }

    /**
     * Phase (a): blocking connect. Opens a SocketChannel, connects
     * synchronously, and returns an [NioChannel].
     */
    override suspend fun connect(host: String, port: Int): Channel {
        check(!closed) { "Engine is closed" }

        val socketChannel = SocketChannel.open()
        // Phase (a): blocking mode
        socketChannel.configureBlocking(true)
        socketChannel.connect(InetSocketAddress(host, port))
        // 5-second read timeout to prevent indefinite blocking
        socketChannel.socket().soTimeout = 5000

        val remoteAddr = NioChannel.toSocketAddress(socketChannel.remoteAddress)
        val localAddr = NioChannel.toSocketAddress(socketChannel.localAddress)

        return NioChannel(socketChannel, config.allocator, remoteAddr, localAddr)
    }

    override fun close() {
        if (!closed) {
            closed = true
        }
    }
}
