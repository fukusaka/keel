package io.github.fukusaka.keel.engine.nodejs

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import io.github.fukusaka.keel.core.StreamServer as KeelStreamServer

/**
 * Node.js `net.Server`-based [StreamServer] implementation for JS.
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
internal class NodeStreamServer(
    private val server: Server,
    override val localAddress: SocketAddress,
    private val allocator: BufferAllocator,
    private val bindConfig: BindConfig,
    private val channelLogger: Logger,
    /** Engine-wide default idle timeout; the per-server [BindConfig.idleTimeoutMillis] overrides it. */
    private val idleTimeoutMillis: Long,
) : KeelStreamServer {

    private var _active = true
    private val pendingConnections = ArrayDeque<Socket>()

    // FIFO queue of suspended accept() callers waiting for the next
    // `onConnection` push. The previous single-slot design
    // (`pendingAcceptCont: CancellableContinuation<Socket>?`) silently
    // overwrote earlier waiters when two `accept()` calls in a row
    // each found `pendingConnections` empty and assigned the slot —
    // the lost continuation never resumed and the corresponding
    // `accept()` hung forever. JS is single-threaded so the bug
    // requires both calls to suspend back-to-back without yielding to
    // the event loop, but it is reachable through the public
    // `StreamServer.accept()` path. Counterpart of the POSIX engines'
    // chain (PR #367), the io-uring queue (PR #368), and the Netty
    // queue (PR #369). Identity-based `ArrayDeque.remove(cont)` works
    // because `CancellableContinuation` inherits `Object.equals`
    // (reference identity).
    private val pendingAcceptConts = ArrayDeque<CancellableContinuation<Socket>>()

    override val isActive: Boolean get() = _active

    /** Called by [NodeEngine.bind] to register the connection handler. */
    internal fun onConnection(socket: Socket) {
        val cont = pendingAcceptConts.removeFirstOrNull()
        if (cont != null) {
            cont.resume(socket)
        } else {
            pendingConnections.addLast(socket)
        }
    }

    override suspend fun accept(): PipelinedChannel {
        check(_active) { "StreamServer is closed" }

        val socket: Socket = if (pendingConnections.isNotEmpty()) {
            pendingConnections.removeFirst()
        } else {
            suspendCancellableCoroutine { cont ->
                pendingAcceptConts.addLast(cont)
                cont.invokeOnCancellation {
                    // Identity-based remove via CancellableContinuation's
                    // default Object.equals (reference equality).
                    pendingAcceptConts.remove(cont)
                }
            }
        }

        applySocketOptions(socket, bindConfig.childSocketOptions)

        val remoteAddr = socket.remoteAddress?.let { host ->
            socket.remotePort?.let { port -> InetSocketAddress(host, port) }
        }

        val transport = NodeIoTransport(
            socket,
            allocator,
            idleTimeoutMillis = bindConfig.idleTimeoutMillis ?: idleTimeoutMillis,
        )
        val channel = NodePipelinedChannel(transport, channelLogger, remoteAddr, localAddress)
        bindConfig.initializeConnection(channel)
        return channel
    }

    /**
     * Closes the server channel and stops accepting connections.
     *
     * Idempotent: subsequent calls are no-ops. Every queued [accept]
     * coroutine is resumed with [CancellationException].
     *
     * **Thread safety**: JS is single-threaded, so every caller runs on
     * the same Node.js event-loop thread and the `_active` /
     * `pendingAcceptConts` reads-then-writes are atomic by construction.
     * No locking is needed, but the idempotent-first-call contract
     * matches the multi-threaded engines.
     */
    override fun close() {
        if (_active) {
            _active = false
            while (pendingAcceptConts.isNotEmpty()) {
                pendingAcceptConts.removeFirst()
                    .resumeWithException(CancellationException("StreamServer closed"))
            }
            server.close()
        }
    }
}
