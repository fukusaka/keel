package io.github.keel.engine.nodejs

import io.github.keel.core.IoEngine
import io.github.keel.core.IoEngineConfig
import io.github.keel.core.ServerChannel
import io.github.keel.core.SocketAddress
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import io.github.keel.core.Channel as KeelChannel

/**
 * Node.js-based [IoEngine] implementation for JS.
 *
 * Uses Node.js `net` module for TCP I/O. All operations are
 * callback-based internally, bridged to Kotlin coroutines via
 * [suspendCoroutine].
 *
 * **Push-to-pull bridge**: Node.js's event-driven model (socket.on("data"))
 * is bridged to keel's pull model (suspend read) via [ArrayDeque] queues
 * in [NodeChannel]. See [NodeChannel] KDoc for details.
 *
 * ```
 * NodeEngine
 *   |
 *   +-- bind() --> NodeServerChannel (wraps net.Server)
 *   |                |
 *   |                +-- accept() --> NodeChannel (wraps net.Socket)
 *   |
 *   +-- connect() --> NodeChannel (wraps net.Socket)
 * ```
 *
 * @param config Engine-wide configuration (allocator, threads).
 */
class NodeEngine(
    private val config: IoEngineConfig = IoEngineConfig(),
) : IoEngine {

    private var closed = false

    override suspend fun bind(host: String, port: Int): ServerChannel {
        check(!closed) { "Engine is closed" }

        return suspendCoroutine { cont ->
            val srv = Net.createServer { _ ->
                // No-op: connections handled via "connection" event below
            }

            srv.listen(port) {
                val addr = srv.address()
                val assignedPort = addr.port as Int
                val localAddr = SocketAddress(host, assignedPort)
                val serverChannel = NodeServerChannel(srv, localAddr, config.allocator)

                // Wire connection events to the ServerChannel's accept queue
                srv.on("connection") { socket: dynamic ->
                    serverChannel.onConnection(socket as Socket)
                }

                cont.resume(serverChannel)
            }

            srv.on("error") { err: dynamic ->
                cont.resumeWithException(Error(err.message as? String ?: "listen error"))
            }
        }
    }

    override suspend fun connect(host: String, port: Int): KeelChannel {
        check(!closed) { "Engine is closed" }

        return suspendCoroutine { cont ->
            val socket = Net.createConnection(port, host)

            socket.once("connect") { _: dynamic ->
                val remoteAddr = SocketAddress(host, port)
                val localAddr = socket.localAddress?.let { h ->
                    socket.localPort?.let { p -> SocketAddress(h, p) }
                }
                val channel = NodeChannel(socket, config.allocator, remoteAddr, localAddr)
                cont.resume(channel)
            }

            socket.once("error") { err: dynamic ->
                cont.resumeWithException(Error(err.message as? String ?: "connect error"))
            }
        }
    }

    override fun close() {
        if (!closed) {
            closed = true
        }
    }
}
