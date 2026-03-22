package io.github.fukusaka.keel.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class BufferedSuspendSinkTest {

    /** Collects all written bytes into a list. */
    private class CollectingSink : SuspendSink {
        val chunks = mutableListOf<ByteArray>()
        var flushed = false

        override suspend fun write(buf: NativeBuf): Int {
            val bytes = ByteArray(buf.readableBytes)
            for (i in bytes.indices) bytes[i] = buf.readByte()
            chunks.add(bytes)
            return bytes.size
        }

        override suspend fun flush() { flushed = true }
        override fun close() {}

        fun collected(): String = chunks.flatMap { it.toList() }
            .toByteArray().decodeToString()
    }

    @Test
    fun writeString() = runBlocking {
        val sink = CollectingSink()
        val buffered = BufferedSuspendSink(sink, HeapAllocator)
        buffered.writeString("hello")
        buffered.flush()
        assertEquals("hello", sink.collected())
        assertEquals(true, sink.flushed)
        buffered.close()
    }

    @Test
    fun writeByte() = runBlocking {
        val sink = CollectingSink()
        val buffered = BufferedSuspendSink(sink, HeapAllocator)
        buffered.writeByte(0x41)
        buffered.writeByte(0x42)
        buffered.flush()
        assertEquals("AB", sink.collected())
        buffered.close()
    }

    @Test
    fun writeByteArray() = runBlocking {
        val sink = CollectingSink()
        val buffered = BufferedSuspendSink(sink, HeapAllocator)
        buffered.write("data".encodeToByteArray())
        buffered.flush()
        assertEquals("data", sink.collected())
        buffered.close()
    }

    @Test
    fun bufferFlushesWhenFull() = runBlocking {
        val sink = CollectingSink()
        val buffered = BufferedSuspendSink(sink, HeapAllocator)
        // Write more than BUFFER_SIZE (8192) bytes
        val large = "x".repeat(10000)
        buffered.writeString(large)
        buffered.flush()
        assertEquals(large, sink.collected())
        // Should have flushed at least once before final flush
        assertEquals(true, sink.chunks.size >= 2)
        buffered.close()
    }
}
