package io.github.fukusaka.keel.engine.iouring

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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

/**
 * Lifecycle coverage for the multi-address `bindPipeline` overload on the
 * io_uring engine: bind-order address reporting, echo service on every bound
 * address, the single-entry degenerate case, all-or-nothing rollback with
 * the rolled-back port claimable again, whole-server close releasing every
 * port, the accepted channel reporting the local address of the listener
 * it arrived on, and the empty-list rejection.
 *
 * This engine's inet listeners are `SO_REUSEPORT` sockets, so "the port
 * is free again" cannot be asserted by rebinding through the engine (a
 * REUSEPORT bind joins a live group instead of failing) — occupancy and
 * release are probed with a plain (non-REUSEPORT) listener socket, which
 * the kernel rejects while any REUSEPORT socket holds the port and
 * accepts once all are closed. Bind failures are forced the same way: a
 * plain listener on the target port makes the engine's REUSEPORT bind
 * fail with EADDRINUSE.
 */
@OptIn(ExperimentalForeignApi::class)
class IoUringMultiAddressBindTest {

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

    private val probeOps = PosixNativeSocketOps(NoopLoggerFactory.logger("probe"))
    private val loopbackIp = IpAddress.parse("127.0.0.1")

    /** Binds a plain (non-REUSEPORT) listener on [port]; the caller owns the fd. */
    private fun plainListener(port: Int): Int = probeOps.bindListener(loopbackIp, port, backlog = 1)

    /**
     * Claims [port] with a plain (non-REUSEPORT) listener under a bounded
     * retry, failing if the budget is exhausted. The plain bind fails while
     * any of the engine's REUSEPORT sockets still holds the port. Retry is
     * needed because the server's close() releases asynchronously: each
     * worker cancels its armed accept SQE and closes its fd on its own
     * EventLoop, so the kernel-side file reference drops one EL dispatch
     * (plus cancel processing) after close() returns.
     */
    private fun assertPortReleased(port: Int, budgetMillis: Long = 2_000L) {
        val mark = kotlin.time.TimeSource.Monotonic.markNow()
        var last: Throwable? = null
        while (mark.elapsedNow().inWholeMilliseconds < budgetMillis) {
            val fd = runCatching { plainListener(port) }
                .onFailure { last = it }
                .getOrNull()
            if (fd != null) {
                closeFdSafely(fd, NoopLoggerFactory.logger("probe"), "port release probe")
                return
            }
            platform.posix.usleep(20_000u)
        }
        throw AssertionError("port $port still bound ${budgetMillis}ms after the listener was closed", last)
    }

    @Test
    fun `binding two addresses serves connections on both and reports them in bind order`() = runBlocking {
        withTimeout(15.seconds) {
            val engine = IoUringEngine(IoEngineConfig(threads = 1))
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
            val engine = IoUringEngine(IoEngineConfig(threads = 1))
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
            val engine = IoUringEngine(IoEngineConfig(threads = 1))
            try {
                // A plain (non-REUSEPORT) listener occupies the second entry's
                // port, so the engine's REUSEPORT bind deterministically fails.
                val blockerFd = plainListener(0)
                try {
                    val blockerPort = portOf(probeOps.getLocalAddress(blockerFd))
                    // A port that is free right now (native close releases synchronously).
                    val probe = engine.bindPipeline(InetSocketAddress("127.0.0.1", 0)) { }
                    val freePort = portOf(probe.localAddress)
                    probe.close()
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
            val engine = IoUringEngine(IoEngineConfig(threads = 1))
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
            val engine = IoUringEngine(IoEngineConfig(threads = 1))
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
            val engine = IoUringEngine(IoEngineConfig(threads = 1))
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
