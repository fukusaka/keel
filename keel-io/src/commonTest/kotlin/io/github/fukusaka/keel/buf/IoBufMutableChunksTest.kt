package io.github.fukusaka.keel.buf

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Contract tests for [IoBufMutableChunks]: the add-side collector that holds
 * existing pooled [IoBuf] chunks and finalises to an [IoBufChunks] or a
 * contiguous `ByteArray`. Pins ownership transfer, both finalisers, empty-chunk
 * dropping, and abort-path release (via [TrackingAllocator]'s leak counter).
 */
class IoBufMutableChunksTest {

    private fun bufOf(tracker: TrackingAllocator, bytes: ByteArray): IoBuf {
        val buf = tracker.allocate(bytes.size)
        buf.writeByteArray(bytes, 0, bytes.size)
        return buf
    }

    @Test
    fun `toByteArray flattens added chunks in order`() {
        val tracker = TrackingAllocator(DefaultAllocator)
        val acc = IoBufMutableChunks()
        acc.add(bufOf(tracker, byteArrayOf(1, 2, 3)))
        acc.add(bufOf(tracker, byteArrayOf(4, 5)))
        acc.add(bufOf(tracker, byteArrayOf(6, 7, 8, 9)))
        assertEquals(9, acc.size)
        assertEquals(3, acc.chunkCount)
        val out = acc.toByteArray()
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9), out)
        tracker.assertNoLeaks("toByteArray must release every chunk")
    }

    @Test
    fun `toIoBufChunks hands off the added chunks without copy`() {
        val tracker = TrackingAllocator(DefaultAllocator)
        val acc = IoBufMutableChunks()
        acc.add(bufOf(tracker, byteArrayOf(1, 2, 3)))
        acc.add(bufOf(tracker, byteArrayOf(4, 5, 6)))
        val chunks = acc.toIoBufChunks()
        try {
            assertEquals(6, chunks.totalSize)
            assertEquals(2, chunks.chunkCount)
            val flat = ByteArray(6)
            var off = 0
            chunks.forEach { c ->
                val n = c.readableBytes
                c.readByteArray(flat, off, n)
                off += n
            }
            assertContentEquals(byteArrayOf(1, 2, 3, 4, 5, 6), flat)
        } finally {
            chunks.release()
        }
        tracker.assertNoLeaks("toIoBufChunks hand-off + release must free every chunk")
    }

    @Test
    fun `an empty chunk is dropped and released rather than held`() {
        val tracker = TrackingAllocator(DefaultAllocator)
        val acc = IoBufMutableChunks()
        acc.add(bufOf(tracker, byteArrayOf(1, 2)))
        acc.add(tracker.allocate(4)) // allocated, nothing written -> 0 readable -> dropped
        acc.add(bufOf(tracker, byteArrayOf(3)))
        assertEquals(2, acc.chunkCount, "empty chunk must not be held")
        assertEquals(3, acc.size)
        acc.release()
        tracker.assertNoLeaks("the dropped empty + held chunks must all be released")
    }

    @Test
    fun `release frees every added chunk on the abort path`() {
        val tracker = TrackingAllocator(DefaultAllocator)
        val acc = IoBufMutableChunks()
        acc.add(bufOf(tracker, byteArrayOf(1, 2, 3)))
        acc.add(bufOf(tracker, byteArrayOf(4, 5, 6)))
        assertTrue(acc.release(), "release frees at least one chunk")
        tracker.assertNoLeaks("release must free every added chunk")
    }

    @Test
    fun `toByteArray on an empty accumulator returns an empty array`() {
        val acc = IoBufMutableChunks()
        assertEquals(0, acc.size)
        assertEquals(0, acc.chunkCount)
        assertContentEquals(ByteArray(0), acc.toByteArray())
    }
}
