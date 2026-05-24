package io.github.fukusaka.keel.compression.zlib

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.compression.CodecStatus
import io.github.fukusaka.keel.compression.EncoderOptions
import io.github.fukusaka.keel.compression.EncoderSession
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * SPI contract tests for [EncoderSession] implementations.
 *
 * Subclasses bind a concrete encoder by overriding [newSession] (and
 * optionally [newSessionWithOptions]) and the abstract tests below pin
 * the documented [EncoderSession] / [Encoder] invariants from
 * `EncoderSession.kt` KDoc:
 *
 *  1. **Caller-provided output** — `update` / `finish` only write into
 *     the caller's [IoBuf]; the session never replaces it.
 *  2. **Status state machine** — `update` returns `NEED_OUTPUT` /
 *     `NEED_INPUT`; `finish` returns `NEED_OUTPUT` / `FINISHED`. `update`
 *     never returns `FINISHED`.
 *  3. **Input ownership** — `update` does not release the input buffer.
 *  4. **`reset`** — session is reusable for another message after reset
 *     (smoke test; deep dictionary-state contracts live in backend tests).
 *  5. **`close` idempotency** — second `close` is a no-op.
 *  6. **`close` poisons further calls** — any method after `close` throws
 *     `IllegalStateException`.
 *
 * Backend-specific guarantees (gzip header bytes, raw deflate framing,
 * etc.) live in backend test classes — this abstract class is intentionally
 * **backend-agnostic**: any conformant [Encoder] should pass every test.
 *
 * Implementors that cannot honour `IllegalStateException` semantics (e.g.
 * a stub backend that throws a different type) should override the
 * relevant test to assert their actual contract — but production backends
 * are expected to comply.
 */
public abstract class AbstractEncoderSessionContractTest {

    protected open val allocator: BufferAllocator = DefaultAllocator

    /**
     * Output buffer capacity. Small enough that any non-trivial input
     * exercises `NEED_OUTPUT` cycling.
     */
    protected open val outputCap: Int = 256

    /**
     * Construct a fresh session with default options. Subclasses bind a
     * specific backend here.
     */
    protected abstract fun newSession(): EncoderSession

    /**
     * Construct a fresh session with the given options. Default
     * implementation uses [newSession] — subclasses that support
     * non-default [EncoderOptions] (most do) should override.
     */
    protected open fun newSessionWithOptions(options: EncoderOptions): EncoderSession = newSession()

    // ---- Status state-machine ----

    @Test
    public fun `finish on empty input eventually returns FINISHED`() {
        val session = newSession()
        val output = allocator.allocate(outputCap)
        try {
            // Drive finish to completion without any update — empty
            // payload should still produce a valid finished stream
            // (gzip header + trailer; deflate empty block; etc.).
            val sink = ByteCollector()
            driveToFinished(session, output, sink, maxIterations = 64)
            assertTrue(sink.size >= 0, "finish should not throw on empty input")
        } finally {
            output.release()
            session.close()
        }
    }

    @Test
    public fun `update never returns FINISHED`() {
        val session = newSession()
        val payload = ByteArray(64) { it.toByte() }
        val input = allocator.allocate(payload.size).apply { writeByteArray(payload, 0, payload.size) }
        val output = allocator.allocate(outputCap)
        try {
            // Drive update until NEED_INPUT (all input consumed). Per SPI:
            // update never returns FINISHED — only finish does.
            var iters = 0
            while (iters < 64) {
                when (val st = session.update(input, output)) {
                    CodecStatus.NEED_OUTPUT -> output.clear()
                    CodecStatus.NEED_INPUT -> return
                    CodecStatus.FINISHED -> fail("update must not return FINISHED, got $st")
                }
                iters++
            }
            fail("update did not converge in 64 iterations")
        } finally {
            output.release()
            input.release()
            session.close()
        }
    }

    @Test
    public fun `single-byte payload round-trips through finish`() {
        val session = newSession()
        val payload = byteArrayOf(0x42)
        val input = allocator.allocate(1).apply { writeByteArray(payload, 0, 1) }
        val output = allocator.allocate(outputCap)
        val sink = ByteCollector()
        try {
            // Consume the byte.
            driveUpdateToNeedInput(session, input, output, sink, maxIterations = 16)
            // Finalize.
            driveToFinished(session, output, sink, maxIterations = 32)
            assertTrue(sink.size > 0, "encoded output should be non-empty for 1-byte payload")
        } finally {
            output.release()
            input.release()
            session.close()
        }
    }

    @Test
    public fun `large payload cycles NEED_OUTPUT across update or finish`() {
        // Pseudo-random bytes resist compression so the encoder emits
        // roughly as many bytes as the input — far more than [outputCap].
        // NEED_OUTPUT MUST fire at least once across the update + finish
        // sequence (which side depends on the backend's buffering: some
        // emit during update, others buffer until finish under FlushMode
        // NoFlush). What the SPI promises is the cycling itself.
        val payload = ByteArray(8192) { (it * 31 + 7).toByte() }
        val session = newSession()
        val input = allocator.allocate(payload.size).apply { writeByteArray(payload, 0, payload.size) }
        val output = allocator.allocate(outputCap)
        val sink = ByteCollector()
        var needOutputCount = 0
        try {
            var iters = 0
            while (iters < 1024) {
                when (session.update(input, output)) {
                    CodecStatus.NEED_OUTPUT -> {
                        needOutputCount++
                        sink.drain(output)
                    }
                    CodecStatus.NEED_INPUT -> break
                    CodecStatus.FINISHED -> fail("update must not return FINISHED")
                }
                iters++
            }
            sink.drain(output)
            iters = 0
            while (iters < 256) {
                when (session.finish(output)) {
                    CodecStatus.NEED_OUTPUT -> {
                        needOutputCount++
                        sink.drain(output)
                    }
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
            // For an 8 KiB largely-incompressible payload, encoded size
            // overflows the 256-byte output capacity many times over —
            // the SPI MUST surface that via NEED_OUTPUT (else the caller
            // could never drain mid-stream).
            assertTrue(
                needOutputCount > 0,
                "expected NEED_OUTPUT to fire at least once for 8 KiB payload (sink.size=${sink.size})",
            )
        } finally {
            output.release()
            input.release()
            session.close()
        }
    }

    // ---- Input ownership ----

    @Test
    public fun `update does not release input buffer`() {
        val session = newSession()
        val payload = ByteArray(128) { it.toByte() }
        val input = allocator.allocate(payload.size).apply { writeByteArray(payload, 0, payload.size) }
        val output = allocator.allocate(outputCap)
        try {
            session.update(input, output)
            // The session must not have released the input — the caller
            // still owns it, and a follow-up retain must not be needed
            // before the next update. The simplest invariant we can pin
            // without a refcount probe is "release after one update
            // returns true exactly once". `release()` of a not-yet-fully
            // released IoBuf returns false if refcount > 0, true if it
            // hit zero. Since we hold a single refcount, post-update
            // release must return true.
            assertTrue(input.release(), "input release after update must succeed (session must not double-retain)")
        } finally {
            output.release()
            session.close()
        }
    }

    // ---- reset semantics ----

    @Test
    public fun `reset allows reusing the session for a second message`() {
        val session = newSession()
        val payload = "abcdef".encodeToByteArray()
        val output = allocator.allocate(outputCap)
        try {
            // Message 1.
            encodeOnce(session, payload, output)
            session.reset()
            // Message 2 — must succeed without throwing.
            encodeOnce(session, payload, output)
        } finally {
            output.release()
            session.close()
        }
    }

    @Test
    public fun `reset with contextTakeover=false fully resets state across multiple messages`() {
        val session = newSessionWithOptions(EncoderOptions(contextTakeover = false))
        val payload = "the quick brown fox".encodeToByteArray()
        val output = allocator.allocate(outputCap)
        try {
            // Three messages back-to-back — previous best-effort impls
            // (left `Deflater.end()`d state in place) would fail on the
            // second or third encode.
            repeat(3) {
                encodeOnce(session, payload, output)
                session.reset()
            }
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
        // Second close must not throw.
        session.close()
    }

    @Test
    public fun `update after close throws IllegalStateException`() {
        val session = newSession()
        session.close()
        val output = allocator.allocate(outputCap)
        val input = allocator.allocate(8)
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

    // ---- helpers ----

    private fun encodeOnce(session: EncoderSession, payload: ByteArray, output: IoBuf) {
        val input = allocator.allocate(payload.size).apply { writeByteArray(payload, 0, payload.size) }
        val sink = ByteCollector()
        try {
            driveUpdateToNeedInput(session, input, output, sink, maxIterations = 64)
            driveToFinished(session, output, sink, maxIterations = 64)
            assertTrue(sink.size > 0, "encoded output should be non-empty for non-empty input")
        } finally {
            input.release()
        }
    }

    private fun driveUpdateToNeedInput(
        session: EncoderSession,
        input: IoBuf,
        output: IoBuf,
        sink: ByteCollector,
        maxIterations: Int,
    ) {
        var iters = 0
        while (iters < maxIterations) {
            when (session.update(input, output)) {
                CodecStatus.NEED_OUTPUT -> sink.drain(output)
                CodecStatus.NEED_INPUT -> {
                    sink.drain(output)
                    return
                }
                CodecStatus.FINISHED -> fail("update must not return FINISHED")
            }
            iters++
        }
        fail("update did not converge in $maxIterations iterations")
    }

    private fun driveToFinished(
        session: EncoderSession,
        output: IoBuf,
        sink: ByteCollector,
        maxIterations: Int,
    ) {
        var iters = 0
        while (iters < maxIterations) {
            when (session.finish(output)) {
                CodecStatus.NEED_OUTPUT -> sink.drain(output)
                CodecStatus.NEED_INPUT -> sink.drain(output)
                CodecStatus.FINISHED -> {
                    sink.drain(output)
                    return
                }
            }
            iters++
        }
        fail("finish did not converge in $maxIterations iterations")
    }
}

/**
 * Simple collector that drains an [IoBuf] into an in-memory buffer for
 * size / content inspection. Used by contract tests.
 */
internal class ByteCollector {
    private var collected: ByteArray = ByteArray(0)

    val size: Int get() = collected.size

    fun drain(buf: IoBuf) {
        val n = buf.readableBytes
        if (n == 0) return
        val tmp = ByteArray(n)
        buf.readByteArray(tmp, 0, n)
        val merged = ByteArray(collected.size + n)
        collected.copyInto(merged, 0)
        tmp.copyInto(merged, collected.size)
        collected = merged
        buf.clear()
    }

    fun toByteArray(): ByteArray = collected.copyOf()
}
