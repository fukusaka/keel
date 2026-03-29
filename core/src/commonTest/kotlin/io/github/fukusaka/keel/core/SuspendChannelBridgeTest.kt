package io.github.fukusaka.keel.core

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class SuspendChannelBridgeTest {

    /** Minimal Channel stub for testing the bridge delegates. */
    private class StubChannel : Channel {
        override val allocator: BufferAllocator = DefaultAllocator
        override val remoteAddress: SocketAddress? = null
        override val localAddress: SocketAddress? = null
        override val isOpen: Boolean = true
        override val isActive: Boolean = true

        var readCalled = false
        var writeCalled = false
        var flushCalled = false

        override suspend fun awaitClosed() {}

        override suspend fun read(buf: IoBuf): Int {
            readCalled = true
            buf.writeByte(0x42)
            return 1
        }

        override suspend fun write(buf: IoBuf): Int {
            writeCalled = true
            val n = buf.readableBytes
            buf.readerIndex += n
            return n
        }

        override suspend fun flush() {
            flushCalled = true
        }

        override fun shutdownOutput() {}
        override fun close() {}
    }

    @Test
    fun `SuspendChannelSource delegates read to channel`(): Unit = runBlocking {
        val channel = StubChannel()
        val source = channel.asSuspendSource()

        val buf = DefaultAllocator.allocate(16)
        val n = source.read(buf)

        assertEquals(1, n)
        assertEquals(true, channel.readCalled)
        assertEquals(0x42.toByte(), buf.readByte())
        buf.release()
    }

    @Test
    fun `SuspendChannelSink delegates write and flush to channel`(): Unit = runBlocking {
        val channel = StubChannel()
        val sink = channel.asSuspendSink()

        val buf = DefaultAllocator.allocate(16)
        buf.writeByte(0x41)
        val n = sink.write(buf)
        sink.flush()

        assertEquals(1, n)
        assertEquals(true, channel.writeCalled)
        assertEquals(true, channel.flushCalled)
        buf.release()
    }

    @Test
    fun `source close is no-op`() {
        val channel = StubChannel()
        val source = channel.asSuspendSource()
        // Should not throw or close the channel
        source.close()
        assertEquals(true, channel.isOpen)
    }

    @Test
    fun `sink close is no-op`() {
        val channel = StubChannel()
        val sink = channel.asSuspendSink()
        // Should not throw or close the channel
        sink.close()
        assertEquals(true, channel.isOpen)
    }
}
