package io.github.keel.engine.netty

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NettyEngineTest {

    @Test
    fun engineCreatesWithoutError() {
        val engine = NettyEngine()
        engine.close()
    }

    @Test
    fun bindReturnsActiveChannel() {
        val engine = NettyEngine()
        val serverChannel = engine.bind(0)
        assertTrue(serverChannel.isActive)
        serverChannel.close().sync()
        engine.close()
    }

    @Test
    fun serverChannelIsActive() {
        val engine = NettyEngine()
        val serverChannel = engine.bind(0)
        assertTrue(serverChannel.isActive, "server channel must be active")
        serverChannel.close().sync()
        engine.close()
    }

    @Test
    fun echoServerEchoesDataOverLoopback() {
        val engine = NettyEngine()
        val serverChannel = engine.bind(0)

        val port = (serverChannel.localAddress() as InetSocketAddress).port

        val client = Socket(InetAddress.getLoopbackAddress(), port).apply {
            soTimeout = 5000
        }

        val msg = "hello"
        client.getOutputStream().write(msg.toByteArray())
        client.getOutputStream().flush()

        // Netty のバックグラウンドスレッドが accept + echo を処理するまで待機
        Thread.sleep(200)

        val buf = ByteArray(5)
        val n = client.getInputStream().read(buf, 0, buf.size)

        assertEquals(5, n)
        assertEquals(msg, String(buf))

        client.close()
        serverChannel.close().sync()
        engine.close()
    }
}
