@file:OptIn(InternalReadinessEngineApi::class)

package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.native.readiness.InternalReadinessEngineApi
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
class KqueueEngineConnectTest {

    // --- connect ---

    @Test
    fun connectToListeningServer() = runBlocking {
        withTimeout(5.seconds) {
            val engine = KqueueEngine()
            val server = engine.bind(LOOPBACK_HOST, 0)
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
            val engine = KqueueEngine()
            val server = engine.bind(LOOPBACK_HOST, 0)
            val port = (server.localAddress as InetSocketAddress).port

            val ch = engine.connect("127.0.0.1", port)
            server.accept().close() // drain accept queue

            assertNotNull(ch.remoteAddress)
            assertEquals("127.0.0.1", (ch.remoteAddress as InetSocketAddress).hostString)
            assertEquals(port, (ch.remoteAddress as InetSocketAddress).port)

            ch.close()
            server.close()
            engine.close()
        }
    }

    @Test
    fun connectLocalAddress() = runBlocking {
        withTimeout(5.seconds) {
            val engine = KqueueEngine()
            val server = engine.bind(LOOPBACK_HOST, 0)
            val port = (server.localAddress as InetSocketAddress).port

            val ch = engine.connect("127.0.0.1", port)
            server.accept().close()

            assertNotNull(ch.localAddress)
            assertEquals("127.0.0.1", (ch.localAddress as InetSocketAddress).hostString)
            assertTrue((ch.localAddress as InetSocketAddress).port > 0)

            ch.close()
            server.close()
            engine.close()
        }
    }

    @Test
    fun `connect via hostname resolves through SystemDnsResolver`() = runBlocking {
        withTimeout(5.seconds) {
            val engine = KqueueEngine()
            // 'localhost' comes from /etc/hosts, so getaddrinfo never leaves
            // the machine — this exercises the resolve + connect path without
            // depending on network DNS.
            val server = engine.bind(LOOPBACK_HOST, 0)
            val port = (server.localAddress as InetSocketAddress).port

            val channel = engine.connect("localhost", port)
            server.accept().close()
            channel.close()

            server.close()
            engine.close()
        }
    }
}
