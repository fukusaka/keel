package io.github.fukusaka.keel.engine.nodejs

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import io.github.fukusaka.keel.core.Server as KeelServer

/**
 * Node.js `net.Server`-based [ServerChannel] implementation for JS.
 *
 * Wraps a Node.js [Server] and accepts incoming connections via an
 * [ArrayDeque] queue. The server's connection listener pushes each
 * [Socket] into the queue.
 *
 * [accept] suspends via [suspendCancellableCoroutine] until a
 * connection arrives. JS is single-threaded, so no locking is needed.
 *
 * @param server        The Node.js net.Server.
 * @param localAddress  Bind address of this server channel.
 * @param allocator     Passed to accepted [NodePipelinedChannel]s.
 * @param loggerFactory Logger factory for creating per-channel loggers.
 */
internal class NodeServer(
    private val server: Server,
    override val localAddress: SocketAddress,
    private val allocator: BufferAllocator,
    private val bindConfig: BindConfig,
    private val channelLogger: Logger,
) : KeelServer {

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

    override suspend fun accept(): PipelinedChannel {
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

        val transport = NodeIoTransport(socket, allocator)
        val channel = NodePipelinedChannel(transport, channelLogger, remoteAddr, localAddress)
        bindConfig.initializeConnection(channel)
        return channel
    }

    /**
     * Closes the server channel and stops accepting connections.
     *
     * Idempotent: subsequent calls are no-ops. If an [accept] coroutine
     * is suspended, it is cancelled with [CancellationException].
     * No locking needed — JS is single-threaded.
     */
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
