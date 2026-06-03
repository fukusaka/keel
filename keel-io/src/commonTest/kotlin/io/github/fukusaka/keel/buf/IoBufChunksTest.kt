package io.github.fukusaka.keel.buf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Contract tests for the [IoBufChunks] public API: the carrier type for a
 * gather-write payload (`WsFrame.payloadChunks` etc.). Pins ownership
 * semantics, accessor bounds, and the iteration contract that downstream
 * encoders rely on.
 */
class IoBufChunksTest {

    private fun bufOf(bytes: ByteArray): IoBuf =
        DefaultAllocator.allocate(bytes.size).apply { writeByteArray(bytes, 0, bytes.size) }

    @Test
    fun `totalSize is the sum of every chunk's readableBytes`() {
        val a = bufOf(ByteArray(3))
        val b = bufOf(ByteArray(5))
        val c = bufOf(ByteArray(7))
        val chunks = IoBufChunks(listOf(a, b, c))
        try {
            assertEquals(15, chunks.totalSize)
            assertEquals(3, chunks.chunkCount)
        } finally {
            chunks.release()
        }
    }

    @Test
    fun `totalSize and chunkCount are zero for an empty list`() {
        val chunks = IoBufChunks(emptyList())
        assertEquals(0, chunks.totalSize)
        assertEquals(0, chunks.chunkCount)
        // release of an empty list must report no buffers freed and not throw.
        assertFalse(chunks.release(), "release on empty chunks must report no buffer freed")
    }

    @Test
    fun `chunkAt returns the chunks in construction order`() {
        val a = bufOf(byteArrayOf(1))
        val b = bufOf(byteArrayOf(2))
        val c = bufOf(byteArrayOf(3))
        val chunks = IoBufChunks(listOf(a, b, c))
        try {
            assertSame(a, chunks.chunkAt(0))
            assertSame(b, chunks.chunkAt(1))
            assertSame(c, chunks.chunkAt(2))
        } finally {
            chunks.release()
        }
    }

    @Test
    fun `chunkAt out of bounds throws with a descriptive message`() {
        val a = bufOf(byteArrayOf(0))
        val chunks = IoBufChunks(listOf(a))
        try {
            val negative = assertFailsWith<IndexOutOfBoundsException> { chunks.chunkAt(-1) }
            val tooLarge = assertFailsWith<IndexOutOfBoundsException> { chunks.chunkAt(1) }
            val tooLargeFar = assertFailsWith<IndexOutOfBoundsException> { chunks.chunkAt(99) }
            // Each message must include the offending index and the valid range,
            // so future debugging is not a guessing game.
            for (ex in listOf(negative, tooLarge, tooLargeFar)) {
                val msg = ex.message ?: ""
                assertTrue("index" in msg, "expected 'index' in message, got: $msg")
                assertTrue("[0.." in msg, "expected the valid range in message, got: $msg")
            }
        } finally {
            chunks.release()
        }
    }

    @Test
    fun `forEach visits every chunk in order exactly once`() {
        val a = bufOf(byteArrayOf(10))
        val b = bufOf(byteArrayOf(20))
        val c = bufOf(byteArrayOf(30))
        val chunks = IoBufChunks(listOf(a, b, c))
        try {
            val visited = mutableListOf<IoBuf>()
            chunks.forEach { visited.add(it) }
            assertEquals(listOf(a, b, c), visited)
        } finally {
            chunks.release()
        }
    }

    @Test
    fun `release returns true when at least one chunk's refCount reaches zero`() {
        val a = bufOf(byteArrayOf(1))
        val b = bufOf(byteArrayOf(2))
        val chunks = IoBufChunks(listOf(a, b))
        // First release frees both chunks (refCount started at 1).
        assertTrue(chunks.release(), "release must report a freed buffer")
    }

    @Test
    fun `mutating the source list after construction does not affect the chunks`() {
        // The carrier defensively copies the source list so caller-side
        // mutations cannot reach the held chunks (otherwise an encoder could
        // see a different set of chunks than the caller intended, or even an
        // empty list right when ownership transfer happens).
        val a = bufOf(byteArrayOf(1))
        val b = bufOf(byteArrayOf(2))
        val source = mutableListOf(a, b)
        val chunks = IoBufChunks(source)
        try {
            source.clear()
            source.add(bufOf(byteArrayOf(3)))
            assertEquals(2, chunks.chunkCount)
            assertSame(a, chunks.chunkAt(0))
            assertSame(b, chunks.chunkAt(1))
        } finally {
            chunks.release()
            // Free the buffer that never made it into `chunks`.
            source.single().release()
        }
    }
}
