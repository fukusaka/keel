package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.core.Channel
import io.github.fukusaka.keel.core.IoEngine
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.core.ServerChannel
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.linux.EPOLLIN
import platform.linux.EPOLL_CTL_ADD
import platform.linux.epoll_ctl
import platform.linux.epoll_event
import platform.posix.errno
import platform.posix.strerror

/**
 * Linux epoll-based [IoEngine] implementation.
 *
 * Creates an [EpollEventLoop] that drives all I/O for channels created
 * by this engine. The EventLoop owns the epoll fd and runs a dedicated
 * pthread that calls `epoll_wait()` to multiplex fd readiness events.
 *
 * All suspend functions ([bind], [connect]) are non-blocking. Channel
 * read/write operations suspend via [suspendCancellableCoroutine] and
 * are resumed by the EventLoop when their fds become ready.
 *
 * ```
 * EpollEngine (owns EventLoop)
 *   |
 *   +-- bind() --> EpollServerChannel (serverFd registered on EventLoop)
 *   |                |
 *   |                +-- accept() --> EpollChannel (clientFd, shares EventLoop)
 *   |
 *   +-- connect() --> EpollChannel (clientFd, shares EventLoop)
 * ```
 *
 * @param config Engine-wide configuration (allocator, threads).
 */
@OptIn(ExperimentalForeignApi::class)
class EpollEngine(
    private val config: IoEngineConfig = IoEngineConfig(),
) : IoEngine {

    private val eventLoop = EpollEventLoop()
    private var closed = false

    init {
        eventLoop.start()
    }

    override suspend fun bind(host: String, port: Int): ServerChannel {
        check(!closed) { "Engine is closed" }

        val serverFd = SocketUtils.createServerSocket(host, port)

        // Register server fd with epoll so that accept() can be notified
        // of incoming connections via the EventLoop.
        memScoped {
            val ev = alloc<epoll_event>()
            ev.events = EPOLLIN.toUInt()
            ev.data.fd = serverFd
            val result = epoll_ctl(eventLoop.epFd, EPOLL_CTL_ADD, serverFd, ev.ptr)
            check(result >= 0) { "epoll_ctl(ADD server) failed: ${strerror(errno)?.toKString()}" }
        }

        val localAddr = SocketUtils.getLocalAddress(serverFd)
        return EpollServerChannel(serverFd, eventLoop, localAddr, config.allocator)
    }

    /**
     * Creates a TCP client connection.
     *
     * Connect is synchronous (blocking): the socket is created in blocking
     * mode, connected, then switched to non-blocking for subsequent I/O.
     * Non-blocking connect (EINPROGRESS + EPOLLOUT wait) is deferred
     * because synchronous connect is sufficient for current use cases and
     * avoids additional complexity in the EventLoop.
     */
    override suspend fun connect(host: String, port: Int): Channel {
        check(!closed) { "Engine is closed" }

        val clientFd = SocketUtils.createClientSocket(host, port)
        val remoteAddr = SocketUtils.getRemoteAddress(clientFd)
        val localAddr = SocketUtils.getLocalAddress(clientFd)

        return EpollChannel(clientFd, eventLoop, config.allocator, remoteAddr, localAddr)
    }

    override fun close() {
        if (!closed) {
            closed = true
            eventLoop.close()
        }
    }
}
