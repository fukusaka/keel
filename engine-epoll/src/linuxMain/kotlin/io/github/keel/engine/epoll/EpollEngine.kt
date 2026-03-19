package io.github.keel.engine.epoll

import io.github.keel.core.Channel
import io.github.keel.core.IoEngine
import io.github.keel.core.IoEngineConfig
import io.github.keel.core.ServerChannel
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.linux.EPOLLIN
import platform.linux.EPOLL_CTL_ADD
import platform.linux.epoll_create1
import platform.linux.epoll_ctl
import platform.linux.epoll_event
import platform.posix.close
import platform.posix.errno
import platform.posix.strerror

/**
 * Linux epoll-based [IoEngine] implementation.
 *
 * Phase (a): synchronous I/O. All suspend functions block internally.
 * A single epoll fd is shared across all channels created by this engine
 * for read-readiness notification (EAGAIN -> epoll_wait -> retry).
 *
 * The epoll fd is created at construction time and closed when
 * [close] is called. All [ServerChannel]s and [Channel]s created by
 * this engine share this epoll fd for event notification.
 *
 * ```
 * EpollEngine (owns epFd)
 *   |
 *   +-- bind() --> EpollServerChannel (serverFd registered on epFd)
 *   |                |
 *   |                +-- accept() --> EpollChannel (clientFd, shares epFd)
 *   |
 *   +-- connect() --> EpollChannel (clientFd, shares epFd)
 * ```
 *
 * @param config Engine-wide configuration (allocator, threads).
 */
@OptIn(ExperimentalForeignApi::class)
class EpollEngine(
    private val config: IoEngineConfig = IoEngineConfig(),
) : IoEngine {

    private val epFd: Int
    private var closed = false

    init {
        val fd = epoll_create1(0)
        check(fd >= 0) { "epoll_create1() failed: ${strerror(errno)?.toKString()}" }
        epFd = fd
    }

    override suspend fun bind(host: String, port: Int): ServerChannel {
        check(!closed) { "Engine is closed" }

        val serverFd = SocketUtils.createServerSocket(host, port)

        // Register server fd with epoll so that EpollServerChannel.accept()
        // can wait for incoming connections via epoll_wait().
        memScoped {
            val ev = alloc<epoll_event>()
            ev.events = EPOLLIN.toUInt()
            ev.data.fd = serverFd
            val result = epoll_ctl(epFd, EPOLL_CTL_ADD, serverFd, ev.ptr)
            check(result >= 0) { "epoll_ctl(ADD server) failed: ${strerror(errno)?.toKString()}" }
        }

        val localAddr = SocketUtils.getLocalAddress(serverFd)
        return EpollServerChannel(serverFd, epFd, localAddr, config.allocator)
    }

    /**
     * Phase (a): blocking connect. The socket is created in blocking mode,
     * connected synchronously, then switched to non-blocking for subsequent
     * read/write operations.
     */
    override suspend fun connect(host: String, port: Int): Channel {
        check(!closed) { "Engine is closed" }

        val clientFd = SocketUtils.createClientSocket(host, port)
        val remoteAddr = SocketUtils.getRemoteAddress(clientFd)
        val localAddr = SocketUtils.getLocalAddress(clientFd)

        return EpollChannel(clientFd, epFd, config.allocator, remoteAddr, localAddr)
    }

    override fun close() {
        if (!closed) {
            closed = true
            close(epFd)
        }
    }
}
