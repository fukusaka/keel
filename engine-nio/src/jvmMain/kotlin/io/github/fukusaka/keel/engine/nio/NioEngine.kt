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
 * **SelectionKey caching**: Channels are registered with the Selector once
 * via [NioEventLoop.registerChannel]. Subsequent I/O uses [NioEventLoop.setInterest]
 * to toggle interest ops without JNI re-registration.
 *
 * ```
 * NioEngine
 *   |
 *   +-- bossLoop (accept EventLoop)
 *   |     |
 *   |     +-- bind() → NioServerChannel (cached SelectionKey)
 *   |           |
 *   |           +-- accept() → registerChannel on workerLoop → NioChannel
 *   |
 *   +-- workerGroup (N worker EventLoops, round-robin)
 *         |
 *         +-- worker[0]: Channel A, E, I, ...
 *         +-- worker[1]: Channel B, F, J, ...
 *         +-- worker[N]: ...
 * ```
 *
 * @param config Engine-wide configuration. [IoEngineConfig.threads] controls
 *               the number of worker EventLoop threads. 0 (default) resolves
 *               to `availableProcessors()`.
 */
class NioEngine(
    private val config: IoEngineConfig = IoEngineConfig(),
) : IoEngine {

    private val bossLoop = NioEventLoop("keel-nio-boss")
    private val workerGroup = NioEventLoopGroup(resolveThreads(config), "keel-nio-worker")
    private var closed = false

    override suspend fun bind(host: String, port: Int): ServerChannel {
        check(!closed) { "Engine is closed" }

        val serverChannel = ServerSocketChannel.open()
        serverChannel.configureBlocking(false)
        serverChannel.bind(InetSocketAddress(host, port))

        val localAddr = NioChannel.toSocketAddress(serverChannel.localAddress)
            ?: error("Failed to get local address")

        // One-time registration with the boss Selector
        val selectionKey = bossLoop.registerChannel(serverChannel)

        return NioServerChannel(serverChannel, selectionKey, bossLoop, workerGroup, localAddr, config.allocator)
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
     * in round-robin order with a cached [SelectionKey].
     */
    override suspend fun connect(host: String, port: Int): Channel {
        check(!closed) { "Engine is closed" }

        val socketChannel = SocketChannel.open()
        socketChannel.configureBlocking(false)
        val workerLoop = workerGroup.next()

        // Try connect first — loopback may succeed or fail immediately
        // without needing Selector registration.
        val connected = try {
            socketChannel.connect(InetSocketAddress(host, port))
        } catch (e: Exception) {
            socketChannel.close()
            throw e
        }

        // One-time registration with the worker Selector
        val selectionKey = workerLoop.registerChannel(socketChannel)

        if (!connected) {
            // Connection in progress — suspend until OP_CONNECT fires
            try {
                suspendCancellableCoroutine<Unit> { cont ->
                    workerLoop.setInterest(selectionKey, SelectionKey.OP_CONNECT, cont)
                    cont.invokeOnCancellation {
                        selectionKey.cancel()
                        runCatching { socketChannel.close() }
                    }
                }
                socketChannel.finishConnect()
            } catch (e: Exception) {
                selectionKey.cancel()
                runCatching { socketChannel.close() }
                throw e
            }
        }

        val remoteAddr = NioChannel.toSocketAddress(socketChannel.remoteAddress)
        val localAddr = NioChannel.toSocketAddress(socketChannel.localAddress)

        return NioChannel(socketChannel, selectionKey, workerLoop, config.allocator, remoteAddr, localAddr)
    }

    override fun close() {
        if (!closed) {
            closed = true
            bossLoop.close()
            workerGroup.close()
        }
    }

    companion object {
        /** Resolves threads=0 to available CPU cores. */
        private fun resolveThreads(config: IoEngineConfig): Int =
            if (config.threads > 0) config.threads
            else Runtime.getRuntime().availableProcessors()
    }
}
