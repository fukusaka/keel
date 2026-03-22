package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.core.Channel
import io.github.fukusaka.keel.core.IoEngine
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.core.ServerChannel
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kqueue.keel_ev_set
import platform.darwin.EV_ADD
import platform.darwin.EVFILT_READ
import platform.darwin.kevent
import platform.posix.errno
import platform.posix.strerror

/**
 * macOS kqueue-based [IoEngine] implementation.
 *
 * Creates a [KqueueEventLoop] that drives all I/O for channels created
 * by this engine. The EventLoop owns the kqueue fd and runs a dedicated
 * pthread that calls `kevent()` to multiplex fd readiness events.
 *
 * All suspend functions ([bind], [connect]) are non-blocking. Channel
 * read/write operations suspend via [suspendCancellableCoroutine] and
 * are resumed by the EventLoop when their fds become ready.
 *
 * ```
 * KqueueEngine (owns EventLoop)
 *   |
 *   +-- bind() --> KqueueServerChannel (serverFd registered on EventLoop)
 *   |                |
 *   |                +-- accept() --> KqueueChannel (clientFd, shares EventLoop)
 *   |
 *   +-- connect() --> KqueueChannel (clientFd, shares EventLoop)
 * ```
 *
 * @param config Engine-wide configuration (allocator, threads).
 */
@OptIn(ExperimentalForeignApi::class)
class KqueueEngine(
    private val config: IoEngineConfig = IoEngineConfig(),
) : IoEngine {

    private val eventLoop = KqueueEventLoop()
    private var closed = false

    init {
        eventLoop.start()
    }

    override suspend fun bind(host: String, port: Int): ServerChannel {
        check(!closed) { "Engine is closed" }

        val serverFd = SocketUtils.createServerSocket(host, port)

        // Register server fd with kqueue so that accept() can be notified
        // of incoming connections via the EventLoop.
        memScoped {
            val kev = alloc<kevent>()
            keel_ev_set(
                kev.ptr,
                serverFd.convert(),
                EVFILT_READ.convert(),
                EV_ADD.convert(),
                0u,
                0,
                null,
            )
            val result = kevent(eventLoop.kqFd, kev.ptr, 1, null, 0, null)
            check(result >= 0) { "kevent(EV_ADD server) failed: ${strerror(errno)?.toKString()}" }
        }

        val localAddr = SocketUtils.getLocalAddress(serverFd)
        return KqueueServerChannel(serverFd, eventLoop, localAddr, config.allocator)
    }

    /**
     * Creates a TCP client connection.
     *
     * Phase (a) limitation: connect is still synchronous (blocking).
     * The socket is created in blocking mode, connected, then switched
     * to non-blocking. Phase (b) defers non-blocking connect with
     * kqueue EVFILT_WRITE wait to a future improvement.
     */
    override suspend fun connect(host: String, port: Int): Channel {
        check(!closed) { "Engine is closed" }

        val clientFd = SocketUtils.createClientSocket(host, port)
        val remoteAddr = SocketUtils.getRemoteAddress(clientFd)
        val localAddr = SocketUtils.getLocalAddress(clientFd)

        return KqueueChannel(clientFd, eventLoop, config.allocator, remoteAddr, localAddr)
    }

    override fun close() {
        if (!closed) {
            closed = true
            eventLoop.close()
        }
    }
}
