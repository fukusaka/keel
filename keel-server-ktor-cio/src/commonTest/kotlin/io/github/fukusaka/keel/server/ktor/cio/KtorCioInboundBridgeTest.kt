package io.github.fukusaka.keel.server.ktor.cio

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.logging.LogLevel
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import io.github.fukusaka.keel.pipeline.Pipeline
import io.github.fukusaka.keel.testing.InjectedFault
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class KtorCioInboundBridgeTest {

    private val allocator: BufferAllocator = DefaultAllocator
    private val transport = TestIoTransport()
    private val channel = object : AbstractPipelinedChannel(transport, PrintLogger("ktor-cio-bridge-test")) {}

    private class RecordingLogger : Logger {
        val records = mutableListOf<Pair<LogLevel, String>>()
        val warnings: List<String> get() = records.filter { it.first == LogLevel.WARN }.map { it.second }
        override fun isLoggable(level: LogLevel) = true
        override fun rawLog(level: LogLevel, throwable: Throwable?, message: Any?) {
            records.add(level to message.toString())
        }
    }

    private fun installBridge(): Pair<Pipeline, KtorCioInboundBridge> {
        val bridge = KtorCioInboundBridge()
        channel.pipeline.addLast("bridge", bridge)
        return Pair(channel.pipeline, bridge)
    }

    private fun allocBuf(vararg bytes: Byte): IoBuf {
        val buf = allocator.allocate(bytes.size)
        for (b in bytes) buf.writeByte(b)
        return buf
    }

    @Test
    fun `receiveCatching delivers IoBuf from pipeline`() = runTest(timeout = 15.seconds) {
        val (pipeline, bridge) = installBridge()
        pipeline.notifyRead(allocBuf(0x41, 0x42))

        val received = bridge.receiveCatching()
        assertTrue(received.isSuccess)
        val buf = received.getOrThrow()
        assertEquals(2, buf.readableBytes)
        assertEquals(0x41.toByte(), buf.readByte())
        assertEquals(0x42.toByte(), buf.readByte())
        buf.release()
    }

    @Test
    fun `multiple buffers are delivered in order`() = runTest(timeout = 15.seconds) {
        val (pipeline, bridge) = installBridge()
        pipeline.notifyRead(allocBuf(0x01))
        pipeline.notifyRead(allocBuf(0x02))
        pipeline.notifyRead(allocBuf(0x03))

        for (expected in listOf<Byte>(0x01, 0x02, 0x03)) {
            val buf = bridge.receiveCatching().getOrThrow()
            assertEquals(expected, buf.readByte())
            buf.release()
        }
    }

    @Test
    fun `onInactive closes the channel cleanly`() = runTest(timeout = 15.seconds) {
        val (pipeline, bridge) = installBridge()
        pipeline.notifyInactive()

        val received = bridge.receiveCatching()
        assertTrue(received.isClosed)
        assertNull(received.exceptionOrNull())
    }

    @Test
    fun `onError is handled here and does not travel on as unhandled`() = runTest(timeout = 15.seconds) {
        // Closing the inbound with the cause is handling it: everything this
        // bridge feeds is now finished, and the reason went with it. Passing
        // it on as well would reach the tail of a pipeline whose last handler
        // this is, which records what arrives there as an application bug --
        // on the ordinary path where a peer disappears mid-write.
        val log = RecordingLogger()
        val ownTransport = TestIoTransport()
        val ownChannel = object : AbstractPipelinedChannel(ownTransport, log) {}
        val bridge = KtorCioInboundBridge()
        ownChannel.pipeline.addLast("bridge", bridge)

        ownChannel.pipeline.notifyError(InjectedFault("peer is gone"))

        assertTrue(bridge.receiveCatching().isClosed, "the inbound is finished, with the reason")
        assertTrue(
            log.warnings.none { "Unhandled" in it },
            "and it is not reported as unhandled: ${log.warnings}",
        )
    }

    @Test
    fun `onError closes the channel with cause`() = runTest(timeout = 15.seconds) {
        val (pipeline, bridge) = installBridge()
        val error = InjectedFault("boom")
        pipeline.notifyError(error)

        val received = bridge.receiveCatching()
        assertTrue(received.isClosed)
        assertEquals(error, received.exceptionOrNull())
    }

    @Test
    fun `onRead pauses transport reads when pending bytes cross the high watermark`() =
        runTest(timeout = 15.seconds) {
            val (pipeline, bridge) = installBridge()
            // 70 KiB chunk crosses the 64 KiB high watermark — pauseReads
            // must fire once.
            val big = allocator.allocate(70_000).also { buf ->
                repeat(70_000) { buf.writeByte('x'.code.toByte()) }
            }
            pipeline.notifyRead(big)

            assertEquals(
                1,
                transport.pauseReadsCount,
                "pending bytes (70 KiB) crossed the high watermark — pauseReads must fire once",
            )
            assertEquals(
                0,
                transport.resumeReadsCount,
                "low watermark not yet reached — resumeReads must not fire while the queue is full",
            )

            // Drain it; receiveCatching decrements pendingBytes back below
            // the low watermark (32 KiB) so resumeReads fires.
            val buf = bridge.receiveCatching().getOrThrow()
            buf.release()

            assertEquals(
                1,
                transport.resumeReadsCount,
                "consumer drained the queue below the low watermark — resumeReads must fire once",
            )
        }

    @Test
    fun `pause and resume fire at most once across the hysteresis band`() =
        runTest(timeout = 15.seconds) {
            val (pipeline, bridge) = installBridge()
            // Push two 40 KiB chunks: the first leaves pendingBytes at
            // 40 KiB (below high), the second pushes to 80 KiB (above
            // high) — pauseReads fires exactly once.
            for (i in 0 until 2) {
                val buf = allocator.allocate(40_000).also { b ->
                    repeat(40_000) { b.writeByte(i.toByte()) }
                }
                pipeline.notifyRead(buf)
            }
            assertEquals(1, transport.pauseReadsCount, "exactly one pause across two pushes that cross high")

            // Draining the first chunk leaves pendingBytes at 40 KiB
            // (above 32 KiB low) — no resume yet.
            bridge.receiveCatching().getOrThrow().release()
            assertEquals(0, transport.resumeReadsCount, "still above low watermark — no resume yet")

            // Draining the second chunk drops to 0 — resume fires once.
            bridge.receiveCatching().getOrThrow().release()
            assertEquals(1, transport.resumeReadsCount, "queue drained below low watermark — exactly one resume")
        }

    @Test
    fun `close clears watermark state without issuing resume`() = runTest(timeout = 15.seconds) {
        val (pipeline, bridge) = installBridge()
        // Push enough to trip pause, then close without draining via the
        // bridge's receive path.
        val big = allocator.allocate(70_000).also { buf ->
            repeat(70_000) { buf.writeByte('z'.code.toByte()) }
        }
        pipeline.notifyRead(big)
        assertEquals(1, transport.pauseReadsCount, "pause must fire after high watermark crossed")

        bridge.close()

        assertEquals(
            0,
            transport.resumeReadsCount,
            "close must reset state silently — never resumeReads on a closing transport",
        )
        // The IoBuf was released by close; releasing again throws.
        assertFailsWith<IllegalStateException> { big.release() }
    }

    @Test
    fun `close drains and releases queued buffers`() = runTest(timeout = 15.seconds) {
        val (pipeline, bridge) = installBridge()
        val buf1 = allocBuf(0x10)
        val buf2 = allocBuf(0x20)
        pipeline.notifyRead(buf1)
        pipeline.notifyRead(buf2)

        // Skip receiveCatching — emulate a pump that never drained.
        bridge.close()

        // After close, receiveCatching reports closed and the queued IoBufs
        // were released as part of close — releasing again must throw
        // IllegalStateException (refcount already zero).
        assertTrue(bridge.receiveCatching().isClosed)
        assertFailsWith<IllegalStateException> { buf1.release() }
        assertFailsWith<IllegalStateException> { buf2.release() }
    }
}
