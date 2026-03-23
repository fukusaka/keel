package io.github.fukusaka.keel.engine.nio

import io.github.fukusaka.keel.core.Channel
import io.github.fukusaka.keel.core.IoEngine
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.core.ServerChannel
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.InetSocketAddress
import java.nio.channels.SelectionKey
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel

/**
 * JVM NIO-based [IoEngine] implementation with multi-threaded EventLoop.
 *
 * Uses a boss/worker EventLoop model (same as Netty):
 * - **Boss EventLoop**: handles `accept()` on the ServerSocketChannel
 * - **Worker EventLoopGroup**: handles `read`/`write`/`flush` on accepted channels
 *
 * New connections are assigned to worker EventLoops in round-robin order.
 * Each worker thread runs its own [java.nio.channels.Selector] and acts as
 * a [CoroutineDispatcher][kotlinx.coroutines.CoroutineDispatcher], so all
 * I/O + request processing for a channel runs on a single thread without
 * cross-thread dispatch.
 *
 * ```
 * NioEngine
 *   |
 *   +-- bossLoop (accept EventLoop)
 *   |     |
 *   |     +-- bind() → NioServerChannel
 *   |           |
 *   |           +-- accept() → assign to workerGroup.next()
 *   |
 *   +-- workerGroup (N worker EventLoops, round-robin)
 *         |
 *         +-- worker[0]: Channel A, E, I, ...
 *         +-- worker[1]: Channel B, F, J, ...
 *         +-- worker[N]: ...
 * ```
 *
 * @param config Engine-wide configuration. [IoEngineConfig.threads] controls
 *               the number of worker EventLoop threads (default: 1).
 */
class NioEngine(
    private val config: IoEngineConfig = IoEngineConfig(),
) : IoEngine {

    private val bossLoop = NioEventLoop("keel-nio-boss")
    private val workerGroup = NioEventLoopGroup(config.threads, "keel-nio-worker")
    private var closed = false

    override suspend fun bind(host: String, port: Int): ServerChannel {
        check(!closed) { "Engine is closed" }

        val serverChannel = ServerSocketChannel.open()
        serverChannel.configureBlocking(false)
        serverChannel.bind(InetSocketAddress(host, port))

        val localAddr = NioChannel.toSocketAddress(serverChannel.localAddress)
            ?: error("Failed to get local address")

        return NioServerChannel(serverChannel, bossLoop, workerGroup, localAddr, config.allocator)
    }

    /**
     * Creates a TCP client connection (non-blocking).
     *
     * The SocketChannel is opened in non-blocking mode so `connect()`
     * returns false (connection pending). The coroutine then suspends
     * on `OP_CONNECT` via the worker EventLoop until the connection is
     * established. On loopback, `connect()` may return true immediately
     * without needing to suspend.
     *
     * The connected channel is assigned to the next worker EventLoop
     * in round-robin order.
     */
    override suspend fun connect(host: String, port: Int): Channel {
        check(!closed) { "Engine is closed" }

        val socketChannel = SocketChannel.open()
        socketChannel.configureBlocking(false)
        val workerLoop = workerGroup.next()

        val connected = socketChannel.connect(InetSocketAddress(host, port))
        if (!connected) {
            // Connection in progress — suspend until OP_CONNECT fires
            suspendCancellableCoroutine<Unit> { cont ->
                workerLoop.register(socketChannel, SelectionKey.OP_CONNECT, cont)
                cont.invokeOnCancellation {
                    runCatching { socketChannel.close() }
                }
            }
            socketChannel.finishConnect()
        }

        val remoteAddr = NioChannel.toSocketAddress(socketChannel.remoteAddress)
        val localAddr = NioChannel.toSocketAddress(socketChannel.localAddress)

        return NioChannel(socketChannel, workerLoop, config.allocator, remoteAddr, localAddr)
    }

    override fun close() {
        if (!closed) {
            closed = true
            bossLoop.close()
            workerGroup.close()
        }
    }
}
