package io.github.keel.engine.kqueue

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kqueue.keel_htons
import platform.posix.AF_INET
import platform.posix.F_GETFL
import platform.posix.F_SETFL
import platform.posix.INADDR_ANY
import platform.posix.O_NONBLOCK
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_REUSEADDR
import platform.posix.bind
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.listen
import platform.posix.setsockopt
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.strerror

@OptIn(ExperimentalForeignApi::class)
internal object SocketUtils {

    fun createServerSocket(port: Int): Int {
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        check(fd >= 0) { "socket() failed: ${strerror(errno)?.toKString()}" }

        intArrayOf(1).usePinned { pinned ->
            setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, pinned.addressOf(0), sizeOf<IntVar>().convert())
        }

        setNonBlocking(fd)

        memScoped {
            val addr = alloc<sockaddr_in>()
            addr.sin_family = AF_INET.convert()
            addr.sin_port = keel_htons(port.toUShort())
            addr.sin_addr.s_addr = INADDR_ANY.toUInt()
            val result = bind(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
            check(result == 0) { "bind() failed: ${strerror(errno)?.toKString()}" }
        }

        val result = listen(fd, 128)
        check(result == 0) { "listen() failed: ${strerror(errno)?.toKString()}" }

        return fd
    }

    fun setNonBlocking(fd: Int) {
        val flags = fcntl(fd, F_GETFL, 0)
        fcntl(fd, F_SETFL, flags or O_NONBLOCK)
    }
}
