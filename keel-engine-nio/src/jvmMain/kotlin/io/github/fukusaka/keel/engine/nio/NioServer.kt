package io.github.fukusaka.keel.engine.nio

import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.ServerChannel
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.channels.SelectionKey
import java.nio.channels.ServerSocketChannel
import kotlin.coroutines.resumeWithException

/**
 * Java NIO [ServerSocketChannel]-based [ServerChannel] implementation for JVM.
 *
 * Uses a cached [selectionKey] registered once with `interestOps=0`.
 * Each `accept()` call toggles [SelectionKey.OP_ACCEPT] via
 * [NioEventLoop.setInterest] instead of re-registering.
 *
 * Accepted channels are registered with the next worker EventLoop
 * via [NioEventLoop.registerChannel] (one-time Selector registration),
 * then assigned a cached [SelectionKey] for zero-overhead I/O.
 *
 * ```
 * accept() flow:
 *   bossLoop: setInterest(key, OP_ACCEPT) → select() → resume
 *   ServerSocketChannel.accept() → client SocketChannel
 *   workerLoop.registerChannel(client) → cached SelectionKey
 *   → NioPipelinedChannel(client, key, transport, workerLoop, ...)
 * ```
 *
 * @param serverChannel The listening ServerSocketChannel (non-blocking).
 * @param selectionKey  Cached SelectionKey registered with the boss Selector.
 * @param bossLoop      EventLoop for accept readiness notification.
 * @param workerGroup   Worker EventLoopGroup for accepted channels.
 * @param localAddress  Bind address of this server channel.
 */
internal class NioServer(
    private val serverChannel: ServerSocketChannel,
    private val selectionKey: SelectionKey,
    private val bossLoop: NioEventLoop,
    private val workerGroup: NioEventLoopGroup,
    override val localAddress: SocketAddress,
    private val bindConfig: BindConfig,
    private val logger: Logger = io.github.fukusaka.keel.logging.NoopLoggerFactory.logger("NioServer"),
) : ServerChannel {

    private var _active = true
    private var pendingAcceptCont: CancellableContinuation<Unit>? = null

    override val isActive: Boolean get() = _active

    /**
     * Suspends until an incoming connection arrives, then returns a [NioPipelinedChannel]
     * assigned to the next worker EventLoop with a cached [SelectionKey].
     */
    override suspend fun accept(): PipelinedChannel {
        check(_active) { "ServerChannel is closed" }

        while (true) {
            val client = serverChannel.accept()
            if (client != null) {
                client.configureBlocking(false)
                val remoteAddr = NioPipelinedChannel.toSocketAddress(client.remoteAddress)
                val localAddr = NioPipelinedChannel.toSocketAddress(client.localAddress)
                val (workerLoop, allocator) = workerGroup.next()
                // One-time registration with the worker's Selector.
                // Returns a cached SelectionKey for interestOps toggling.
                val clientKey = workerLoop.registerChannel(client)
                val transport = NioIoTransport(client, clientKey, workerLoop, allocator)
                val channel = NioPipelinedChannel(transport, logger, remoteAddr, localAddr)
                bindConfig.initializeConnection(channel)
                return channel
            }

            suspendCancellableCoroutine<Unit> { cont ->
                pendingAcceptCont = cont
                bossLoop.setInterest(selectionKey, SelectionKey.OP_ACCEPT, cont)
                cont.invokeOnCancellation { pendingAcceptCont = null }
            }
            pendingAcceptCont = null
        }
    }

    /**
     * Closes the server channel and stops accepting connections.
     *
     * Idempotent: subsequent calls are no-ops. If an [accept] coroutine
     * is suspended, it is cancelled with [CancellationException].
     *
     * **Thread safety**: must be called from the boss EventLoop thread
     * or after the EventLoop has been stopped. [_active] and
     * [pendingAcceptCont] are not thread-safe.
     */
    override fun close() {
        if (_active) {
            _active = false
            pendingAcceptCont?.resumeWithException(
                CancellationException("ServerChannel closed"),
            )
            pendingAcceptCont = null
            selectionKey.cancel()
            serverChannel.close()
        }
    }
}
