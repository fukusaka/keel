package io.github.fukusaka.keel.engine.nodejs

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.core.BindSpec
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.pipeline.InboundHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * Lifecycle coverage for the multi-address `bindPipeline` overload on the
 * Node.js engine: bind-order address reporting, echo service on every
 * bound address, the single-entry degenerate case, all-or-nothing rollback
 * for the failures the engine observes synchronously (the ephemeral-port
 * rejection — Node reports bind conflicts only asynchronously, so they
 * cannot force a synchronous mid-list failure), whole-server close
 * releasing every port, the accepted channel reporting the local address
 * of the listener it arrived on, and the empty-list rejection.
 *
 * `bindPipeline` requires explicit ports on this engine, so the tests use
 * fixed loopback ports in a module-unique range. Node stops accepting and
 * releases listen sockets asynchronously on the event loop, so port
 * release is probed with a raw `net.Server` listen attempt under a
 * bounded retry.
 */
class NodeMultiAddressBindTest {

    /** Echoes every inbound IoBuf straight back to the peer (transfers ownership). */
    private class EchoHandler : InboundHandler {
        override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
            ctx.propagateWriteAndFlush(msg)
        }
    }

    private fun loopbackSpec(port: Int) = BindSpec(InetSocketAddress("127.0.0.1", port))

    private fun portOf(address: SocketAddress) = (address as InetSocketAddress).port

    /** Echoes one payload through [engine]'s own client connect. */
    private suspend fun assertEchoServed(engine: NodeEngine, port: Int) {
        val client = engine.connect("127.0.0.1", port)
        try {
            val payload = "multi-echo-$port".encodeToByteArray()
            val writeBuf = DefaultAllocator.allocate(payload.size)
            for (b in payload) writeBuf.writeByte(b)
            client.write(writeBuf) // transfer
            client.flush()
            val readBuf = DefaultAllocator.allocate(payload.size * 2)
            var total = 0
            while (total < payload.size) {
                val n = client.read(readBuf)
                if (n < 0) break
                total += n
            }
            assertEquals(payload.size, total)
            val received = ByteArray(total) { readBuf.readByte() }.decodeToString()
            assertEquals(payload.decodeToString(), received)
            readBuf.release()
        } finally {
            client.close()
        }
    }

    /**
     * Attempts one raw `net.Server` listen on [port]; resolves `true` when
     * the listen succeeds (port free) and `false` on a bind error.
     */
    private suspend fun canListen(port: Int): Boolean {
        val result = Channel<Boolean>(capacity = 1)
        val srv = Net.createServer { _ -> }
        srv.on("error") { _: dynamic ->
            result.trySend(false)
        }
        val listenOpts = js("({})")
        listenOpts.port = port
        listenOpts.host = "127.0.0.1"
        srv.listen(listenOpts) {
            srv.close()
            result.trySend(true)
        }
        return result.receive()
    }

    /**
     * Claims [port] with raw listen attempts, up to [attempts] tries. Each
     * attempt is a real Node event-loop round trip (listen → callback /
     * error), which is what gives the pending close time to process —
     * `delay` under `runTest` advances virtual time only, so the wall-clock
     * bound comes from `runTest(timeout = ...)`.
     */
    private suspend fun assertPortReleased(port: Int, attempts: Int = RELEASE_PROBE_ATTEMPTS) {
        repeat(attempts) {
            if (canListen(port)) return
            delay(RETRY_INTERVAL_MS)
        }
        fail("port $port still bound after $attempts listen probes")
    }

    @Test
    fun bindingTwoAddressesServesConnectionsOnBothAndReportsThemInBindOrder() = runTest(timeout = 15.seconds) {
        val engine = NodeEngine()
        try {
            val server = engine.bindPipeline(
                listOf(loopbackSpec(BASE_PORT), loopbackSpec(BASE_PORT + 1)),
            ) { ch ->
                ch.pipeline.addLast("echo", EchoHandler())
            }
            try {
                assertEquals(
                    listOf(BASE_PORT, BASE_PORT + 1),
                    server.localAddresses.map { portOf(it) },
                )
                assertEquals(server.localAddresses.first(), server.localAddress)
                assertEchoServed(engine, BASE_PORT)
                assertEchoServed(engine, BASE_PORT + 1)
            } finally {
                server.close()
            }
        } finally {
            engine.close()
        }
    }

    @Test
    fun aSingleEntryBindListBehavesLikeTheSingleAddressOverload() = runTest(timeout = 15.seconds) {
        val engine = NodeEngine()
        try {
            val server = engine.bindPipeline(listOf(loopbackSpec(BASE_PORT + 2))) { ch ->
                ch.pipeline.addLast("echo", EchoHandler())
            }
            try {
                assertEquals(listOf(server.localAddress), server.localAddresses)
                assertEchoServed(engine, BASE_PORT + 2)
            } finally {
                server.close()
            }
        } finally {
            engine.close()
        }
    }

    @Test
    fun aMidListSynchronousFailureRollsBackTheBoundListenerAndReleasesItsPort() = runTest(timeout = 15.seconds) {
        val engine = NodeEngine()
        try {
            // The ephemeral-port rejection is this engine's deterministic
            // synchronous failure (Node reports port conflicts only
            // asynchronously, after bindPipeline has returned).
            assertFailsWith<IllegalArgumentException> {
                engine.bindPipeline(
                    listOf(loopbackSpec(BASE_PORT + 3), loopbackSpec(0)),
                ) { }
            }
            assertPortReleased(BASE_PORT + 3)
        } finally {
            engine.close()
        }
    }

    @Test
    fun closingAMultiAddressServerReleasesEveryPort() = runTest(timeout = 15.seconds) {
        val engine = NodeEngine()
        try {
            val server = engine.bindPipeline(
                listOf(loopbackSpec(BASE_PORT + 4), loopbackSpec(BASE_PORT + 5)),
            ) { }
            val ports = server.localAddresses.map { portOf(it) }
            server.close()
            ports.forEach { assertPortReleased(it) }
        } finally {
            engine.close()
        }
    }

    @Test
    fun anAcceptedChannelReportsTheLocalAddressOfTheListenerItArrivedOn() = runTest(timeout = 15.seconds) {
        val engine = NodeEngine()
        try {
            val seen = Channel<SocketAddress>(capacity = 4)
            val server = engine.bindPipeline(
                listOf(loopbackSpec(BASE_PORT + 6), loopbackSpec(BASE_PORT + 7)),
            ) { ch ->
                ch.localAddress?.let { seen.trySend(it) }
                ch.pipeline.addLast("echo", EchoHandler())
            }
            try {
                assertEchoServed(engine, BASE_PORT + 7)
                assertEquals(BASE_PORT + 7, portOf(seen.receive()))
            } finally {
                server.close()
            }
        } finally {
            engine.close()
        }
    }

    @Test
    fun anEmptyBindListIsRejected() = runTest(timeout = 15.seconds) {
        val engine = NodeEngine()
        try {
            assertFailsWith<IllegalArgumentException> {
                engine.bindPipeline(emptyList()) { }
            }
        } finally {
            engine.close()
        }
    }

    private companion object {
        // Module-unique fixed port range: Node's bindPipeline rejects
        // ephemeral ports (the assignment arrives asynchronously), so each
        // test uses its own port(s) from this base to stay independent.
        const val BASE_PORT = 19310

        const val RETRY_INTERVAL_MS = 50L

        const val RELEASE_PROBE_ATTEMPTS = 100
    }
}
