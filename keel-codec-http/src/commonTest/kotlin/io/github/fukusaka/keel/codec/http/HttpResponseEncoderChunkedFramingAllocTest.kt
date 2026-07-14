package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import io.github.fukusaka.keel.pipeline.Pipeline
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Deterministic guard for the chunked-framing allocation shape.
 *
 * Per data chunk the encoder emits framing around the payload: a variable
 * `"{hex-size}\r\n"` header and a constant `"\r\n"` suffix. The header is
 * per-chunk (its hex differs), but the suffix is the same two bytes on every
 * chunk, so it is served from a single reusable per-encoder constant buffer
 * ([HttpResponseEncoder]) instead of a fresh framing view each time.
 *
 * This counts the buffers the encoder creates through its allocator across
 * [FRAME_COUNT] data chunks (measured after the head, before the terminator):
 * one header view per chunk plus the one-time constant `\r\n` buffer. Before
 * the constant-suffix change the encoder produced two framing views per chunk
 * (header + a fresh suffix view), so this asserts the halving structurally —
 * no timing, no JIT sensitivity.
 */
class HttpResponseEncoderChunkedFramingAllocTest {

    /** Delegating allocator that counts [allocate] calls (framing-buffer creations). */
    private class CountingAllocator(private val delegate: BufferAllocator) : BufferAllocator {
        var allocateCount: Int = 0

        override fun allocate(capacity: Int): IoBuf {
            allocateCount++
            return delegate.allocate(capacity)
        }

        override fun wrapBytes(bytes: ByteArray, offset: Int, length: Int): IoBuf? =
            delegate.wrapBytes(bytes, offset, length)

        override fun slice(source: IoBuf, offset: Int, length: Int): IoBuf =
            delegate.slice(source, offset, length)
    }

    private val counting = CountingAllocator(DefaultAllocator)
    private val transport = TestIoTransport(allocator = counting)
    private val channel = object : AbstractPipelinedChannel(transport, PrintLogger("alloc")) {}

    private fun bufOf(text: String): IoBuf {
        val bytes = text.encodeToByteArray()
        // Payload uses DefaultAllocator directly so it is not counted — only
        // the encoder's own framing allocations go through `counting`.
        val buf = DefaultAllocator.allocate(bytes.size)
        buf.writeByteArray(bytes, 0, bytes.size)
        return buf
    }

    private fun chunkedPipeline(): Pipeline {
        val pipeline = channel.pipeline
        pipeline.addLast("encoder", HttpResponseEncoder())
        val head = HttpResponseHead(
            status = HttpStatus.OK,
            headers = HttpHeaders.of("Transfer-Encoding" to "chunked"),
        )
        pipeline.requestWrite(head)
        return pipeline
    }

    @Test
    fun `chunked framing allocates one header view per chunk plus a single reusable CRLF constant`() {
        val pipeline = chunkedPipeline()

        // Count only the framing allocations made while emitting data chunks:
        // snapshot after the head write, stop before the terminator.
        val beforeChunks = counting.allocateCount
        repeat(FRAME_COUNT) {
            pipeline.requestWrite(HttpBody(bufOf("hello")))
        }
        val framingAllocs = counting.allocateCount - beforeChunks

        // One "{hex}\r\n" header view per chunk + one lazily-created reusable
        // "\r\n" constant (allocated on the first chunk, retained thereafter).
        assertEquals(
            FRAME_COUNT + 1,
            framingAllocs,
            "chunked framing must allocate one header view per chunk plus a single " +
                "shared CRLF constant, not a fresh suffix view per chunk",
        )

        pipeline.requestWrite(HttpBodyEnd.EMPTY)
    }

    private companion object {
        const val FRAME_COUNT = 100
    }
}
