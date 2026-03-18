package io.github.keel.engine.kqueue

import io.github.keel.core.Channel
import io.github.keel.core.IoEngine
import io.github.keel.core.IoEngineConfig
import io.github.keel.core.ServerChannel
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
import platform.darwin.kqueue
import platform.posix.close
import platform.posix.errno
import platform.posix.strerror

/**
 * macOS kqueue-based [IoEngine] implementation.
 *
 * Phase (a): synchronous I/O. All suspend functions block internally.
 * A single kqueue fd is shared across all channels created by this engine
 * for read-readiness notification (EAGAIN → kevent wait → retry).
 *
 * The kqueue fd is created at construction time and closed when
 * [close] is called. All [ServerChannel]s and [Channel]s created by
 * this engine share this kqueue fd for event notification.
 *
 * @param config Engine-wide configuration (allocator, threads).
 */
@OptIn(ExperimentalForeignApi::class)
class KqueueEngine(
    private val config: IoEngineConfig = IoEngineConfig(),
) : IoEngine {

    private val kqFd: Int
    private var closed = false

    init {
        val fd = kqueue()
        check(fd >= 0) { "kqueue() failed: ${strerror(errno)?.toKString()}" }
        kqFd = fd
    }

    override suspend fun bind(host: String, port: Int): ServerChannel {
        check(!closed) { "Engine is closed" }

        val serverFd = SocketUtils.createServerSocket(host, port)

        // Register server fd with kqueue so that KqueueServerChannel.accept()
        // can wait for incoming connections via kevent().
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
            val result = kevent(kqFd, kev.ptr, 1, null, 0, null)
            check(result >= 0) { "kevent(EV_ADD server) failed: ${strerror(errno)?.toKString()}" }
        }

        val localAddr = SocketUtils.getLocalAddress(serverFd)
        return KqueueServerChannel(serverFd, kqFd, localAddr, config.allocator)
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

        return KqueueChannel(clientFd, kqFd, config.allocator, remoteAddr, localAddr)
    }

    override fun close() {
        if (!closed) {
            closed = true
            close(kqFd)
        }
    }
}
