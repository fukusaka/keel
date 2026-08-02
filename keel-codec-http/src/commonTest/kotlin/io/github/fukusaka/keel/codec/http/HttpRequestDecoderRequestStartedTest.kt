package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import io.github.fukusaka.keel.pipeline.InboundHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies [HttpRequestDecoder] emits the [HttpRequestStarted] lifecycle user-event
 * exactly once per request, at the start of the request line — the signal the
 * downstream [RequestDeadlineHandler] arms its header-complete deadline on.
 */
class HttpRequestDecoderRequestStartedTest {

    private val transport = TestIoTransport()
    private val channel = object : AbstractPipelinedChannel(transport, PrintLogger("test")) {}

    /** Records the order of request lifecycle signals: "start" / "head". */
    private class LifecycleRecorder : InboundHandler {
        val events = mutableListOf<String>()
        override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
            if (msg is HttpRequestHead) events.add("head")
            if (msg is IoBuf) msg.release()
        }
        override fun onUserEvent(ctx: PipelineHandlerContext, event: Any) {
            if (event === HttpRequestStarted) events.add("start")
        }
    }

    private fun newDecoderWith(recorder: LifecycleRecorder) {
        channel.pipeline.addLast("decoder", HttpRequestDecoder())
        channel.pipeline.addLast("rec", recorder)
    }

    private fun buf(text: String): IoBuf {
        val bytes = text.encodeToByteArray()
        val b = DefaultAllocator.allocate(bytes.size)
        b.writeByteArray(bytes, 0, bytes.size)
        return b
    }

    @Test
    fun `a single request emits start once before the head`() {
        val rec = LifecycleRecorder()
        newDecoderWith(rec)
        channel.pipeline.notifyRead(buf("GET /a HTTP/1.1\r\nHost: x\r\n\r\n"))
        assertEquals(listOf("start", "head"), rec.events, "start must precede head, emitted once")
    }

    @Test
    fun `a header trickled byte-by-byte still emits start exactly once`() {
        val rec = LifecycleRecorder()
        newDecoderWith(rec)
        val req = "GET /a HTTP/1.1\r\nHost: x\r\n\r\n"
        for (ch in req) channel.pipeline.notifyRead(buf(ch.toString()))
        assertEquals(listOf("start", "head"), rec.events, "trickle must announce the request start only once")
    }

    @Test
    fun `two pipelined requests each emit their own start`() {
        val rec = LifecycleRecorder()
        newDecoderWith(rec)
        // Both requests (no body) arrive in one buffer.
        channel.pipeline.notifyRead(buf("GET /a HTTP/1.1\r\nHost: x\r\n\r\nGET /b HTTP/1.1\r\nHost: x\r\n\r\n"))
        assertEquals(
            listOf("start", "head", "start", "head"),
            rec.events,
            "each pipelined request announces its own start",
        )
    }
}
