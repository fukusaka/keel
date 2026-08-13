@file:OptIn(InternalReadinessEngineApi::class)

package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.native.readiness.InternalReadinessEngineApi
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.core.InetSocketAddress
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.close
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalForeignApi::class)
class EpollEngineConnectTest {

    // --- connect ---

    @Test
    fun connectToListeningServer() = runBlocking {
        withTimeout(5.seconds) {
            val engine = EpollEngine()
            val server = engine.bind(LOOPBACK_HOST, 0)
            val port = (server.localAddress as InetSocketAddress).port

            val ch = engine.connect("127.0.0.1", port)
            assertTrue(ch.isOpen)
            assertTrue(ch.isActive)

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
            val engine = EpollEngine()
            val server = engine.bind(LOOPBACK_HOST, 0)
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

    @Test
    fun connectLocalAddress() = runBlocking {
        withTimeout(5.seconds) {
            val engine = EpollEngine()
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
    fun `connect and echo round trip`() = runBlocking {
        withTimeout(5.seconds) {
            val engine = EpollEngine()
            val server = engine.bind(LOOPBACK_HOST, 0)
            val port = (server.localAddress as InetSocketAddress).port

            // Non-blocking connect (EINPROGRESS on non-loopback, immediate on loopback)
            val client = engine.connect("127.0.0.1", port)
            val serverCh = server.accept()

            // Client writes, server reads and echoes back
            val msg = "async-connect"
            val writeBuf = DefaultAllocator.allocate(64)
            for (b in msg.encodeToByteArray()) writeBuf.writeByte(b)
            client.write(writeBuf)
            client.flush()

            val readBuf = DefaultAllocator.allocate(64)
            val n = serverCh.read(readBuf)
            assertEquals(msg.length, n)
            serverCh.write(readBuf)
            serverCh.flush()

            val echoBuf = DefaultAllocator.allocate(64)
            val n2 = client.read(echoBuf)
            assertEquals(msg.length, n2)
            echoBuf.release()

            client.close()
            serverCh.close()
            server.close()
            engine.close()
        }
    }

    @Test
    fun `connect to a refused port throws`() = runBlocking {
        val engine = EpollEngine()
        // Connect straight to REFUSED_PORT — a fixed non-ephemeral port
        // nothing listens on — so the refusal is deterministic (see the
        // REFUSED_PORT KDoc for why a freed ephemeral port is unsafe here).
        val ex = assertFailsWith<IllegalStateException> {
            withTimeout(IO_OP_TIMEOUT_MS) {
                engine.connect("127.0.0.1", REFUSED_PORT)
            }
        }
        // A real refusal is a plain IllegalStateException("connect() failed: …").
        // If the hang guard fires instead, withTimeout throws a
        // TimeoutCancellationException — a CancellationException, and on
        // Kotlin/Native a subtype of IllegalStateException, so the
        // assertFailsWith above does NOT screen it out. Rethrow it so a
        // starved-CI hang fails loudly as a timeout instead of slipping
        // through to the message check below with a "Timed out…" string
        // (the prior intermittent linuxX64 CI failure).
        if (ex is CancellationException) throw ex
        assertTrue(ex.message?.contains("connect") == true, "got: ${ex.message}")

        engine.close()
    }
}
