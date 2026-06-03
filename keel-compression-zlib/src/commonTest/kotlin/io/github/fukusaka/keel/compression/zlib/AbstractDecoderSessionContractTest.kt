package io.github.fukusaka.keel.compression.zlib

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.compression.CodecStatus
import io.github.fukusaka.keel.compression.DecoderOptions
import io.github.fukusaka.keel.compression.DecoderSession
import io.github.fukusaka.keel.compression.DecompressionException
import io.github.fukusaka.keel.compression.DecompressionLimitException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * SPI contract tests for [DecoderSession] implementations.
 *
 * Subclasses bind a concrete decoder by overriding [newSession] /
 * [newSessionWithOptions] / [encodeForDecode]. The abstract tests pin
 * the [DecoderSession] / [Decoder] invariants documented in
 * `EncoderSession.kt` KDoc:
 *
 *  1. **State machine** symmetric to encoder — `update` returns
 *     `NEED_OUTPUT` / `NEED_INPUT` only.
 *  2. **Malformed input** throws [DecompressionException].
 *  3. **Zip-bomb defence** — `maxOutputSize` enforcement triggers
 *     [DecompressionLimitException] **before** more output is produced
 *     (per-chunk reject, not after-payload).
 *  4. **`reset`** allows reusing the session for the next message.
 *  5. **`close` idempotency** + post-close `IllegalStateException`.
 *
 * Concrete encoded payloads are produced by [encodeForDecode] — the
 * decoder under test must be able to round-trip whatever the matching
 * encoder produces. This couples each backend's encoder + decoder test
 * pair (which is by design: the SPI contract states they share an
 * algorithm namespace).
 */
public abstract class AbstractDecoderSessionContractTest {

    protected open val allocator: BufferAllocator = DefaultAllocator
    protected open val outputCap: Int = 256

    /** Construct a fresh decoder session with default options. */
    protected abstract fun newSession(): DecoderSession

    /** Construct a fresh decoder session with the given options. */
    protected open fun newSessionWithOptions(options: DecoderOptions): DecoderSession = newSession()

    /**
     * Encode [payload] using this decoder's matching encoder so that
     * [newSession] can decode it. Implementations typically use the
     * sibling `Encoder` from the same backend.
     */
    protected abstract fun encodeForDecode(payload: ByteArray): ByteArray

    // ---- Round-trip / state-machine ----

    @Test
    public fun `update never returns FINISHED`() {
        val payload = "Hello, decoder contract".encodeToByteArray()
        val encoded = encodeForDecode(payload)
        val session = newSession()
        val input = allocator.allocate(encoded.size).apply { writeByteArray(encoded, 0, encoded.size) }
        val output = allocator.allocate(outputCap)
        try {
            var iters = 0
            while (iters < 256) {
                when (val st = session.update(input, output)) {
                    CodecStatus.NEED_OUTPUT -> output.clear()
                    CodecStatus.NEED_INPUT -> return
                    CodecStatus.FINISHED -> fail("update must not return FINISHED, got $st")
                }
                iters++
            }
            fail("update did not converge in 256 iterations")
        } finally {
            output.release()
            input.release()
            session.close()
        }
    }

    @Test
    public fun `decode round-trips a non-trivial payload`() {
        val payload = "Round-trip ${"x".repeat(2048)}".encodeToByteArray()
        val encoded = encodeForDecode(payload)
        val decoded = runDecode(encoded)
        assertContentEquals(payload, decoded)
    }

    @Test
    public fun `decode emits multiple chunks for high-ratio payload`() {
        // 50 KiB of repeated bytes compresses to a few hundred bytes;
        // decoding back should cycle NEED_OUTPUT many times.
        val payload = "x".repeat(50_000).encodeToByteArray()
        val encoded = encodeForDecode(payload)
        val (decoded, chunkCount) = runDecodeWithChunkCount(encoded)
        assertContentEquals(payload, decoded)
        assertTrue(
            chunkCount >= 50_000 / outputCap - 5,
            "expected many bounded chunks (decoded ${decoded.size} bytes in $chunkCount chunks of $outputCap)",
        )
    }

    // ---- update does not release input ----

    @Test
    public fun `update does not release input buffer`() {
        val payload = "input ownership".encodeToByteArray()
        val encoded = encodeForDecode(payload)
        val session = newSession()
        val input = allocator.allocate(encoded.size).apply { writeByteArray(encoded, 0, encoded.size) }
        val output = allocator.allocate(outputCap)
        try {
            session.update(input, output)
            // Caller still owns the input — final release must return
            // true (not "already released").
            assertTrue(input.release(), "input release after update must succeed")
        } finally {
            output.release()
            session.close()
        }
    }

    // ---- Malformed input ----

    @Test
    public fun `malformed input throws DecompressionException`() {
        // Random non-compressed bytes — header check should fail for any
        // wrap format requiring a header (gzip, zlib). For Raw deflate
        // backends a header-less malformed stream may also throw, but
        // the contract only requires "throws DecompressionException for
        // malformed input"; backends that auto-detect to identity-pass
        // raw bytes can override this test.
        val garbage = ByteArray(64) { (it * 173 + 91).toByte() }
        val session = newSession()
        val input = allocator.allocate(garbage.size).apply { writeByteArray(garbage, 0, garbage.size) }
        val output = allocator.allocate(outputCap)
        try {
            val ex = assertFailsWith<DecompressionException> {
                // Drive update / finish — malformed detection may occur
                // on the first update or only at finish (depending on
                // header position). Either path must throw.
                var iters = 0
                while (iters < 64) {
                    val st = session.update(input, output)
                    if (st == CodecStatus.NEED_INPUT) break
                    output.clear()
                    iters++
                }
                session.finish(output)
            }
            assertNotNull(ex.message)
        } finally {
            output.release()
            input.release()
            // Session may be in an error state after a malformed throw;
            // close must still succeed.
            session.close()
        }
    }

    // ---- Zip-bomb defence ----

    @Test
    public fun `maxOutputSize eventually triggers DecompressionLimitException`() {
        // 10 KB decoded payload, decoder capped at 100 bytes — the
        // session MUST throw DecompressionLimitException. The SPI ideally
        // wants the throw to fire from `update` per-chunk (defending
        // against zip-bomb DoS before more output is produced), but some
        // backends (e.g. JS Node sync zlib) buffer input and only check
        // at finish. Either path is acceptable for this baseline test;
        // backends that promise per-chunk early-throw can pin that more
        // specifically in their own test classes.
        val payload = "x".repeat(10_000).encodeToByteArray()
        val encoded = encodeForDecode(payload)
        val session = newSessionWithOptions(DecoderOptions(maxOutputSize = 100L))
        val input = allocator.allocate(encoded.size).apply { writeByteArray(encoded, 0, encoded.size) }
        val output = allocator.allocate(outputCap)
        try {
            assertFailsWith<DecompressionLimitException> {
                var iters = 0
                while (iters < 4096) {
                    val st = session.update(input, output)
                    if (st == CodecStatus.NEED_INPUT) break
                    output.clear()
                    iters++
                }
                iters = 0
                while (iters < 256) {
                    val st = session.finish(output)
                    if (st == CodecStatus.FINISHED || st == CodecStatus.NEED_INPUT) break
                    output.clear()
                    iters++
                }
            }
            // Inheritance contract (DecompressionLimitException extends
            // DecompressionException) is pinned in
            // [DecompressionExceptionTest] within keel-compression.
        } finally {
            output.release()
            input.release()
            session.close()
        }
    }

    // ---- reset semantics ----

    @Test
    public fun `reset allows decoding a second message`() {
        val payload = "second-message-test".encodeToByteArray()
        val encoded = encodeForDecode(payload)
        val session = newSession()
        val output = allocator.allocate(outputCap)
        try {
            // Message 1.
            decodeWith(session, encoded, output)
            session.reset()
            // Message 2.
            decodeWith(session, encoded, output)
        } finally {
            output.release()
            session.close()
        }
    }

    // ---- close lifecycle ----

    @Test
    public fun `close is idempotent`() {
        val session = newSession()
        session.close()
        session.close() // must not throw
    }

    @Test
    public fun `update after close throws IllegalStateException`() {
        val session = newSession()
        session.close()
        val input = allocator.allocate(8)
        val output = allocator.allocate(outputCap)
        try {
            assertFailsWith<IllegalStateException> {
                session.update(input, output)
            }
        } finally {
            output.release()
            input.release()
        }
    }

    @Test
    public fun `finish after close throws IllegalStateException`() {
        val session = newSession()
        session.close()
        val output = allocator.allocate(outputCap)
        try {
            assertFailsWith<IllegalStateException> {
                session.finish(output)
            }
        } finally {
            output.release()
        }
    }

    @Test
    public fun `reset after close throws IllegalStateException`() {
        val session = newSession()
        session.close()
        assertFailsWith<IllegalStateException> {
            session.reset()
        }
    }

    @Test
    public fun `update after finish throws IllegalStateException`() {
        // Symmetric to the encoder's `update after finish` invariant. After
        // FINISHED the decoder stream is no longer open and must reject
        // further `update` calls (caller error: forgot `reset` between
        // messages).
        val payload = "Hello, decoder finish-then-update".encodeToByteArray()
        val encoded = encodeForDecode(payload)
        val session = newSession()
        val input = allocator.allocate(encoded.size).apply { writeByteArray(encoded, 0, encoded.size) }
        val output = allocator.allocate(outputCap)
        try {
            driveToFinished(session, input, output)
            input.clear()
            output.clear()
            assertFailsWith<IllegalStateException> {
                session.update(input, output)
            }
        } finally {
            output.release()
            input.release()
            session.close()
        }
    }

    @Test
    public fun `flush after finish throws IllegalStateException`() {
        // Same invariant as `update after finish` — flush() must also reject
        // a finished session (no Z_SYNC_FLUSH boundary exists after the
        // stream has terminated).
        val payload = "Hello, decoder finish-then-flush".encodeToByteArray()
        val encoded = encodeForDecode(payload)
        val session = newSession()
        val input = allocator.allocate(encoded.size).apply { writeByteArray(encoded, 0, encoded.size) }
        val output = allocator.allocate(outputCap)
        try {
            driveToFinished(session, input, output)
            output.clear()
            assertFailsWith<IllegalStateException> {
                session.flush(output)
            }
        } finally {
            output.release()
            input.release()
            session.close()
        }
    }

    private fun driveToFinished(session: DecoderSession, input: IoBuf, output: IoBuf) {
        var iters = 0
        while (iters < 256) {
            when (session.update(input, output)) {
                CodecStatus.NEED_OUTPUT -> output.clear()
                CodecStatus.NEED_INPUT -> break
                CodecStatus.FINISHED -> fail("update must not return FINISHED")
            }
            iters++
        }
        iters = 0
        while (iters < 256) {
            when (session.finish(output)) {
                CodecStatus.NEED_OUTPUT, CodecStatus.NEED_INPUT -> output.clear()
                CodecStatus.FINISHED -> return
            }
            iters++
        }
        fail("finish did not converge in 256 iterations")
    }

    // ---- helpers ----

    private fun runDecode(encoded: ByteArray): ByteArray = runDecodeWithChunkCount(encoded).first

    private fun runDecodeWithChunkCount(encoded: ByteArray): Pair<ByteArray, Int> {
        val session = newSession()
        val input = allocator.allocate(encoded.size).apply { writeByteArray(encoded, 0, encoded.size) }
        val output = allocator.allocate(outputCap)
        val sink = ByteCollector()
        var chunkCount = 0
        try {
            var iters = 0
            while (iters < 4096) {
                when (session.update(input, output)) {
                    CodecStatus.NEED_OUTPUT -> {
                        chunkCount++
                        sink.drain(output)
                    }
                    CodecStatus.NEED_INPUT -> break
                    CodecStatus.FINISHED -> fail("update must not return FINISHED")
                }
                iters++
            }
            if (output.readableBytes > 0) {
                chunkCount++
                sink.drain(output)
            }
            iters = 0
            while (iters < 256) {
                when (session.finish(output)) {
                    CodecStatus.NEED_OUTPUT -> {
                        chunkCount++
                        sink.drain(output)
                    }
                    CodecStatus.NEED_INPUT -> {
                        if (output.readableBytes > 0) {
                            sink.drain(output)
                        }
                        break
                    }
                    CodecStatus.FINISHED -> {
                        if (output.readableBytes > 0) {
                            sink.drain(output)
                        }
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
        return sink.toByteArray() to chunkCount
    }

    private fun decodeWith(session: DecoderSession, encoded: ByteArray, output: IoBuf) {
        val input = allocator.allocate(encoded.size).apply { writeByteArray(encoded, 0, encoded.size) }
        val sink = ByteCollector()
        try {
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
            input.release()
        }
    }
}
