package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.core.BindSpec
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.native.posix.PosixRawClient
import io.github.fukusaka.keel.pipeline.InboundHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.close
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

/**
 * Lifecycle coverage for the multi-address `bindPipeline` overload on the
 * epoll engine: bind-order address reporting, echo service on every bound
 * address, the single-entry degenerate case, all-or-nothing rollback with
 * the rolled-back port claimable again, whole-server close releasing every
 * port, the accepted channel reporting the local address of the listener
 * it arrived on, and the empty-list rejection. Native listener close
 * releases the port asynchronously, so release
 * is asserted by rebinding directly.
 */
@OptIn(ExperimentalForeignApi::class)
class EpollMultiAddressBindTest {

    /** Echoes every inbound IoBuf straight back to the peer (transfers ownership). */
    private class EchoHandler : InboundHandler {
        override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
            ctx.propagateWriteAndFlush(msg)
        }
    }

    private fun loopbackSpec(port: Int = 0) = BindSpec(InetSocketAddress("127.0.0.1", port))

    private fun portOf(address: SocketAddress) = (address as InetSocketAddress).port

    private fun assertEchoServed(port: Int) {
        val fd = PosixRawClient.rawConnect(port)
        try {
            val payload = "multi-echo-$port"
            PosixRawClient.rawWrite(fd, payload)
            assertEquals(payload, PosixRawClient.rawRead(fd, payload.length))
        } finally {
            close(fd)
        }
    }

    /**
     * Claims [port] again under a bounded retry, failing if the budget runs out.
     *
     * `close()` releases the port asynchronously by contract — it hands the
     * teardown to the boss EventLoop and returns — so the rebind can lose a
     * race with a listener that is still one dispatch away from being closed.
     * The same retry shape the io_uring and NIO suites use for the same reason.
     */
    private fun assertPortReleased(engine: EpollEngine, port: Int, budgetMillis: Long = PORT_RELEASE_BUDGET_MS) {
        val mark = kotlin.time.TimeSource.Monotonic.markNow()
        var last: Throwable? = null
        while (mark.elapsedNow().inWholeMilliseconds < budgetMillis) {
            val server = runCatching { engine.bindPipeline(InetSocketAddress("127.0.0.1", port)) { } }
                .onFailure { last = it }
                .getOrNull()
            if (server != null) {
                server.close()
                return
            }
            platform.posix.usleep(PORT_RELEASE_POLL_MICROS)
        }
        throw AssertionError("port $port still bound ${budgetMillis}ms after the listener was closed", last)
    }

    @Test
    fun `binding two addresses serves connections on both and reports them in bind order`() = runBlocking {
        withTimeout(15.seconds) {
            val engine = EpollEngine(IoEngineConfig(threads = 1))
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
    }

    @Test
    fun `a single-entry bind list behaves like the single-address overload`() = runBlocking {
        withTimeout(15.seconds) {
            val engine = EpollEngine(IoEngineConfig(threads = 1))
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
    }

    @Test
    fun `a mid-list bind failure rolls back the bound listener and releases its port`() = runBlocking {
        withTimeout(15.seconds) {
            val engine = EpollEngine(IoEngineConfig(threads = 1))
            try {
                // Occupies the second entry's port so its bind deterministically fails.
                val blocker = engine.bindPipeline(InetSocketAddress("127.0.0.1", 0)) { }
                try {
                    // A port that was just freed. close() releases asynchronously, so wait
                    // for it before reusing the number as the multi-bind's first entry —
                    // otherwise that entry fails and the rollback path under test never runs.
                    val probe = engine.bindPipeline(InetSocketAddress("127.0.0.1", 0)) { }
                    val freePort = portOf(probe.localAddress)
                    probe.close()
                    assertPortReleased(engine, freePort)
                    assertFailsWith<IllegalStateException> {
                        engine.bindPipeline(
                            listOf(loopbackSpec(freePort), loopbackSpec(portOf(blocker.localAddress))),
                        ) { }
                    }
                    assertPortReleased(engine, freePort)
                } finally {
                    blocker.close()
                }
            } finally {
                engine.close()
            }
        }
    }

    @Test
    fun `closing a multi-address server releases every port`() = runBlocking {
        withTimeout(15.seconds) {
            val engine = EpollEngine(IoEngineConfig(threads = 1))
            try {
                val server = engine.bindPipeline(listOf(loopbackSpec(), loopbackSpec())) { }
                val ports = server.localAddresses.map { portOf(it) }
                server.close()
                ports.forEach { assertPortReleased(engine, it) }
            } finally {
                engine.close()
            }
        }
    }

    @Test
    fun `an accepted channel reports the local address of the listener it arrived on`() = runBlocking {
        withTimeout(15.seconds) {
            val engine = EpollEngine(IoEngineConfig(threads = 1))
            try {
                val seen = Channel<SocketAddress>(capacity = 4)
                val server = engine.bindPipeline(listOf(loopbackSpec(), loopbackSpec())) { ch ->
                    ch.localAddress?.let { seen.trySend(it) }
                    ch.pipeline.addLast("echo", EchoHandler())
                }
                try {
                    val target = portOf(server.localAddresses[1])
                    assertEchoServed(target)
                    assertEquals(target, portOf(seen.receive()))
                } finally {
                    server.close()
                }
            } finally {
                engine.close()
            }
        }
    }

    @Test
    fun `an empty bind list is rejected`() = runBlocking<Unit> {
        withTimeout(15.seconds) {
            val engine = EpollEngine(IoEngineConfig(threads = 1))
            try {
                assertFailsWith<IllegalArgumentException> {
                    engine.bindPipeline(emptyList()) { }
                }
            } finally {
                engine.close()
            }
        }
    }

    private companion object {
        const val PORT_RELEASE_BUDGET_MS = 2_000L
        const val PORT_RELEASE_POLL_MICROS: UInt = 20_000u
    }
}
