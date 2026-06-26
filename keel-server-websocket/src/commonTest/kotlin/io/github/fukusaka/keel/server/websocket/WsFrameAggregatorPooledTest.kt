package io.github.fukusaka.keel.server.websocket

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.IoBufChunks
import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.codec.websocket.WsFrame
import io.github.fukusaka.keel.codec.websocket.WsOpcode
import io.github.fukusaka.keel.compression.zlib.DeflateCodec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Seam tests for [WsFrameAggregator]'s pooled-payload path — frames carrying
 * a [WsFrame.inboundPayload] (the decoder's zero-copy fast path).
 *
 * A [TrackingAllocator] backs every pooled payload so each test asserts that
 * alloc / release stays balanced: an uncompressed `BINARY` message is handed
 * off as [WsMessage.BinaryChunks] (the test releases the chunks), while every
 * other outcome — flattened `Text` / `Binary`, a protocol error, or a
 * mid-message teardown — must release the pooled buffers itself.
 *
 * Frames are fed synchronously (no I/O, no coroutines), so no timeout.
 */
class WsFrameAggregatorPooledTest {

    private val tracker = TrackingAllocator(DefaultAllocator)

    /** Allocates a pooled [IoBuf] holding [bytes] (the decoder's fast-path output shape). */
    private fun pooled(bytes: ByteArray): IoBuf =
        tracker.allocate(bytes.size).apply { writeByteArray(bytes, 0, bytes.size) }

    /** A data frame carrying [bytes] as a pooled [WsFrame.inboundPayload]. */
    private fun pooledFrame(opcode: WsOpcode, bytes: ByteArray, fin: Boolean = true, rsv1: Boolean = false): WsFrame =
        WsFrame(fin = fin, rsv1 = rsv1, opcode = opcode, inboundPayload = pooled(bytes))

    /** Reads every chunk's bytes (for assertion) then releases the [IoBufChunks]. */
    private fun drainAndRelease(chunks: IoBufChunks): ByteArray {
        val out = ByteArray(chunks.totalSize)
        var offset = 0
        chunks.forEach { c ->
            val n = c.readableBytes
            c.readByteArray(out, offset, n)
            offset += n
        }
        chunks.release()
        return out
    }

    private fun assertBalanced() {
        assertEquals(
            0,
            tracker.outstandingCount,
            "IoBuf leak (alloc=${tracker.allocateCount} release=${tracker.releaseCount})",
        )
    }

    private fun deflateEngine(): WsPermessageDeflate =
        WsPermessageDeflate(
            allocator = DefaultAllocator,
            codec = DeflateCodec,
            options = WsDeflateOptions(contextTakeover = false, threshold = 0),
            serverMaxWindowBits = null,
            clientMaxWindowBits = null,
        )

    // --- BinaryChunks delivery (the zero-copy win) ---

    @Test
    fun `a single-frame uncompressed binary pooled payload is delivered as BinaryChunks`() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val completed = assertIs<WsAggregateResult.Completed>(
            WsFrameAggregator().feed(pooledFrame(WsOpcode.BINARY, bytes)),
        )
        val message = assertIs<WsMessage.BinaryChunks>(completed.message)
        assertEquals(1, message.chunks.chunkCount)
        assertEquals(bytes.size, message.chunks.totalSize)
        assertContentEquals(bytes, drainAndRelease(message.chunks))
        assertBalanced()
    }

    @Test
    fun `a fragmented all-pooled binary message is delivered as multi-chunk BinaryChunks`() {
        val aggregator = WsFrameAggregator()
        val a = byteArrayOf(1, 2, 3)
        val b = byteArrayOf(4, 5)
        val c = byteArrayOf(6, 7, 8, 9)
        assertIs<WsAggregateResult.Incomplete>(aggregator.feed(pooledFrame(WsOpcode.BINARY, a, fin = false)))
        assertIs<WsAggregateResult.Incomplete>(aggregator.feed(pooledFrame(WsOpcode.CONTINUATION, b, fin = false)))
        val completed = assertIs<WsAggregateResult.Completed>(
            aggregator.feed(pooledFrame(WsOpcode.CONTINUATION, c, fin = true)),
        )
        val message = assertIs<WsMessage.BinaryChunks>(completed.message)
        assertEquals(3, message.chunks.chunkCount)
        assertContentEquals(a + b + c, drainAndRelease(message.chunks))
        assertBalanced()
    }

    // --- flatten paths (pooled released by the aggregator) ---

    @Test
    fun `a single-frame pooled text payload is flattened and validated as Text`() {
        val text = "hello pooled text"
        val completed = assertIs<WsAggregateResult.Completed>(
            WsFrameAggregator().feed(pooledFrame(WsOpcode.TEXT, text.encodeToByteArray())),
        )
        assertEquals(WsMessage.Text(text), completed.message)
        // The aggregator released the pooled payload during the flatten.
        assertBalanced()
    }

    @Test
    fun `a binary message mixing a pooled and a heap fragment is flattened to Binary`() {
        val aggregator = WsFrameAggregator()
        val pooledPart = byteArrayOf(1, 2, 3)
        val heapPart = byteArrayOf(4, 5, 6, 7)
        assertIs<WsAggregateResult.Incomplete>(aggregator.feed(pooledFrame(WsOpcode.BINARY, pooledPart, fin = false)))
        // A slow-path (straddle) continuation arrives as a heap ByteArray frame.
        val completed = assertIs<WsAggregateResult.Completed>(
            aggregator.feed(WsFrame.continuation(heapPart, fin = true)),
        )
        val message = assertIs<WsMessage.Binary>(completed.message)
        assertContentEquals(pooledPart + heapPart, message.bytes)
        assertBalanced()
    }

    @Test
    fun `a single-frame pooled empty-after-flatten text yields the expected string`() {
        // A heap empty-text frame still produces Text(""), unchanged; pinned
        // here next to the pooled tests so the empty boundary stays covered.
        val completed = assertIs<WsAggregateResult.Completed>(WsFrameAggregator().feed(WsFrame.text("")))
        assertEquals(WsMessage.Text(""), completed.message)
        assertBalanced()
    }

    @Test
    fun `a compressed pooled binary message is inflated to Binary`() {
        val engine = deflateEngine()
        try {
            val original = ByteArray(2048) { (it and 0x3F).toByte() }
            val wire = wireBytes(engine.compress(original))
            val aggregator = WsFrameAggregator(WsMessageInflater { engine.decompress(it) })
            val completed = assertIs<WsAggregateResult.Completed>(
                aggregator.feed(pooledFrame(WsOpcode.BINARY, wire, rsv1 = true)),
            )
            val message = assertIs<WsMessage.Binary>(completed.message)
            assertContentEquals(original, message.bytes)
            assertBalanced()
        } finally {
            engine.close()
        }
    }

    // --- error / teardown paths free the pooled buffers ---

    @Test
    fun `an orphan continuation carrying a pooled payload releases it`() {
        val result = WsFrameAggregator().feed(pooledFrame(WsOpcode.CONTINUATION, byteArrayOf(9, 9, 9), fin = true))
        val error = assertIs<WsAggregateResult.ProtocolError>(result)
        assertEquals(WsFrameAggregator.PROTOCOL_ERROR_CODE, error.closeCode)
        assertBalanced()
    }

    @Test
    fun `an interleaved pooled frame releases both it and the in-progress fragment`() {
        val aggregator = WsFrameAggregator()
        assertIs<WsAggregateResult.Incomplete>(
            aggregator.feed(pooledFrame(WsOpcode.BINARY, byteArrayOf(1, 2, 3), fin = false)),
        )
        // A new TEXT before the binary message's fin is an interleave error;
        // the in-progress pooled fragment AND the rejected pooled frame must
        // both be freed (exactly once each).
        val result = aggregator.feed(pooledFrame(WsOpcode.TEXT, byteArrayOf(4, 5)))
        val error = assertIs<WsAggregateResult.ProtocolError>(result)
        assertEquals(WsFrameAggregator.PROTOCOL_ERROR_CODE, error.closeCode)
        assertBalanced()
    }

    @Test
    fun `release frees the pooled fragments of a message in progress`() {
        val aggregator = WsFrameAggregator()
        assertIs<WsAggregateResult.Incomplete>(
            aggregator.feed(pooledFrame(WsOpcode.BINARY, byteArrayOf(1, 2, 3, 4), fin = false)),
        )
        assertIs<WsAggregateResult.Incomplete>(
            aggregator.feed(pooledFrame(WsOpcode.CONTINUATION, byteArrayOf(5, 6), fin = false)),
        )
        // Connection drops mid-message: teardown must free both pooled fragments.
        aggregator.release()
        assertBalanced()
    }

    @Test
    fun `the aggregator still delivers heap binary frames as Binary`() {
        // Regression guard: a frame with no inboundPayload (slow path) keeps
        // the pre-existing Binary delivery, not BinaryChunks.
        val bytes = byteArrayOf(10, 20, 30)
        val completed = assertIs<WsAggregateResult.Completed>(WsFrameAggregator().feed(WsFrame.binary(bytes)))
        val message = assertIs<WsMessage.Binary>(completed.message)
        assertTrue(bytes.contentEquals(message.bytes))
        assertBalanced()
    }
}
