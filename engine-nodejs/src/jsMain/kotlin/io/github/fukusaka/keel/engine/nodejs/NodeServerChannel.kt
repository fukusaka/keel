package io.github.fukusaka.keel.engine.nodejs

import io.github.fukusaka.keel.io.BufferAllocator
import io.github.fukusaka.keel.core.ServerChannel
import io.github.fukusaka.keel.core.SocketAddress
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.resume
import io.github.fukusaka.keel.core.Channel as KeelChannel

/**
 * Node.js `net.Server`-based [ServerChannel] implementation for JS.
 *
 * Wraps a Node.js [Server] and accepts incoming connections via an
 * [ArrayDeque] queue. The server's connection listener pushes each
 * [Socket] into the queue.
 *
 * Phase (a): [accept] suspends via [suspendCoroutine] until a
 * connection arrives. JS is single-threaded, so no locking is needed.
 *
 * @param server       The Node.js net.Server.
 * @param localAddress Bind address of this server channel.
 * @param allocator    Passed to accepted [NodeChannel]s.
 */
internal class NodeServerChannel(
    private val server: Server,
    override val localAddress: SocketAddress,
    private val allocator: BufferAllocator,
) : ServerChannel {

    private var _active = true
    private val pendingConnections = ArrayDeque<Socket>()
    private var pendingAcceptCont: CancellableContinuation<Socket>? = null

    override val isActive: Boolean get() = _active

    /** Called by [NodeEngine.bind] to register the connection handler. */
    internal fun onConnection(socket: Socket) {
        val cont = pendingAcceptCont
        if (cont != null) {
            pendingAcceptCont = null
            cont.resume(socket)
        } else {
            pendingConnections.addLast(socket)
        }
    }

    override suspend fun accept(): KeelChannel {
        check(_active) { "ServerChannel is closed" }

        val socket: Socket = if (pendingConnections.isNotEmpty()) {
            pendingConnections.removeFirst()
        } else {
            suspendCancellableCoroutine { cont ->
                pendingAcceptCont = cont
                cont.invokeOnCancellation { pendingAcceptCont = null }
            }
        }

        val remoteAddr = socket.remoteAddress?.let { host ->
            socket.remotePort?.let { port -> SocketAddress(host, port) }
        }

        return NodeChannel(socket, allocator, remoteAddr, localAddress)
    }

    override fun close() {
        if (_active) {
            _active = false
            pendingAcceptCont?.resumeWithException(
                CancellationException("ServerChannel closed"),
            )
            pendingAcceptCont = null
            server.close()
        }
    }
}
