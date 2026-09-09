package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.buf.IoBufChunks
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round-trip and wire-shape invariants for [PipelineHttpWsDeflate].
 *
 * This class is a bench-only copy of the RFC 7692 transform that
 * keel-server-websocket applies, and it had no test — which is how it
 * twice fell behind the path it claims to mirror: once on the
 * `finish()` / `flush()` boundary, which surfaced as a benchmark
 * timeout, and once on the accumulation shape, which surfaced as
 * nothing at all because the numbers still arrived.
 *
 * What these tests pin, precisely: the round trip, the absence of the
 * sync-flush tail on the wire, and the per-message session reset. They
 * do **not** pin the compressed bytes themselves against production's
 * — the two run the same codec with the same options, but nothing here
 * would catch a divergence that both sides of this class agree on.
 */
class PipelineHttpWsDeflateTest {

    @Test
    fun `round trip returns the original bytes`() {
        PipelineHttpWsDeflate().use { deflate ->
            val payload = "hello permessage-deflate".encodeToByteArray()
            assertContentEquals(payload, deflate.roundTrip(payload))
        }
    }

    @Test
    fun `round trip holds for an empty payload`() {
        PipelineHttpWsDeflate().use { deflate ->
            assertContentEquals(ByteArray(0), deflate.roundTrip(ByteArray(0)))
        }
    }

    @Test
    fun `round trip holds for a single byte`() {
        PipelineHttpWsDeflate().use { deflate ->
            assertContentEquals(byteArrayOf(0x42), deflate.roundTrip(byteArrayOf(0x42)))
        }
    }

    @Test
    fun `round trip holds across the accumulator chunk boundary`() {
        // Incompressible input either side of the 8 KiB chunk size, so the
        // encoder commits more than one chunk and the decoder is fed one.
        // Note this does not reach trimTail's "release a fully consumed
        // trailing chunk" branch: the sync-flush marker never lands in a
        // chunk of its own for these sizes.
        PipelineHttpWsDeflate().use { deflate ->
            for (size in listOf(8191, 8192, 8193, 40_000)) {
                val payload = Random(size).nextBytes(size)
                assertContentEquals(payload, deflate.roundTrip(payload), "size=$size")
            }
        }
    }

    @Test
    fun `round trip holds for highly compressible input`() {
        PipelineHttpWsDeflate().use { deflate ->
            val payload = ByteArray(50_000) { 'a'.code.toByte() }
            val compressed = deflate.compressToChunks(payload).drain()
            assertTrue(compressed.size < payload.size / 10, "expected real compression, got ${compressed.size}")
            assertContentEquals(payload, deflate.decompress(compressed))
        }
    }

    @Test
    fun `compressed output does not carry the sync flush tail`() {
        // RFC 7692 §7.2.1: the 00 00 FF FF that Z_SYNC_FLUSH emits is removed
        // before the frame goes on the wire, and the peer appends it back.
        // Leaving it on is a protocol error the round trip cannot see, because
        // this class re-appends it on the way in.
        PipelineHttpWsDeflate().use { deflate ->
            val compressed = deflate.compressToChunks(ByteArray(1000) { (it % 7).toByte() }).drain()
            val tail = compressed.takeLast(SYNC_TAIL.size).toByteArray()
            assertTrue(!tail.contentEquals(SYNC_TAIL), "sync-flush tail was left on the wire")
        }
    }

    @Test
    fun `each message compresses independently of the ones before it`() {
        // contextTakeover = false is advertised to the peer, which then drops
        // its LZ77 window after every message. So the same payload must encode
        // to the same bytes however many messages preceded it — a session that
        // stopped being reset would emit back-references into a window the peer
        // no longer holds, and the peer, not this class, would be the one that
        // could not inflate. A round trip on one instance cannot see that:
        // encoder and decoder drift together.
        PipelineHttpWsDeflate().use { deflate ->
            val payload = ByteArray(4000) { (it % 13).toByte() }
            val first = deflate.compressToChunks(payload).drain()
            repeat(4) { deflate.compressToChunks(ByteArray(2000) { i -> (i % 29).toByte() }).drain() }
            val later = deflate.compressToChunks(payload).drain()
            assertContentEquals(first, later, "encoder carried state across messages")
        }
    }

    @Test
    fun `successive messages round trip on one instance`() {
        PipelineHttpWsDeflate().use { deflate ->
            repeat(5) { i ->
                val payload = "message $i ${"x".repeat(i * 100)}".encodeToByteArray()
                assertContentEquals(payload, deflate.roundTrip(payload), "message $i")
            }
        }
    }

    @Test
    fun `decompress accepts what compress produced for every size class`() {
        PipelineHttpWsDeflate().use { deflate ->
            for (size in listOf(0, 1, 100, 8192, 20_000)) {
                val payload = ByteArray(size) { (it % 251).toByte() }
                val out = deflate.roundTrip(payload)
                assertEquals(size, out.size, "size=$size")
                assertContentEquals(payload, out, "size=$size")
            }
        }
    }

    private fun PipelineHttpWsDeflate.roundTrip(payload: ByteArray): ByteArray =
        decompress(compressToChunks(payload).drain())

    /** Flattens the chunks into a `ByteArray` and releases them. Test-only. */
    private fun IoBufChunks.drain(): ByteArray {
        val out = ByteArray(totalSize)
        var offset = 0
        for (i in 0 until chunkCount) {
            val chunk = chunkAt(i)
            val n = chunk.readableBytes
            chunk.readByteArray(out, offset, n)
            offset += n
        }
        release()
        return out
    }

    private companion object {
        val SYNC_TAIL = byteArrayOf(0x00, 0x00, 0xFF.toByte(), 0xFF.toByte())
    }
}
