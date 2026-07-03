package io.github.fukusaka.keel.engine.nwconnection

import io.github.fukusaka.keel.core.BindSpec
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.core.IpAddress
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import io.github.fukusaka.keel.native.posix.PosixNativeSocketOps
import io.github.fukusaka.keel.native.posix.PosixRawClient
import io.github.fukusaka.keel.native.posix.closeFdSafely
import io.github.fukusaka.keel.pipeline.InboundHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.close
import platform.posix.usleep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

/**
 * Lifecycle coverage for the multi-address `bindPipeline` overload on the
 * NWConnection engine: bind-order address reporting, echo service on every
 * bound address, the single-entry degenerate case, all-or-nothing rollback
 * with the rolled-back port claimable again, whole-server close releasing
 * every port, the accepted channel reporting its listener's address (the
 * value this engine wires — Network.framework exposes no cheap
 * per-connection local-endpoint query on the pipelined path), and the
 * empty-list rejection.
 *
 * Listener teardown is asynchronous on Network.framework's dispatch
 * queues, so port occupancy and release are probed with a plain POSIX
 * listener under a bounded retry. A plain POSIX listener also forces the
 * deterministic mid-list bind failure (the NWListener reaches its failed
 * state with EADDRINUSE).
 */
@OptIn(ExperimentalForeignApi::class)
class NwMultiAddressBindTest {

    /** Echoes every inbound IoBuf straight back to the peer (transfers ownership). */
    private class EchoHandler : InboundHandler {
        override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
            ctx.propagateWriteAndFlush(msg)
        }
    }

    private val probeOps = PosixNativeSocketOps(NoopLoggerFactory.logger("probe"))
    private val loopbackIp = IpAddress.parse("127.0.0.1")

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
     * Claims [port] with a plain POSIX listener under a bounded retry —
     * NWListener cancellation completes asynchronously on its dispatch
     * queue, so release is prompt but not synchronous with close().
     */
    private fun assertPortReleased(port: Int, budgetMillis: Long = 5_000L) {
        val mark = kotlin.time.TimeSource.Monotonic.markNow()
        var last: Throwable? = null
        while (mark.elapsedNow().inWholeMilliseconds < budgetMillis) {
            val fd = runCatching { probeOps.bindListener(loopbackIp, port, backlog = 1) }
                .onFailure { last = it }
                .getOrNull()
            if (fd != null) {
                closeFdSafely(fd, NoopLoggerFactory.logger("probe"), "port release probe")
                return
            }
            usleep(20_000u)
        }
        throw AssertionError("port $port still bound ${budgetMillis}ms after the listener was closed", last)
    }

    @Test
    fun `binding two addresses serves connections on both and reports them in bind order`() = runBlocking {
        withTimeout(15.seconds) {
            val engine = NwEngine(IoEngineConfig(threads = 1))
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
            val engine = NwEngine(IoEngineConfig(threads = 1))
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
        withTimeout(30.seconds) {
            val engine = NwEngine(IoEngineConfig(threads = 1))
            try {
                // A plain POSIX listener occupies the second entry's port so
                // the NWListener deterministically reaches its failed state.
                val blockerFd = probeOps.bindListener(loopbackIp, port = 0, backlog = 1)
                try {
                    val blockerPort = portOf(probeOps.getLocalAddress(blockerFd))
                    // A port that is free right now; claimable again after rollback.
                    val probeFd = probeOps.bindListener(loopbackIp, port = 0, backlog = 1)
                    val freePort = portOf(probeOps.getLocalAddress(probeFd))
                    closeFdSafely(probeFd, NoopLoggerFactory.logger("probe"), "free port probe")
                    assertFailsWith<IllegalStateException> {
                        engine.bindPipeline(
                            listOf(loopbackSpec(freePort), loopbackSpec(blockerPort)),
                        ) { }
                    }
                    assertPortReleased(freePort)
                } finally {
                    closeFdSafely(blockerFd, NoopLoggerFactory.logger("probe"), "blocker close")
                }
            } finally {
                engine.close()
            }
        }
    }

    @Test
    fun `closing a multi-address server releases every port`() = runBlocking {
        withTimeout(15.seconds) {
            val engine = NwEngine(IoEngineConfig(threads = 1))
            try {
                val server = engine.bindPipeline(listOf(loopbackSpec(), loopbackSpec())) { }
                val ports = server.localAddresses.map { portOf(it) }
                server.close()
                ports.forEach { assertPortReleased(it) }
            } finally {
                engine.close()
            }
        }
    }

    @Test
    fun `an accepted channel reports the local address of the listener it arrived on`() = runBlocking {
        withTimeout(15.seconds) {
            val engine = NwEngine(IoEngineConfig(threads = 1))
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
            val engine = NwEngine(IoEngineConfig(threads = 1))
            try {
                assertFailsWith<IllegalArgumentException> {
                    engine.bindPipeline(emptyList()) { }
                }
            } finally {
                engine.close()
            }
        }
    }
}
