package io.github.keel.engine.nio

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NioEngineTest {

    @Test
    fun selectorIsValid() {
        val engine = NioEngine()
        engine.close()
    }

    @Test
    fun bindReturnsOpenChannel() {
        val engine = NioEngine()
        val serverChannel = engine.bind(0)
        assertTrue(serverChannel.isOpen)
        serverChannel.close()
        engine.close()
    }

    @Test
    fun serverChannelIsNonBlocking() {
        val engine = NioEngine()
        val serverChannel = engine.bind(0)
        assertFalse(serverChannel.isBlocking, "server channel must be non-blocking")
        serverChannel.close()
        engine.close()
    }

    @Test
    fun echoServerEchoesDataOverLoopback() {
        val engine = NioEngine()
        val serverChannel = engine.bind(0)

        // Get assigned port
        val port = (serverChannel.localAddress as InetSocketAddress).port

        // Connect blocking client with timeout to prevent hang
        val client = Socket(InetAddress.getLoopbackAddress(), port).apply {
            soTimeout = 5000
        }

        // Send "hello"
        val msg = "hello"
        client.getOutputStream().write(msg.toByteArray())

        // Event loop: event 1 = accept, event 2 = echo
        engine.runEchoLoop(serverChannel, maxEvents = 2)

        // Read echoed data
        val buf = ByteArray(5)
        val n = client.getInputStream().read(buf, 0, buf.size)

        assertEquals(5, n)
        assertEquals(msg, String(buf))

        client.close()
        serverChannel.close()
        engine.close()
    }
}
