package io.github.keel.engine.kqueue

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kqueue.keel_loopback_addr
import platform.posix.AF_INET
import platform.posix.F_GETFL
import platform.posix.O_NONBLOCK
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_RCVTIMEO
import platform.posix.close
import platform.posix.connect
import platform.posix.fcntl
import platform.posix.getsockname
import platform.posix.read
import platform.posix.setsockopt
import platform.posix.socket
import platform.posix.sockaddr_in
import platform.posix.timeval
import platform.posix.write
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class KqueueEngineTest {

    @Test
    fun kqueueFdIsValid() {
        val engine = KqueueEngine()
        engine.close()
    }

    @Test
    fun bindReturnsValidServerFd() {
        val engine = KqueueEngine()
        val serverFd = engine.bind(0)
        assertTrue(serverFd >= 0)
        close(serverFd)
        engine.close()
    }

    @Test
    fun serverSocketIsNonBlocking() {
        val engine = KqueueEngine()
        val serverFd = engine.bind(0)
        val flags = fcntl(serverFd, F_GETFL, 0)
        assertTrue(flags and O_NONBLOCK != 0, "server socket must be O_NONBLOCK")
        close(serverFd)
        engine.close()
    }

    @Test
    fun echoServerEchoesDataOverLoopback() {
        val engine = KqueueEngine()
        val serverFd = engine.bind(0)

        // Get port in network byte order directly from getsockname
        val portNetworkOrder: UShort = memScoped {
            val addr = alloc<sockaddr_in>()
            // Use UIntArray as mutable size holder (avoids socklen_tVar.value issue)
            uintArrayOf(sizeOf<sockaddr_in>().toUInt()).usePinned { len ->
                getsockname(serverFd, addr.ptr.reinterpret(), len.addressOf(0).reinterpret())
            }
            addr.sin_port
        }

        // Connect blocking client with SO_RCVTIMEO to prevent hang
        val clientFd = socket(AF_INET, SOCK_STREAM, 0)
        memScoped {
            val tv = alloc<timeval>()
            tv.tv_sec = 5
            tv.tv_usec = 0
            setsockopt(clientFd, SOL_SOCKET, SO_RCVTIMEO, tv.ptr, sizeOf<timeval>().convert())

            val addr = alloc<sockaddr_in>()
            addr.sin_family = AF_INET.convert()
            addr.sin_port = portNetworkOrder
            addr.sin_addr.s_addr = keel_loopback_addr()  // 127.0.0.1 in network byte order
            connect(clientFd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
        }

        // Send "hello"
        val msg = "hello"
        msg.encodeToByteArray().usePinned { pinned ->
            write(clientFd, pinned.addressOf(0), msg.length.convert())
        }

        // Event loop: event 1 = accept, event 2 = echo
        engine.runEchoLoop(serverFd, maxEvents = 2)

        // Read echoed data
        val buf = ByteArray(5)
        val n = buf.usePinned { pinned ->
            read(clientFd, pinned.addressOf(0), buf.size.convert())
        }

        assertEquals(5, n.toInt())
        assertEquals(msg, buf.decodeToString())

        close(clientFd)
        close(serverFd)
        engine.close()
    }
}
