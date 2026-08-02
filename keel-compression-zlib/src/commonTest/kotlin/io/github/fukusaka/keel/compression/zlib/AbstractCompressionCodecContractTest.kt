package io.github.fukusaka.keel.compression.zlib

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.compression.CodecStatus
import io.github.fukusaka.keel.compression.CompressionCodec
import io.github.fukusaka.keel.compression.DecoderOptions
import io.github.fukusaka.keel.compression.EncoderOptions
import io.github.fukusaka.keel.compression.FlushMode
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * SPI contract tests for [CompressionCodec] implementations.
 *
 * Subclasses bind a concrete codec by overriding [codec]; the abstract
 * tests pin:
 *
 *  1. **Name consistency** — `codec.name`, `codec.encoder.name`,
 *     `codec.decoder.name` all match (modulo case).
 *  2. **Round-trip closure** — encoding then decoding any payload yields
 *     the original bytes.
 *  3. **Empty payload round-trip** — boundary case; both sides must
 *     handle zero-length input without error.
 *  4. **`encoder` / `decoder` are stable references** — same instance
 *     returned on repeat access (allows pipeline pinning).
 */
public abstract class AbstractCompressionCodecContractTest {

    protected open val allocator: BufferAllocator = DefaultAllocator
    protected open val outputCap: Int = 256

    protected abstract val codec: CompressionCodec

    @Test
    public fun `codec name matches encoder and decoder names`() {
        assertEquals(codec.name.lowercase(), codec.encoder.name.lowercase())
        assertEquals(codec.name.lowercase(), codec.decoder.name.lowercase())
    }

    @Test
    public fun `encoder accessor returns a stable reference`() {
        val first = codec.encoder
        val second = codec.encoder
        assertTrue(first === second, "codec.encoder must return the same instance on repeat access")
    }

    @Test
    public fun `decoder accessor returns a stable reference`() {
        val first = codec.decoder
        val second = codec.decoder
        assertTrue(first === second, "codec.decoder must return the same instance on repeat access")
    }

    @Test
    public fun `round-trips empty payload`() {
        val decoded = roundTrip(ByteArray(0))
        assertContentEquals(ByteArray(0), decoded)
    }

    @Test
    public fun `round-trips one-byte payload`() {
        val payload = byteArrayOf(0x7F)
        val decoded = roundTrip(payload)
        assertContentEquals(payload, decoded)
    }

    @Test
    public fun `round-trips ASCII payload`() {
        val payload = "The quick brown fox jumps over the lazy dog.".encodeToByteArray()
        val decoded = roundTrip(payload)
        assertContentEquals(payload, decoded)
    }

    @Test
    public fun `round-trips large repeating payload`() {
        // Stays under 64 KiB so it works on the JS backend, whose sync
        // `gunzipSync` defaults to a 64 KiB output chunk size and would
        // silently truncate larger payloads. Multi-tens-of-KiB is still
        // a meaningful "large" boundary for the contract.
        val payload = "abcdefghij".repeat(5_000).encodeToByteArray()
        val decoded = roundTrip(payload)
        assertContentEquals(payload, decoded)
    }

    @Test
    public fun `round-trips high-entropy payload`() {
        // Pseudo-random bytes — exercises the "incompressible" path where
        // the encoder must emit raw blocks for deflate-family codecs.
        val payload = ByteArray(4096) { (it * 257 + 31).toByte() }
        val decoded = roundTrip(payload)
        assertContentEquals(payload, decoded)
    }

    // ---- helpers ----

    private fun roundTrip(payload: ByteArray): ByteArray {
        val encoded = encode(payload)
        return decode(encoded)
    }

    private fun encode(payload: ByteArray): ByteArray {
        val session = codec.encoder.newSession(allocator, EncoderOptions(flushMode = FlushMode.NoFlush))
        val sink = ByteCollector()
        val output = allocator.allocate(outputCap)
        val input = allocator.allocate(maxOf(payload.size, 1))
        try {
            if (payload.isNotEmpty()) {
                input.writeByteArray(payload, 0, payload.size)
            }
            var iters = 0
            while (iters < 4096) {
                when (session.update(input, output)) {
                    CodecStatus.NEED_OUTPUT -> sink.drain(output)
                    CodecStatus.NEED_INPUT -> {
                        sink.drain(output)
                        break
                    }
                    CodecStatus.FINISHED -> fail("update must not return FINISHED")
                }
                iters++
            }
            iters = 0
            while (iters < 256) {
                when (session.finish(output)) {
                    CodecStatus.NEED_OUTPUT -> sink.drain(output)
                    CodecStatus.NEED_INPUT -> sink.drain(output)
                    CodecStatus.FINISHED -> {
                        sink.drain(output)
                        break
                    }
                }
                iters++
            }
        } finally {
            output.release()
            input.release()
            session.close()
        }
        return sink.toByteArray()
    }

    private fun decode(encoded: ByteArray): ByteArray {
        val session = codec.decoder.newSession(allocator, DecoderOptions())
        val sink = ByteCollector()
        val output = allocator.allocate(outputCap)
        val input = allocator.allocate(maxOf(encoded.size, 1))
        try {
            if (encoded.isNotEmpty()) {
                input.writeByteArray(encoded, 0, encoded.size)
            }
            var iters = 0
            while (iters < 4096) {
                when (session.update(input, output)) {
                    CodecStatus.NEED_OUTPUT -> sink.drain(output)
                    CodecStatus.NEED_INPUT -> {
                        sink.drain(output)
                        break
                    }
                    CodecStatus.FINISHED -> fail("update must not return FINISHED")
                }
                iters++
            }
            iters = 0
            while (iters < 256) {
                when (session.finish(output)) {
                    CodecStatus.NEED_OUTPUT -> sink.drain(output)
                    CodecStatus.NEED_INPUT -> {
                        sink.drain(output)
                        break
                    }
                    CodecStatus.FINISHED -> {
                        sink.drain(output)
                        break
                    }
                }
                iters++
            }
        } finally {
            output.release()
            input.release()
            session.close()
        }
        return sink.toByteArray()
    }
}
