package io.github.keel.engine.nwconnection

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import nwconnection.keel_nw_loopback_addr
import platform.posix.AF_INET
import platform.posix.SOL_SOCKET
import platform.posix.SO_RCVTIMEO
import platform.posix.SOCK_STREAM
import platform.posix.close
import platform.posix.connect
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
class NwEngineTest {

    @Test
    fun engineCreatesWithoutError() {
        NwEngine().close()
    }

    @Test
    fun bindReturnsValidPort() {
        val engine = NwEngine()
        val port = engine.bind(0)
        assertTrue(port > 0, "assigned port must be positive, got $port")
        engine.close()
    }

    @Test
    fun serverIsListeningAfterBind() {
        val engine = NwEngine()
        engine.bind(0)
        assertTrue(engine.isListening, "server must be listening after bind")
        engine.close()
    }

    @Test
    fun echoServerEchoesDataOverLoopback() {
        val engine = NwEngine()
        val port = engine.bind(0)

        val clientFd = socket(AF_INET, SOCK_STREAM, 0)
        memScoped {
            // 5-second receive timeout to prevent hang on test failure
            val tv = alloc<timeval>()
            tv.tv_sec = 5
            tv.tv_usec = 0
            setsockopt(clientFd, SOL_SOCKET, SO_RCVTIMEO, tv.ptr, sizeOf<timeval>().convert())

            val addr = alloc<sockaddr_in>()
            addr.sin_family = AF_INET.convert()
            // nw_listener_get_port returns host byte order — convert to network byte order
            addr.sin_port = ((port shr 8 and 0xFF) or (port shl 8 and 0xFF00)).toUShort()
            addr.sin_addr.s_addr = keel_nw_loopback_addr()
            connect(clientFd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
        }

        val msg = "hello"
        msg.encodeToByteArray().usePinned { pinned ->
            write(clientFd, pinned.addressOf(0), msg.length.convert())
        }

        // NWConnection echoes asynchronously on its dispatch queue;
        // the blocking read() here waits until the echo arrives (or SO_RCVTIMEO fires).
        val buf = ByteArray(5)
        val n = buf.usePinned { pinned ->
            read(clientFd, pinned.addressOf(0), buf.size.convert())
        }

        assertEquals(5, n.toInt(), "expected 5 bytes echoed back")
        assertEquals(msg, buf.decodeToString())

        close(clientFd)
        engine.close()
    }
}
