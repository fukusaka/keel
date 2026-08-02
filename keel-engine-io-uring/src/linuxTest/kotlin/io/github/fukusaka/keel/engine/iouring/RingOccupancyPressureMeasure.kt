package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.logging.LogLevel
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.native.posix.PosixRawClient
import io.github.fukusaka.keel.pipeline.InboundHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.close
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Measurement harness for the provided-buffer-ring occupancy under
 * request-lifetime buffer retention — the grounding data for the 25%
 * copy-on-pressure watermark.
 *
 * A handler retains every recv delivery for a configurable interval
 * before releasing it and acking (the codec's header-view retention
 * shape), M loopback connections issue request rounds against a
 * single-EventLoop engine, and after a graceful close the per-ring
 * occupancy counters report: the min-available low watermark, recv
 * `-ENOBUFS` count, deferred re-arm count (each is a stalled receive),
 * and the copy-on-pressure count.
 *
 * In-tree measure (not a bench script) because the bench runner's
 * signal handler `_exit`s without the engine close that surfaces the
 * counters. Assertions are limited to robust invariants — every round
 * completes (no stall) and no receive starves into a deferred re-arm —
 * while the occupancy distribution itself is reported for analysis,
 * following the footprint-measure precedent.
 */
class RingOccupancyPressureMeasure {

    /**
     * Pins every inbound delivery for [retainMillis] on the connection's
     * deadline timer, then releases it and acks one byte — modelling a
     * request whose retained header buffer pins a ring slot for the
     * handler's whole latency.
     */
    private class RetainingAckHandler(private val retainMillis: Long) : InboundHandler {
        override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
            if (msg !is IoBuf) return ctx.propagateRead(msg)
            val scheduled = ctx.channel.scheduleDeadline(retainMillis) {
                msg.release()
                ack(ctx)
            }
            if (scheduled == null) {
                // No timer available (not expected on this engine): do not
                // silently pin forever.
                msg.release()
                ack(ctx)
            }
        }

        private fun ack(ctx: PipelineHandlerContext) {
            val buf = ctx.allocator.allocate(1)
            buf.writeByte(ACK)
            ctx.propagateWrite(buf)
            ctx.propagateFlush()
        }
    }

    private data class Cell(val slots: Int, val connections: Int, val retainMillis: Long)

    @Test
    fun `ring occupancy under retention pressure`() {
        val cells = listOf(
            // Default 64-slot ring: below, near, and beyond the slot count.
            Cell(slots = 64, connections = 16, retainMillis = 20),
            Cell(slots = 64, connections = 48, retainMillis = 20),
            Cell(slots = 64, connections = 96, retainMillis = 20),
            Cell(slots = 64, connections = 48, retainMillis = 200),
            Cell(slots = 64, connections = 96, retainMillis = 200),
            // Shrunk ring: forced deep pressure at modest concurrency.
            Cell(slots = 16, connections = 48, retainMillis = 200),
        )
        println("ring-occupancy: cell | min-avail/slots | enobufs | deferred-rearms | copy-on-pressure")
        for (cell in cells) {
            val report = runCell(cell)
            println("ring-occupancy: $report")
        }
    }

    private fun runCell(cell: Cell): String {
        // threads = 1: every connection shares one EventLoop and therefore
        // one ring, so `connections` is exactly the worst-case pin count.
        val engine = IoUringEngine(
            config = IoEngineConfig(
                threads = 1,
                loggerFactory = PrintLogger.Factory(LogLevel.WARN),
            ),
            bufferRingSlotCount = cell.slots,
        )
        val server = engine.bindPipeline("127.0.0.1", 0, BindConfig()) { channel ->
            channel.pipeline.addLast("retainer", RetainingAckHandler(cell.retainMillis))
        }
        val port = (server.localAddress as InetSocketAddress).port

        val fds = IntArray(cell.connections) { PosixRawClient.rawConnect(port) }
        var minAvailable = -1
        var enobufs = -1L
        var deferred = -1L
        var copies = -1L
        try {
            runBlocking {
                withTimeout(MEASURE_TIMEOUT_S.seconds) {
                    repeat(ROUNDS) {
                        // One request per connection, then collect every ack:
                        // steady state pins ~= connections for retainMillis.
                        for (fd in fds) PosixRawClient.rawWrite(fd, "x")
                        for (fd in fds) {
                            val ackByte = PosixRawClient.rawRead(fd, 1)
                            assertEquals(
                                ACK.toInt().toChar().toString(),
                                ackByte,
                                "every round must complete (no stall)",
                            )
                        }
                    }
                }
            }
        } finally {
            for (fd in fds) close(fd)
            server.close()
            // Snapshot before engine.close() tears the rings down. The
            // EventLoop is still running, but the connections are closed
            // and drained (every ack was read), so the counters are
            // quiescent for this workload.
            val ring = engine.workerGroup.bufferRingAt(0)
            if (ring != null) {
                minAvailable = ring.minAvailableLowWatermark()
                enobufs = ring.recvEnobufsCount()
                deferred = ring.deferredRearmCount()
                copies = ring.copyOnPressureCount()
            }
            runBlocking { engine.close() }
        }
        assertEquals(0L, deferred, "copy-on-pressure must prevent starvation (no deferred re-arms)")
        return "slots=${cell.slots} conns=${cell.connections} retain=${cell.retainMillis}ms" +
            " | min-avail=$minAvailable/${cell.slots} | enobufs=$enobufs" +
            " | deferred-rearms=$deferred | copy-on-pressure=$copies"
    }

    private companion object {
        private const val ACK: Byte = 0x21 // '!'

        /** Rounds per cell — enough to reach the steady-state pin plateau. */
        private const val ROUNDS = 5

        /**
         * Wall-clock ceiling per cell: 5 rounds at 200 ms retention plus
         * generous loopback dispatch margin.
         */
        private const val MEASURE_TIMEOUT_S = 30
    }
}
