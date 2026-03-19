package io.github.keel.engine.nodejs

import io.github.keel.core.BufferAllocator
import io.github.keel.core.ServerChannel
import io.github.keel.core.SocketAddress
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import io.github.keel.core.Channel as KeelChannel

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
    private var pendingAccept: ((Socket) -> Unit)? = null

    override val isActive: Boolean get() = _active

    /** Called by [NodeEngine.bind] to register the connection handler. */
    internal fun onConnection(socket: Socket) {
        if (pendingAccept != null) {
            val callback = pendingAccept!!
            pendingAccept = null
            callback(socket)
        } else {
            pendingConnections.addLast(socket)
        }
    }

    override suspend fun accept(): KeelChannel {
        check(_active) { "ServerChannel is closed" }

        val socket: Socket = if (pendingConnections.isNotEmpty()) {
            pendingConnections.removeFirst()
        } else {
            suspendCoroutine { cont ->
                pendingAccept = { s -> cont.resume(s) }
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
            server.close()
        }
    }
}
