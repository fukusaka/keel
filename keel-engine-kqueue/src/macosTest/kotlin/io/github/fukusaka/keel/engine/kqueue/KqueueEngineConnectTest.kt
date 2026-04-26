package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.core.UnixSocketAddress
import io.github.fukusaka.keel.native.posix.PosixRawClient
import io.github.fukusaka.keel.native.posix.ReadResult
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.posix.close
import platform.posix.unlink
import platform.posix.usleep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class KqueueEngineConnectTest {

    // --- connect ---

    @Test
    fun connectToListeningServer() = runBlocking {
        val engine = KqueueEngine()
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

    @Test
    fun connectRemoteAddress() = runBlocking {
        val engine = KqueueEngine()
        val server = engine.bind("127.0.0.1", 0)
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

    @Test
    fun connectLocalAddress() = runBlocking {
        val engine = KqueueEngine()
        val server = engine.bind("127.0.0.1", 0)
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

    @Test
    fun `connect via hostname resolves through SystemDnsResolver`() = runBlocking {
        val engine = KqueueEngine()
        // 'localhost' comes from /etc/hosts, so getaddrinfo never leaves
        // the machine — this exercises the resolve + connect path without
        // depending on network DNS.
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val channel = engine.connect("localhost", port)
        server.accept().close()
        channel.close()

        server.close()
        engine.close()
    }

}
