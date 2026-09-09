package io.github.fukusaka.keel.benchmark

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
 * twice fell behind the productized path it claims to mirror (once on
 * the `finish()` / `flush()` boundary, once on the accumulation shape).
 * A bench engine that decodes differently from production measures a
 * path production does not run, and the numbers land in the result
 * tables as though they did. These tests pin the transform itself so a
 * future divergence fails here rather than in a sweep nobody re-reads.
 */
class PipelineHttpWsDeflateTest {

    @Test
    fun `round trip returns the original bytes`() {
        PipelineHttpWsDeflate().use { deflate ->
            val payload = "hello permessage-deflate".encodeToByteArray()
            assertContentEquals(payload, deflate.decompress(deflate.compress(payload)))
        }
    }

    @Test
    fun `round trip holds for an empty payload`() {
        PipelineHttpWsDeflate().use { deflate ->
            assertContentEquals(ByteArray(0), deflate.decompress(deflate.compress(ByteArray(0))))
        }
    }

    @Test
    fun `round trip holds for a single byte`() {
        PipelineHttpWsDeflate().use { deflate ->
            val payload = byteArrayOf(0x42)
            assertContentEquals(payload, deflate.decompress(deflate.compress(payload)))
        }
    }

    @Test
    fun `round trip holds across the accumulator chunk boundary`() {
        // The accumulator seals a chunk every 8 KiB, so a payload either
        // side of that boundary exercises the multi-chunk commit path that
        // a single-chunk message never reaches.
        PipelineHttpWsDeflate().use { deflate ->
            for (size in listOf(8191, 8192, 8193, 40_000)) {
                val payload = Random(size).nextBytes(size)
                assertContentEquals(payload, deflate.decompress(deflate.compress(payload)), "size=$size")
            }
        }
    }

    @Test
    fun `round trip holds for highly compressible input`() {
        // Incompressible input (above) makes deflate emit stored blocks;
        // this one makes it emit far less output than input, which is the
        // case where a trimmed or over-trimmed sync tail shows up.
        PipelineHttpWsDeflate().use { deflate ->
            val payload = ByteArray(50_000) { 'a'.code.toByte() }
            val compressed = deflate.compress(payload)
            assertTrue(compressed.size < payload.size / 10, "expected real compression, got ${compressed.size}")
            assertContentEquals(payload, deflate.decompress(compressed))
        }
    }

    @Test
    fun `compressed output does not carry the sync flush tail`() {
        // RFC 7692 §7.2.1: the 00 00 FF FF that Z_SYNC_FLUSH emits is
        // removed before the frame goes on the wire, and the peer appends
        // it back. Leaving it on is a protocol error the round-trip test
        // cannot see, because this class re-appends it on the way in.
        PipelineHttpWsDeflate().use { deflate ->
            val compressed = deflate.compress(ByteArray(1000) { (it % 7).toByte() })
            assertTrue(compressed.size >= 4, "compressed payload too short to inspect")
            val tail = compressed.copyOfRange(compressed.size - 4, compressed.size)
            assertTrue(
                !tail.contentEquals(byteArrayOf(0x00, 0x00, 0xFF.toByte(), 0xFF.toByte())),
                "sync-flush tail was left on the wire",
            )
        }
    }

    @Test
    fun `successive messages round trip on one instance`() {
        // contextTakeover = false: every message resets the session, so a
        // reset that stopped happening would corrupt the second message
        // rather than the first.
        PipelineHttpWsDeflate().use { deflate ->
            repeat(5) { i ->
                val payload = "message $i ${"x".repeat(i * 100)}".encodeToByteArray()
                assertContentEquals(payload, deflate.decompress(deflate.compress(payload)), "message $i")
            }
        }
    }

    @Test
    fun `decompress accepts what compress produced for every size class`() {
        PipelineHttpWsDeflate().use { deflate ->
            for (size in listOf(0, 1, 100, 8192, 20_000)) {
                val payload = ByteArray(size) { (it % 251).toByte() }
                val out = deflate.decompress(deflate.compress(payload))
                assertEquals(size, out.size, "size=$size")
                assertContentEquals(payload, out, "size=$size")
            }
        }
    }
}
