package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import io.github.fukusaka.keel.pipeline.InboundHandler
import io.github.fukusaka.keel.pipeline.Pipeline
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import io.github.fukusaka.keel.testing.transport.TestIoTransport

/**
 * The fixture shared by the [HttpRequestDecoder] tests: the pipeline under
 * test, the collector that records what reached the tail, and the buffer
 * helpers.
 *
 * Nested and `protected` rather than hoisted to package scope — `bufOf` is a
 * name several sibling test files declare for themselves.
 */
internal abstract class HttpRequestDecoderFixture {

    // --- Test infrastructure ---

    private val transport = TestIoTransport()
    private val channel = object : AbstractPipelinedChannel(transport, PrintLogger("test")) {}

    protected fun createPipeline(vararg handlers: Pair<String, InboundHandler>): Pipeline {
        val pipeline = channel.pipeline
        for ((name, handler) in handlers) pipeline.addLast(name, handler)
        return pipeline
    }

    /** Collects streaming HTTP messages delivered via [propagateRead]. */
    protected class MessageCollector : InboundHandler {
        val heads = mutableListOf<HttpRequestHead>()
        val bodies = mutableListOf<HttpBody>()
        val errors = mutableListOf<Throwable>()

        override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
            when (msg) {
                is HttpRequestHead -> heads.add(msg)
                is HttpBody -> bodies.add(msg) // HttpBodyEnd extends HttpBody
                else -> error("Unexpected message: ${msg::class.simpleName}")
            }
        }

        override fun onError(ctx: PipelineHandlerContext, cause: Throwable) {
            errors.add(cause)
        }
    }

    /** Builds an IoBuf containing the UTF-8 / ASCII bytes of [text]. */
    protected fun bufOf(text: String): IoBuf {
        val bytes = text.encodeToByteArray()
        val buf = DefaultAllocator.allocate(bytes.size)
        buf.writeByteArray(bytes, 0, bytes.size)
        return buf
    }

    /** Builds an IoBuf from raw [bytes] (for obs-text 0x80-0xFF, non-UTF-8). */
    protected fun bufOfBytes(bytes: ByteArray): IoBuf {
        val buf = DefaultAllocator.allocate(bytes.size)
        buf.writeByteArray(bytes, 0, bytes.size)
        return buf
    }

    // GET / HTTP/1.1 with `X-Note: <0xE9>` — a single obs-text byte that is
    // 'é' in ISO-8859-1 but an *invalid* UTF-8 sequence on its own.
    protected val obsTextRequest: ByteArray =
        "GET / HTTP/1.1\r\nHost: x\r\nX-Note: ".encodeToByteArray() +
            byteArrayOf(0xE9.toByte()) +
            "\r\n\r\n".encodeToByteArray()

    // Split index right after "X-Note: ", so the obs-text byte is parsed
    // through the fallback (ByteArray accumulator) path on the second read.
    protected val obsTextSplit: Int = "GET / HTTP/1.1\r\nHost: x\r\nX-Note: ".encodeToByteArray().size
}
