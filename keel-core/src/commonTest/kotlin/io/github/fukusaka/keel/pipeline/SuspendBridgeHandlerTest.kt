package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
    fun `readOwned returns null on EOF`() {
        runTest {
            val (pipeline, bridge) = createPipelineWithBridge()
            pipeline.notifyInactive()

            val owned = bridge.readOwned()
            assertNull(owned)
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
