package io.github.fukusaka.keel.engine.nio

import io.github.fukusaka.keel.core.BindSpec
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.pipeline.InboundHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import java.net.BindException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * Lifecycle coverage for the multi-address `bindPipeline` overload on the
 * NIO engine: bind-order address reporting, echo service on every bound
 * address, the single-entry degenerate case, all-or-nothing rollback with
 * prompt port release, whole-server close releasing every port, and the
 * accepted channel reporting the local address of the listener it arrived
 * on (the branch key for a shared initializer).
 */
class NioMultiAddressBindTest {

    /** Echoes every inbound IoBuf straight back to the peer (transfers ownership). */
    private class EchoHandler : InboundHandler {
        override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
            ctx.propagateWriteAndFlush(msg)
        }
    }

    private fun loopbackSpec(port: Int = 0) = BindSpec(InetSocketAddress("127.0.0.1", port))

    private fun assertEchoServed(port: Int) {
        val client = connectRawClient(port)
        try {
            val payload = "multi-echo-$port"
            rawWrite(client, payload)
            assertEquals(payload, rawRead(client, payload.length))
        } finally {
            client.close()
        }
    }

    @Test
    fun `binding two addresses serves connections on both and reports them in bind order`() = runTest {
        val engine = NioEngine()
        try {
            val server = engine.bindPipeline(listOf(loopbackSpec(), loopbackSpec())) { ch ->
                ch.pipeline.addLast("echo", EchoHandler())
            }
            try {
                assertEquals(2, server.localAddresses.size)
                assertEquals(server.localAddresses.first(), server.localAddress)
                val ports = server.localAddresses.map { (it as InetSocketAddress).port }
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
    fun `a single-entry bind list behaves like the single-address overload`() = runTest {
        val engine = NioEngine()
        try {
            val server = engine.bindPipeline(listOf(loopbackSpec())) { ch ->
                ch.pipeline.addLast("echo", EchoHandler())
            }
            try {
                assertEquals(listOf(server.localAddress), server.localAddresses)
                assertEchoServed((server.localAddress as InetSocketAddress).port)
            } finally {
                server.close()
            }
        } finally {
            engine.close()
        }
    }

    @Test
    fun `a mid-list bind failure rolls back the bound listener and releases its port`() = runTest {
        val engine = NioEngine()
        try {
            // Occupies the second entry's port so its bind deterministically fails.
            val blocker = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
            try {
                // A port that is free right now; claimable again after rollback.
                val freePort = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
                assertFailsWith<BindException> {
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
        val engine = NioEngine()
        try {
            val server = engine.bindPipeline(listOf(loopbackSpec(), loopbackSpec())) { }
            val ports = server.localAddresses.map { (it as InetSocketAddress).port }
            server.close()
            ports.forEach { assertPortReleased(it) }
        } finally {
            engine.close()
        }
    }

    @Test
    fun `an accepted channel reports the local address of the listener it arrived on`() = runTest {
        val engine = NioEngine()
        try {
            val seen = LinkedBlockingQueue<SocketAddress>()
            val server = engine.bindPipeline(listOf(loopbackSpec(), loopbackSpec())) { ch ->
                ch.localAddress?.let { seen.add(it) }
                ch.pipeline.addLast("echo", EchoHandler())
            }
            try {
                val target = (server.localAddresses[1] as InetSocketAddress).port
                assertEchoServed(target)
                val captured = seen.poll(IO_OP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                assertNotNull(captured, "pipeline initializer saw no channel localAddress")
                assertEquals(target, (captured as InetSocketAddress).port)
            } finally {
                server.close()
            }
        } finally {
            engine.close()
        }
    }

    @Test
    fun `an empty bind list is rejected`() = runTest {
        val engine = NioEngine()
        try {
            assertFailsWith<IllegalArgumentException> {
                engine.bindPipeline(emptyList()) { }
            }
        } finally {
            engine.close()
        }
    }
}
