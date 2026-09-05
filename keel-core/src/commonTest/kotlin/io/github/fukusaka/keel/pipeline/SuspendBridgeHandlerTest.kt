package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SuspendBridgeHandlerTest {

    private val logger = PrintLogger("bridge-test")
    private val allocator: BufferAllocator = DefaultAllocator

    private val transport = TestIoTransport()
    private val channel = object : AbstractPipelinedChannel(transport, logger) {}

    private fun createPipelineWithBridge(): Pair<Pipeline, SuspendBridgeHandler> {
        val bridge = channel.ensureBridge()
        return Pair(channel.pipeline, bridge)
    }

    private fun allocBuf(vararg bytes: Byte): IoBuf {
        val buf = allocator.allocate(bytes.size)
        for (b in bytes) buf.writeByte(b)
        return buf
    }

    @Test
    fun `readOwned returns IoBuf from queue without copying`() {
        runTest {
            val (pipeline, bridge) = createPipelineWithBridge()

            val buf = allocBuf(0x41, 0x42)
            pipeline.notifyRead(buf)

            val owned = bridge.readOwned()!!
            assertEquals(2, owned.readableBytes)
            assertEquals(0x41.toByte(), owned.readByte())
            assertEquals(0x42.toByte(), owned.readByte())
            owned.release()
        }
    }

    @Test
    fun `removing the bridge releases a reader parked on it and what it queued`() {
        runTest {
            val (pipeline, bridge) = createPipelineWithBridge()
            val reader = async(start = CoroutineStart.UNDISPATCHED) { bridge.readOwned() }
            assertFalse(reader.isCompleted, "premise: the reader is parked")

            pipeline.remove(PipelinedChannel.SUSPEND_BRIDGE_NAME)

            assertNull(reader.await(), "removal is the bridge's ending: the reader sees EOF")
            assertTrue(bridge.isEof)
        }
    }

    @Test
    fun `readOwned returns null on EOF`() {
        runTest {
            val (pipeline, bridge) = createPipelineWithBridge()
            pipeline.notifyInactive()

            val owned = bridge.readOwned()
            assertNull(owned)
        }
    }

    /** Allocates a buffer carrying [size] readable bytes (content irrelevant). */
    private fun allocFilled(size: Int): IoBuf {
        val buf = allocator.allocate(size)
        repeat(size) { buf.writeByte(0x55) }
        return buf
    }

    @Test
    fun `crossing the high watermark suspends the channel read and draining to the low watermark re-arms it`() {
        runTest {
            val (pipeline, bridge) = createPipelineWithBridge()
            transport.readEnabled = true

            // 8 KiB deliveries up to just below the 64 KiB high watermark:
            // read stays enabled.
            val chunk = 8 * 1024
            repeat(7) { pipeline.notifyRead(allocFilled(chunk)) } // 56 KiB
            assertTrue(transport.readEnabled, "below the high watermark the read stays armed")
            assertFalse(bridge.readSuspendedByWatermark)

            // The 8th delivery reaches 64 KiB: the bridge suspends the read.
            pipeline.notifyRead(allocFilled(chunk))
            assertFalse(transport.readEnabled, "reaching the high watermark must suspend the channel read")
            assertTrue(bridge.readSuspendedByWatermark)

            // Draining to 40 KiB (> 32 KiB low watermark): still suspended —
            // hysteresis, not an immediate re-arm.
            repeat(3) { bridge.readOwned()!!.release() } // 64 -> 40 KiB
            assertFalse(transport.readEnabled, "above the low watermark the read stays suspended")

            // One more dequeue lands exactly on the 32 KiB low watermark: re-armed.
            bridge.readOwned()!!.release() // 40 -> 32 KiB
            assertTrue(transport.readEnabled, "draining to the low watermark must re-arm the read")
            assertFalse(bridge.readSuspendedByWatermark)
        }
    }

    @Test
    fun `the watermark flips route through the channel's pause and resume`() {
        runTest {
            val (pipeline, bridge) = createPipelineWithBridge()
            transport.readEnabled = true

            pipeline.notifyRead(allocFilled(64 * 1024))
            assertEquals(1, transport.pauseReadsCount, "the high watermark must call pauseReads, not raw readEnabled")
            assertEquals(0, transport.resumeReadsCount)

            // The single 64 KiB delivery drains in one dequeue, crossing
            // the low watermark immediately.
            bridge.readOwned()!!.release()
            assertEquals(1, transport.resumeReadsCount, "draining to the low watermark must call resumeReads")
        }
    }

    @Test
    fun `partial consumption through read accounts only the consumed bytes`() {
        runTest {
            val (pipeline, bridge) = createPipelineWithBridge()
            transport.readEnabled = true

            // One 64 KiB delivery suspends the read.
            pipeline.notifyRead(allocFilled(64 * 1024))
            assertFalse(transport.readEnabled)

            // Consuming 16 KiB leaves 48 KiB queued (> 32 KiB): still suspended.
            val small = allocator.allocate(16 * 1024)
            assertEquals(16 * 1024, bridge.read(small))
            assertFalse(transport.readEnabled, "48 KiB backlog is still above the low watermark")

            // Consuming another 16 KiB reaches the 32 KiB low watermark: re-armed.
            small.clear()
            assertEquals(16 * 1024, bridge.read(small))
            assertTrue(transport.readEnabled, "32 KiB backlog re-arms the read")
            small.release()
        }
    }

    @Test
    fun `inactivation while suspended resets the watermark state without re-arming`() {
        runTest {
            val (pipeline, bridge) = createPipelineWithBridge()
            transport.readEnabled = true

            pipeline.notifyRead(allocFilled(64 * 1024))
            assertFalse(transport.readEnabled, "suspended at the high watermark")

            // EOF drains the queue: the channel is going away, so the
            // bridge must not re-arm a dead transport's read.
            pipeline.notifyInactive()
            assertFalse(transport.readEnabled, "inactivation must not re-arm the read")
            assertFalse(bridge.readSuspendedByWatermark, "watermark state resets on EOF")
            assertNull(bridge.readOwned())
        }
    }

    @Test
    fun `readOwned delivers multiple buffers in order`() {
        runTest {
            val (pipeline, bridge) = createPipelineWithBridge()

            pipeline.notifyRead(allocBuf(0x01))
            pipeline.notifyRead(allocBuf(0x02))

            val first = bridge.readOwned()!!
            assertEquals(0x01.toByte(), first.readByte())
            first.release()

            val second = bridge.readOwned()!!
            assertEquals(0x02.toByte(), second.readByte())
            second.release()
        }
    }
}
