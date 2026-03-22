package io.github.fukusaka.keel.engine.nio

import io.github.fukusaka.keel.core.BufferAllocator
import io.github.fukusaka.keel.core.Channel
import io.github.fukusaka.keel.core.ServerChannel
import io.github.fukusaka.keel.core.SocketAddress
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.channels.SelectionKey
import java.nio.channels.ServerSocketChannel

/**
 * Java NIO [ServerSocketChannel]-based [ServerChannel] implementation for JVM.
 *
 * Uses [bossLoop] for accept readiness notification and assigns accepted
 * channels to worker EventLoops from [workerGroup] in round-robin order.
 *
 * ```
 * accept() flow:
 *   bossLoop: select() fires OP_ACCEPT → resume
 *   ServerSocketChannel.accept() → client SocketChannel
 *   workerGroup.next() → assign worker EventLoop
 *   → NioChannel(client, workerLoop, ...)
 * ```
 *
 * @param serverChannel The listening ServerSocketChannel (non-blocking).
 * @param bossLoop      EventLoop for accept readiness notification.
 * @param workerGroup   Worker EventLoopGroup for accepted channels.
 * @param localAddress  Bind address of this server channel.
 * @param allocator     Passed to accepted [NioChannel]s.
 */
internal class NioServerChannel(
    private val serverChannel: ServerSocketChannel,
    private val bossLoop: NioEventLoop,
    private val workerGroup: NioEventLoopGroup,
    override val localAddress: SocketAddress,
    private val allocator: BufferAllocator,
) : ServerChannel {

    private var _active = true
    private var pendingAcceptCont: CancellableContinuation<Unit>? = null

    override val isActive: Boolean get() = _active

    /**
     * Suspends until an incoming connection arrives, then returns a [NioChannel]
     * assigned to the next worker EventLoop.
     */
    override suspend fun accept(): Channel {
        check(_active) { "ServerChannel is closed" }

        while (true) {
            val client = serverChannel.accept()
            if (client != null) {
                client.configureBlocking(false)
                val remoteAddr = NioChannel.toSocketAddress(client.remoteAddress)
                val localAddr = NioChannel.toSocketAddress(client.localAddress)
                val workerLoop = workerGroup.next()
                return NioChannel(client, workerLoop, allocator, remoteAddr, localAddr)
            }

            suspendCancellableCoroutine<Unit> { cont ->
                pendingAcceptCont = cont
                bossLoop.register(serverChannel, SelectionKey.OP_ACCEPT, cont)
                cont.invokeOnCancellation { pendingAcceptCont = null }
            }
            pendingAcceptCont = null
        }
    }

    override fun close() {
        if (_active) {
            _active = false
            pendingAcceptCont?.resumeWithException(
                CancellationException("ServerChannel closed"),
            )
            pendingAcceptCont = null
            serverChannel.close()
        }
    }
}
