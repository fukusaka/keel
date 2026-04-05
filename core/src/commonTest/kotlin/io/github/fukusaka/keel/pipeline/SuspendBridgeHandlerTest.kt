package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.logging.PrintLogger
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SuspendBridgeHandlerTest {

    private val logger = PrintLogger("bridge-test")
    private val allocator: BufferAllocator = DefaultAllocator

    private val transport = object : IoTransport {
        override fun write(buf: IoBuf) {}
        override fun flush(): Boolean = true
        override var onFlushComplete: (() -> Unit)? = null
        override fun close() {}
    }

    private val channel = object : PipelinedChannel {
        override lateinit var pipeline: ChannelPipeline
        override val isActive: Boolean = true
        override val isWritable: Boolean = true
        override val allocator: BufferAllocator get() = this@SuspendBridgeHandlerTest.allocator
    }

    private fun createPipelineWithBridge(): Pair<DefaultChannelPipeline, SuspendBridgeHandler> {
        val pipeline = DefaultChannelPipeline(channel, transport, logger)
        channel.pipeline = pipeline
        val bridge = SuspendBridgeHandler()
        pipeline.addLast(PipelinedChannel.SUSPEND_BRIDGE_NAME, bridge)
        return Pair(pipeline, bridge)
    }

    private fun allocBuf(vararg bytes: Byte): IoBuf {
        val buf = allocator.allocate(bytes.size)
        for (b in bytes) buf.writeByte(b)
        return buf
    }

    @Test
    fun `readOwned returns IoBuf from queue without copying`() {
        runBlocking {
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
        runBlocking {
            val (pipeline, bridge) = createPipelineWithBridge()
            pipeline.notifyInactive()

            val owned = bridge.readOwned()
            assertNull(owned)
        }
    }

    @Test
    fun `readOwned delivers multiple buffers in order`() {
        runBlocking {
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
