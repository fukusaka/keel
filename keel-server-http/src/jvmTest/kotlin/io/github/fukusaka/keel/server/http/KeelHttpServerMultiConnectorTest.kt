package io.github.fukusaka.keel.server.http

import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.engine.nio.NioEngine
import io.github.fukusaka.keel.server.http.dsl.keelHttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Multi-connector [KeelHttpServer]: several `connector { }` blocks open one
 * listener each, all serving the same routes from one lifecycle. Covers the
 * N-listener bind, the declaration-order address surface, the
 * bind-failure rollback (no leaked listener), and stop closing every
 * listener. Real engine + raw [Socket] peers, so every test is wrapped in
 * a wall-clock timeout.
 */
class KeelHttpServerMultiConnectorTest {

    /** One HTTP exchange against loopback [port]; returns the status line. */
    private fun requestStatusLine(port: Int): String {
        Socket(InetAddress.getLoopbackAddress(), port).use { client ->
            client.soTimeout = 5_000
            client.getOutputStream().write(
                "GET /ping HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray(),
            )
            client.getOutputStream().flush()
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            return reader.readLine() ?: error("no response on port $port")
        }
    }

    private fun portOf(address: io.github.fukusaka.keel.core.SocketAddress): Int =
        (address as InetSocketAddress).port

    /**
     * Asserts the listener on [port] is gone by claiming the port with a
     * raw [ServerSocket]. The kernel-level close of an NIO listener
     * completes on the boss selector's next iteration (the close path
     * wakes it), so the claim is retried briefly instead of asserted
     * immediately — a genuinely leaked listener still fails when the
     * budget is exhausted.
     */
    private fun assertPortReleased(port: Int, budgetMillis: Long = 2_000) {
        val deadline = System.currentTimeMillis() + budgetMillis
        var last: Exception? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                ServerSocket(port, 1, InetAddress.getLoopbackAddress()).close()
                return
            } catch (e: java.net.BindException) {
                last = e
                Thread.sleep(POLL_INTERVAL_MS)
            }
        }
        throw AssertionError("port $port still bound after ${budgetMillis}ms", last)
    }

    @Test
    fun `two connectors serve the same routes on independent listeners`() = runBlocking {
        withTimeout(15.seconds) {
            val engine = NioEngine()
            val server = keelHttpServer(engine) {
                connector { host = "127.0.0.1"; port = 0 }
                connector { host = "127.0.0.1"; port = 0 }
                get("/ping") { call -> call.respondText("pong") }
            }
            server.start()
            try {
                val addresses = server.localAddresses
                assertEquals(2, addresses.size)
                val ports = addresses.map { portOf(it) }
                assertTrue(ports[0] != ports[1], "listeners must bind distinct ephemeral ports")
                // The single-address surface stays the first connector (N = 1 compat).
                assertEquals(ports[0], portOf(server.localAddress))
                assertTrue(server.isActive)
                for (port in ports) {
                    assertEquals("HTTP/1.1 200 OK", requestStatusLine(port), "port $port did not serve")
                }
            } finally {
                server.stop(0, 2_000)
            }
        }
    }

    @Test
    fun `a bind failure on a later connector rolls back the earlier listener`() = runBlocking {
        withTimeout(15.seconds) {
            // Occupy a port so the second connector's bind deterministically fails.
            val blocker = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
            val blockedPort = blocker.localPort
            // Pre-pick a concrete free port for the first connector so the
            // rollback can be verified against it after the failed start.
            val firstPort = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
            val engine = NioEngine()
            val server = keelHttpServer(engine) {
                connector { host = "127.0.0.1"; port = firstPort }
                connector { host = "127.0.0.1"; port = blockedPort }
                get("/ping") { call -> call.respondText("pong") }
            }
            try {
                assertFailsWith<Exception> { server.start() }
                assertFalse(server.isActive)
                assertFailsWith<IllegalStateException> { server.localAddress }
                // The first listener must have been rolled back: the port
                // becomes claimable again once the boss loop processes the close.
                assertPortReleased(firstPort)
            } finally {
                blocker.close()
            }
        }
    }

    @Test
    fun `stop closes every listener`() = runBlocking {
        withTimeout(15.seconds) {
            val engine = NioEngine()
            val server = keelHttpServer(engine) {
                connector { host = "127.0.0.1"; port = 0 }
                connector { host = "127.0.0.1"; port = 0 }
                get("/ping") { call -> call.respondText("pong") }
            }
            server.start()
            val ports = server.localAddresses.map { portOf(it) }
            server.stop(0, 2_000)
            assertFalse(server.isActive)
            for (port in ports) {
                assertPortReleased(port)
            }
        }
    }

    @Test
    fun `a single connector keeps the previous surface unchanged`() = runBlocking {
        withTimeout(15.seconds) {
            val engine = NioEngine()
            val server = keelHttpServer(engine) {
                connector { host = "127.0.0.1"; port = 0 }
                get("/ping") { call -> call.respondText("pong") }
            }
            server.start()
            try {
                assertEquals(1, server.localAddresses.size)
                assertEquals(portOf(server.localAddresses[0]), portOf(server.localAddress))
                assertEquals("HTTP/1.1 200 OK", requestStatusLine(portOf(server.localAddress)))
            } finally {
                server.stop(0, 2_000)
            }
        }
    }

    private companion object {
        /** Poll step while waiting for the boss loop to release a closed listener's port. */
        const val POLL_INTERVAL_MS = 20L
    }
}
