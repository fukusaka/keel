package io.github.fukusaka.keel.buf

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Contract tests for [IoBufAccumulator]: the write-side builder of an
 * [IoBufChunks] / contiguous `ByteArray` from a streaming codec's pooled
 * chunk output. Pins the commit lifecycle, both finalisers, the trailing
 * trim, and abort-path release (via [TrackingAllocator]'s leak counter).
 */
class IoBufAccumulatorTest {

    // A 4-byte chunk size forces multi-chunk behaviour on small inputs.
    private val tinyChunk = 4

    /** Writes [bytes] through the accumulator, committing whenever the chunk fills. */
    private fun accumulate(acc: IoBufAccumulator, bytes: ByteArray) {
        var i = 0
        while (i < bytes.size) {
            val chunk = acc.writableChunk()
            val n = minOf(chunk.writableBytes, bytes.size - i)
            chunk.writeByteArray(bytes, i, n)
            i += n
            if (chunk.writableBytes == 0) acc.commit()
        }
        acc.commit() // trailing partial
    }

    @Test
    fun `toByteArray flattens multi-chunk accumulation in order`() {
        val tracker = TrackingAllocator(DefaultAllocator)
        val acc = IoBufAccumulator(tracker, tinyChunk)
        val payload = ByteArray(10) { (it + 1).toByte() } // 10 bytes -> 3 chunks (4+4+2)
        accumulate(acc, payload)
        assertEquals(10, acc.size)
        val out = acc.toByteArray()
        assertContentEquals(payload, out)
        tracker.assertNoLeaks("toByteArray must release every chunk")
    }

    @Test
    fun `toIoBufChunks hands off the committed chunks in order`() {
        val acc = IoBufAccumulator(DefaultAllocator, tinyChunk)
        val payload = ByteArray(9) { (it + 1).toByte() } // 9 bytes -> 3 chunks (4+4+1)
        accumulate(acc, payload)
        val chunks = acc.toIoBufChunks()
        try {
            assertEquals(9, chunks.totalSize)
            assertEquals(3, chunks.chunkCount)
            // Re-read the chunk bytes in order.
            val flat = ByteArray(9)
            var off = 0
            chunks.forEach { c ->
                val n = c.readableBytes
                c.readByteArray(flat, off, n)
                off += n
            }
            assertContentEquals(payload, flat)
        } finally {
            chunks.release()
        }
    }

    @Test
    fun `an empty trailing chunk is not committed`() {
        val acc = IoBufAccumulator(DefaultAllocator, tinyChunk)
        val payload = ByteArray(8) { (it + 1).toByte() } // exactly 2 full chunks
        accumulate(acc, payload) // last commit() sees an empty fresh chunk
        val chunks = acc.toIoBufChunks()
        try {
            assertEquals(2, chunks.chunkCount, "no zero-length trailing chunk")
            assertEquals(8, chunks.totalSize)
        } finally {
            chunks.release()
        }
    }

    @Test
    fun `trimTail drops trailing bytes spanning chunk boundaries`() {
        val acc = IoBufAccumulator(DefaultAllocator, tinyChunk)
        val payload = ByteArray(10) { (it + 1).toByte() } // 3 chunks (4+4+2)
        accumulate(acc, payload)
        acc.trimTail(3) // drops the last 2-byte chunk fully + 1 byte off the middle chunk
        val out = acc.toByteArray()
        assertContentEquals(payload.copyOf(7), out)
    }

    @Test
    fun `trimTail fails fast when fewer bytes are committed than requested`() {
        val acc = IoBufAccumulator(DefaultAllocator, tinyChunk)
        accumulate(acc, byteArrayOf(1, 2))
        assertFailsWith<IllegalStateException> { acc.trimTail(4) }
        acc.release()
    }

    @Test
    fun `release frees committed and in-flight chunks on the abort path`() {
        val tracker = TrackingAllocator(DefaultAllocator)
        val acc = IoBufAccumulator(tracker, tinyChunk)
        // Commit one full chunk, then leave a partly-written in-flight chunk.
        val c0 = acc.writableChunk()
        c0.writeByteArray(byteArrayOf(1, 2, 3, 4), 0, 4)
        acc.commit()
        val c1 = acc.writableChunk()
        c1.writeByteArray(byteArrayOf(5, 6), 0, 2) // in-flight, not committed
        assertTrue(acc.release(), "release frees at least one buffer")
        tracker.assertNoLeaks("release must free both committed and in-flight chunks")
    }

    @Test
    fun `a single small message fits one chunk and round-trips`() {
        val tracker = TrackingAllocator(DefaultAllocator)
        val acc = IoBufAccumulator(tracker) // default 8 KiB chunk
        val payload = "hello websocket".encodeToByteArray()
        accumulate(acc, payload)
        assertContentEquals(payload, acc.toByteArray())
        tracker.assertNoLeaks()
    }
}
