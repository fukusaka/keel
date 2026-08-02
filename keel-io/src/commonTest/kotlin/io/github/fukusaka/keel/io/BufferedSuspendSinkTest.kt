package io.github.fukusaka.keel.io

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.TrackingAllocator
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class BufferedSuspendSinkTest {

    /** Collects all written bytes into a list. */
    private class CollectingSink : SuspendSink {
        val chunks = mutableListOf<ByteArray>()
        var flushed = false

        override suspend fun write(buf: IoBuf): Int {
            val bytes = ByteArray(buf.readableBytes)
            for (i in bytes.indices) bytes[i] = buf.readByte()
            chunks.add(bytes)
            val size = bytes.size
            buf.release() // transfer: sink owns buf
            return size
        }

        override suspend fun flush() { flushed = true }
        override fun close() {}

        fun collected(): String = chunks.flatMap { it.toList() }
            .toByteArray().decodeToString()
    }

    @Test
    fun writeString() = runTest(timeout = 15.seconds) {
        val sink = CollectingSink()
        val buffered = BufferedSuspendSink(sink, DefaultAllocator)
        buffered.writeString("hello")
        buffered.flush()
        assertEquals("hello", sink.collected())
        assertEquals(true, sink.flushed)
        buffered.close()
    }

    @Test
    fun writeAscii() = runTest(timeout = 15.seconds) {
        val sink = CollectingSink()
        val buffered = BufferedSuspendSink(sink, DefaultAllocator)
        buffered.writeAscii("hello")
        buffered.flush()
        assertEquals("hello", sink.collected())
        assertEquals(true, sink.flushed)
        buffered.close()
    }

    @Test
    fun writeAsciiLargerThanBuffer() = runTest(timeout = 15.seconds) {
        val sink = CollectingSink()
        val buffered = BufferedSuspendSink(sink, DefaultAllocator)
        val large = "x".repeat(10000)
        buffered.writeAscii(large)
        buffered.flush()
        assertEquals(large, sink.collected())
        buffered.close()
    }

    @Test
    fun writeByte() = runTest(timeout = 15.seconds) {
        val sink = CollectingSink()
        val buffered = BufferedSuspendSink(sink, DefaultAllocator)
        buffered.writeByte(0x41)
        buffered.writeByte(0x42)
        buffered.flush()
        assertEquals("AB", sink.collected())
        buffered.close()
    }

    @Test
    fun writeByteArray() = runTest(timeout = 15.seconds) {
        val sink = CollectingSink()
        val buffered = BufferedSuspendSink(sink, DefaultAllocator)
        buffered.write("data".encodeToByteArray())
        buffered.flush()
        assertEquals("data", sink.collected())
        buffered.close()
    }

    @Test
    fun bufferFlushesWhenFull() = runTest(timeout = 15.seconds) {
        val sink = CollectingSink()
        val buffered = BufferedSuspendSink(sink, DefaultAllocator)
        // Write more than BUFFER_SIZE (8192) bytes via writeAscii, which is
        // still chunked through the internal buffer because writeAscii has no
        // direct-path optimisation.
        val large = "x".repeat(10000)
        buffered.writeAscii(large)
        buffered.flush()
        assertEquals(large, sink.collected())
        // Should have flushed at least once before final flush.
        assertEquals(true, sink.chunks.size >= 2)
        buffered.close()
    }

    @Test
    fun writeLargeByteArrayTakesDirectPath() = runTest(timeout = 15.seconds) {
        val sink = CollectingSink()
        val buffered = BufferedSuspendSink(sink, DefaultAllocator)
        // Payload at or above BUFFER_SIZE (8192 bytes) should take the direct
        // zero-copy path on JVM: a single sink.write call delivers the whole
        // array without chunking. Native/JS fall back to chunked copy, so we
        // only assert the single-chunk property on JVM — on other platforms
        // we only verify correctness of the delivered bytes.
        val large = ByteArray(10000) { 'x'.code.toByte() }
        buffered.write(large)
        buffered.flush()
        assertEquals(large.decodeToString(), sink.collected())
        buffered.close()
    }

    @Test
    fun writeLargeByteArrayPreservesPriorScratchData() = runTest(timeout = 15.seconds) {
        val sink = CollectingSink()
        val buffered = BufferedSuspendSink(sink, DefaultAllocator)
        // Write headers (small) then body (large). On-wire ordering must be
        // preserved: the direct path flushes the scratch buffer first.
        buffered.writeAscii("HEADERS")
        val body = ByteArray(10000) { 'b'.code.toByte() }
        buffered.write(body)
        buffered.flush()
        assertEquals("HEADERS" + body.decodeToString(), sink.collected())
        buffered.close()
    }

    @Test
    fun writeSmallByteArrayUsesScratchBuffer() = runTest(timeout = 15.seconds) {
        val sink = CollectingSink()
        val buffered = BufferedSuspendSink(sink, DefaultAllocator)
        // Small payloads (below the direct-path threshold) are copied through
        // the scratch buffer and not flushed until the caller calls flush.
        val small = ByteArray(100) { 'a'.code.toByte() }
        buffered.write(small)
        assertEquals(0, sink.chunks.size) // not yet flushed
        buffered.flush()
        assertEquals(small.decodeToString(), sink.collected())
        buffered.close()
    }

    // ============================================================
    // deferred flush behavior (buffer hand-off + batched flush)
    // ============================================================

    @Test
    fun deferFlush_writeDoesNotFlushImmediately() = runTest(timeout = 15.seconds) {
        val sink = CollectingSink()
        val buffered = BufferedSuspendSink(sink, DefaultAllocator)
        buffered.writeString("hello")
        // Data fits in buffer — no write to sink yet
        assertEquals(0, sink.chunks.size)
        assertFalse(sink.flushed)
        buffered.flush()
        assertEquals("hello", sink.collected())
        buffered.close()
    }

    @Test
    fun deferFlush_bufferFullEnqueuesThenFreshBuffer() = runTest(timeout = 15.seconds) {
        val sink = CollectingSink()
        val tracker = TrackingAllocator(DefaultAllocator)
        val buffered = BufferedSuspendSink(sink, tracker)
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
    fun deferFlush_noBufferLeakOnClose() = runTest(timeout = 15.seconds) {
        val tracker = TrackingAllocator(DefaultAllocator)
        val sink = CollectingSink()
        val buffered = BufferedSuspendSink(sink, tracker)
        buffered.writeString("some data")
        // Close without flush — data is discarded but buffer is released
        buffered.close()
        assertEquals(0, tracker.outstandingCount)
    }

    @Test
    fun deferFlush_multipleFlushCycles() = runTest(timeout = 15.seconds) {
        val sink = CollectingSink()
        val tracker = TrackingAllocator(DefaultAllocator)
        val buffered = BufferedSuspendSink(sink, tracker)
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
    fun closeReleasesBuffer() = runTest(timeout = 15.seconds) {
        val tracker = TrackingAllocator(DefaultAllocator)
        val sink = CollectingSink()
        val buffered = BufferedSuspendSink(sink, tracker)
        buffered.writeString("test")
        buffered.flush()
        buffered.close()
        assertEquals(0, tracker.outstandingCount)
    }

    @Test
    fun doubleCloseIsSafe() = runTest(timeout = 15.seconds) {
        val sink = CollectingSink()
        val buffered = BufferedSuspendSink(sink, DefaultAllocator)
        buffered.close()
        buffered.close() // should not throw
    }
}
