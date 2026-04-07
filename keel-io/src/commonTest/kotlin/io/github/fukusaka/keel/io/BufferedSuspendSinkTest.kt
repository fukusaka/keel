package io.github.fukusaka.keel.io

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.TrackingAllocator
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BufferedSuspendSinkTest {

    /** Collects all written bytes into a list. */
    private class CollectingSink : SuspendSink {
        val chunks = mutableListOf<ByteArray>()
        var flushed = false

        override suspend fun write(buf: IoBuf): Int {
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
        val buffered = BufferedSuspendSink(sink, DefaultAllocator)
        buffered.writeString("hello")
        buffered.flush()
        assertEquals("hello", sink.collected())
        assertEquals(true, sink.flushed)
        buffered.close()
    }

    @Test
    fun writeAscii() = runBlocking {
        val sink = CollectingSink()
        val buffered = BufferedSuspendSink(sink, DefaultAllocator)
        buffered.writeAscii("hello")
        buffered.flush()
        assertEquals("hello", sink.collected())
        assertEquals(true, sink.flushed)
        buffered.close()
    }

    @Test
    fun writeAsciiLargerThanBuffer() = runBlocking {
        val sink = CollectingSink()
        val buffered = BufferedSuspendSink(sink, DefaultAllocator)
        val large = "x".repeat(10000)
        buffered.writeAscii(large)
        buffered.flush()
        assertEquals(large, sink.collected())
        buffered.close()
    }

    @Test
    fun writeByte() = runBlocking {
        val sink = CollectingSink()
        val buffered = BufferedSuspendSink(sink, DefaultAllocator)
        buffered.writeByte(0x41)
        buffered.writeByte(0x42)
        buffered.flush()
        assertEquals("AB", sink.collected())
        buffered.close()
    }

    @Test
    fun writeByteArray() = runBlocking {
        val sink = CollectingSink()
        val buffered = BufferedSuspendSink(sink, DefaultAllocator)
        buffered.write("data".encodeToByteArray())
        buffered.flush()
        assertEquals("data", sink.collected())
        buffered.close()
    }

    @Test
    fun bufferFlushesWhenFull() = runBlocking {
        val sink = CollectingSink()
        val buffered = BufferedSuspendSink(sink, DefaultAllocator)
        // Write more than BUFFER_SIZE (8192) bytes
        val large = "x".repeat(10000)
        buffered.writeString(large)
        buffered.flush()
        assertEquals(large, sink.collected())
        // Should have flushed at least once before final flush
        assertEquals(true, sink.chunks.size >= 2)
        buffered.close()
    }

    // ============================================================
    // deferFlush = true tests
    // ============================================================

    @Test
    fun deferFlush_writeDoesNotFlushImmediately() = runBlocking {
        val sink = CollectingSink()
        val buffered = BufferedSuspendSink(sink, DefaultAllocator, deferFlush = true)
        buffered.writeString("hello")
        // Data fits in buffer — no write to sink yet
        assertEquals(0, sink.chunks.size)
        assertFalse(sink.flushed)
        buffered.flush()
        assertEquals("hello", sink.collected())
        buffered.close()
    }

    @Test
    fun deferFlush_bufferFullEnqueuesThenFreshBuffer() = runBlocking {
        val sink = CollectingSink()
        val tracker = TrackingAllocator(DefaultAllocator)
        val buffered = BufferedSuspendSink(sink, tracker, deferFlush = true)
        // Write more than BUFFER_SIZE (8192) to trigger internal flushBuffer
        val large = "x".repeat(10000)
        buffered.writeString(large)
        // flushBuffer was called: sink.write enqueued old buffer, new buffer allocated
        assertTrue(sink.chunks.isNotEmpty())
        // Flush remaining
        buffered.flush()
        assertEquals(large, sink.collected())
        buffered.close()
        // All buffers released (no leak)
        assertEquals(0, tracker.outstandingCount)
    }

    @Test
    fun deferFlush_noBufferLeakOnClose() = runBlocking {
        val tracker = TrackingAllocator(DefaultAllocator)
        val sink = CollectingSink()
        val buffered = BufferedSuspendSink(sink, tracker, deferFlush = true)
        buffered.writeString("some data")
        // Close without flush — data is discarded but buffer is released
        buffered.close()
        assertEquals(0, tracker.outstandingCount)
    }

    @Test
    fun deferFlush_multipleFlushCycles() = runBlocking {
        val sink = CollectingSink()
        val tracker = TrackingAllocator(DefaultAllocator)
        val buffered = BufferedSuspendSink(sink, tracker, deferFlush = true)
        // Cycle 1
        buffered.writeAscii("AAA")
        buffered.flush()
        // Cycle 2
        buffered.writeAscii("BBB")
        buffered.flush()
        assertEquals("AAABBB", sink.collected())
        buffered.close()
        assertEquals(0, tracker.outstandingCount)
    }

    // ============================================================
    // close / resource tests
    // ============================================================

    @Test
    fun closeReleasesBuffer() = runBlocking {
        val tracker = TrackingAllocator(DefaultAllocator)
        val sink = CollectingSink()
        val buffered = BufferedSuspendSink(sink, tracker)
        buffered.writeString("test")
        buffered.flush()
        buffered.close()
        assertEquals(0, tracker.outstandingCount)
    }

    @Test
    fun doubleCloseIsSafe() = runBlocking {
        val sink = CollectingSink()
        val buffered = BufferedSuspendSink(sink, DefaultAllocator)
        buffered.close()
        buffered.close() // should not throw
    }
}
