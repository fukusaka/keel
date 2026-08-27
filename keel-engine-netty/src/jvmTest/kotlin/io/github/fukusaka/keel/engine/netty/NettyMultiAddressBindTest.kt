package io.github.fukusaka.keel.engine.netty

import io.github.fukusaka.keel.core.BindSpec
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.pipeline.InboundHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Lifecycle coverage for the multi-address `bindPipeline` overload on the
 * Netty engine: bind-order address reporting, echo service on every bound
 * address, the single-entry degenerate case, all-or-nothing rollback with
 * prompt port release, whole-server close releasing every port, and the
 * accepted channel reporting the local address of the listener it arrived
 * on. The mid-list bind failure is asserted as [IOException] because the
 * concrete type differs by Netty transport (`BindException` on NIO, the
 * native transports' own `IOException` subtype on epoll / kqueue).
 */
class NettyMultiAddressBindTest {

    /** Echoes every inbound IoBuf straight back to the peer (transfers ownership). */
    private class EchoHandler : InboundHandler {
        override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
            ctx.propagateWriteAndFlush(msg)
        }
    }

    private fun loopbackSpec(port: Int = 0) = BindSpec(InetSocketAddress("127.0.0.1", port))

    private fun portOf(address: SocketAddress) = (address as InetSocketAddress).port

    private fun assertEchoServed(port: Int) {
        Socket(InetAddress.getLoopbackAddress(), port).use { client ->
            client.soTimeout = 5000
            val payload = "multi-echo-$port".toByteArray()
            client.getOutputStream().apply {
                write(payload)
                flush()
            }
            val buf = ByteArray(payload.size)
            var total = 0
            while (total < buf.size) {
                val n = client.getInputStream().read(buf, total, buf.size - total)
                if (n <= 0) break
                total += n
            }
            assertEquals(String(payload), String(buf, 0, total))
        }
    }

    /** Claims [port] with a raw ServerSocket, retrying up to [budgetMillis]. */
    private fun assertPortReleased(port: Int, budgetMillis: Long = 2_000L) {
        val deadline = System.currentTimeMillis() + budgetMillis
        var last: Exception? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                ServerSocket(port, 1, InetAddress.getLoopbackAddress()).close()
                return
            } catch (e: IOException) {
                last = e
                Thread.sleep(20)
            }
        }
        fail("port $port still bound ${budgetMillis}ms after the listener was closed", last)
    }

    @Test
    fun `binding two addresses serves connections on both and reports them in bind order`() = runTest {
        val engine = NettyEngine()
        try {
            val server = engine.bindPipeline(listOf(loopbackSpec(), loopbackSpec())) { ch ->
                ch.pipeline.addLast("echo", EchoHandler())
            }
            try {
                assertEquals(2, server.localAddresses.size)
                assertEquals(server.localAddresses.first(), server.localAddress)
                val ports = server.localAddresses.map { portOf(it) }
                assertEquals(2, ports.distinct().size, "expected two distinct ephemeral ports, got $ports")
                ports.forEach { assertEchoServed(it) }
            } finally {
                server.close()
            }
        } finally {
            engine.close()
        }
    }

    @Test
    fun `a server that lost one listener names the addresses still accepting`() = runTest {
        // isActive stays true while any channel is up, so deriving the living
        // set from that bit — as the interface default does — would answer
        // that both addresses accept when one of them no longer does. Asked
        // per channel, the answer names the survivor alone, and the server
        // still calls itself listening. Found by independent review of the
        // readiness engines' per-listener work.
        val engine = NettyEngine()
        try {
            val server = engine.bindPipeline(listOf(loopbackSpec(), loopbackSpec())) { ch ->
                ch.pipeline.addLast("echo", EchoHandler())
            }
            try {
                val addresses = server.localAddresses
                assertEquals(addresses, server.activeLocalAddresses, "all up, all accepting")

                // One listener's channel goes down behind the server's back —
                // what a channel-level failure leaves, without one to inject.
                // Reached through the module-internal server, since nothing on
                // the public surface can single a listener out.
                val impl = server as NettyEngine.NettyPipelinedServer
                impl.listenersForTest.first().serverChannel.close().sync()

                assertEquals(
                    listOf(addresses[1]),
                    server.activeLocalAddresses,
                    "the survivor is named, not emptied out with its sibling",
                )
                assertTrue(
                    server.isActive,
                    "and a server with an address left to accept on is listening, whatever the other did",
                )
                assertEchoServed(portOf(addresses[1]))
            } finally {
                server.close()
            }
            assertEquals(emptyList(), server.activeLocalAddresses, "and a closed server claims none")
        } finally {
            engine.close()
        }
    }

    @Test
    fun `a single-entry bind list behaves like the single-address overload`() = runTest {
        val engine = NettyEngine()
        try {
            val server = engine.bindPipeline(listOf(loopbackSpec())) { ch ->
                ch.pipeline.addLast("echo", EchoHandler())
            }
            try {
                assertEquals(listOf(server.localAddress), server.localAddresses)
                assertEchoServed(portOf(server.localAddress))
            } finally {
                server.close()
            }
        } finally {
            engine.close()
        }
    }

    @Test
    fun `a mid-list bind failure rolls back the bound listener and releases its port`() = runTest {
        val engine = NettyEngine()
        try {
            // Occupies the second entry's port so its bind deterministically fails.
            val blocker = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
            try {
                // A port that is free right now; claimable again after rollback.
                val freePort = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
                assertFailsWith<IOException> {
                    engine.bindPipeline(
                        listOf(loopbackSpec(freePort), loopbackSpec(blocker.localPort)),
                    ) { }
                }
                assertPortReleased(freePort)
            } finally {
                blocker.close()
            }
        } finally {
            engine.close()
        }
    }

    @Test
    fun `closing a multi-address server releases every port promptly`() = runTest {
        val engine = NettyEngine()
        try {
            val server = engine.bindPipeline(listOf(loopbackSpec(), loopbackSpec())) { }
            val ports = server.localAddresses.map { portOf(it) }
            server.close()
            ports.forEach { assertPortReleased(it) }
        } finally {
            engine.close()
        }
    }

    @Test
    fun `an accepted channel reports the local address of the listener it arrived on`() = runTest {
        val engine = NettyEngine()
        try {
            val seen = LinkedBlockingQueue<SocketAddress>()
            val server = engine.bindPipeline(listOf(loopbackSpec(), loopbackSpec())) { ch ->
                ch.localAddress?.let { seen.add(it) }
                ch.pipeline.addLast("echo", EchoHandler())
            }
            try {
                val target = portOf(server.localAddresses[1])
                assertEchoServed(target)
                val captured = seen.poll(5_000, TimeUnit.MILLISECONDS)
                assertNotNull(captured, "pipeline initializer saw no channel localAddress")
                assertEquals(target, portOf(captured))
            } finally {
                server.close()
            }
        } finally {
            engine.close()
        }
    }

    @Test
    fun `an empty bind list is rejected`() = runTest {
        val engine = NettyEngine()
        try {
            assertFailsWith<IllegalArgumentException> {
                engine.bindPipeline(emptyList()) { }
            }
        } finally {
            engine.close()
        }
    }
}
