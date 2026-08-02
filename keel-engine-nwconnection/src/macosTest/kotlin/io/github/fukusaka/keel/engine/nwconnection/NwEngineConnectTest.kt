package io.github.fukusaka.keel.engine.nwconnection

import io.github.fukusaka.keel.core.InetSocketAddress
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.close
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalForeignApi::class)
class NwEngineConnectTest {

    @Test
    fun connectToListeningServer() = runBlocking {
        withTimeout(5.seconds) {
            val engine = NwEngine()
            val server = engine.bind("127.0.0.1", 0)
            val port = (server.localAddress as InetSocketAddress).port

            val ch = engine.connect("127.0.0.1", port)
            assertTrue(ch.isOpen)
            assertTrue(ch.isActive)

            // Accept server side to complete handshake
            val serverCh = server.accept()

            ch.close()
            serverCh.close()
            server.close()
            engine.close()
        }
    }

    @Test
    fun connectRemoteAddress() = runBlocking {
        withTimeout(5.seconds) {
            val engine = NwEngine()
            val server = engine.bind("127.0.0.1", 0)
            val port = (server.localAddress as InetSocketAddress).port

            val ch = engine.connect("127.0.0.1", port)
            server.accept().close()

            assertNotNull(ch.remoteAddress)
            assertEquals("127.0.0.1", (ch.remoteAddress as InetSocketAddress).hostString)
            assertEquals(port, (ch.remoteAddress as InetSocketAddress).port)

            ch.close()
            server.close()
            engine.close()
        }
    }
}
