package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.pipeline.ChannelPipeline
import io.github.fukusaka.keel.pipeline.DefaultChannelPipeline
import io.github.fukusaka.keel.pipeline.IoTransport
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.pipeline.SuspendBridgeHandler
import io.github.fukusaka.keel.pipeline.ChannelHandlerContext
import io.github.fukusaka.keel.pipeline.ChannelInboundHandler
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * End-to-end integration test for the streaming HTTP pipeline:
 * `[Decoder] → [Aggregator] → [Collector]`.
 *
 * Verifies that raw HTTP bytes are decoded, body chunks are aggregated,
 * and a complete [HttpRequest] is delivered to the collector.
 */
class HttpPipelineIntegrationTest {

    // --- Test infrastructure ---

    private val transport = object : IoTransport {
        override fun write(buf: IoBuf) {}
        override fun flush(): Boolean = true
        override var onFlushComplete: (() -> Unit)? = null
        override fun close() {}
    }

    private val channel = object : PipelinedChannel {
        override lateinit var pipeline: ChannelPipeline
        override val isActive: Boolean = true
        override val isWritable: Boolean = true
        override val allocator: BufferAllocator get() = DefaultAllocator
        override fun ensureBridge(): SuspendBridgeHandler = error("not needed in tests")
    }

    /** Collects aggregated [HttpRequest] messages. */
    private class RequestCollector : ChannelInboundHandler {
        override val acceptedType: KClass<*> get() = HttpRequest::class
        val requests = mutableListOf<HttpRequest>()
        val errors = mutableListOf<Throwable>()

        override fun onRead(ctx: ChannelHandlerContext, msg: Any) {
            requests.add(msg as HttpRequest)
        }

        override fun onError(ctx: ChannelHandlerContext, cause: Throwable) {
            errors.add(cause)
        }
    }

    private fun createPipeline(): Pair<ChannelPipeline, RequestCollector> {
        val collector = RequestCollector()
        val pipeline = DefaultChannelPipeline(channel, transport, PrintLogger("test"))
        channel.pipeline = pipeline
        pipeline.addLast("decoder", HttpRequestDecoder())
        pipeline.addLast("aggregator", HttpBodyAggregator())
        pipeline.addLast("collector", collector)
        return pipeline to collector
    }

    private fun bufOf(text: String): IoBuf {
        val bytes = text.encodeToByteArray()
        val buf = DefaultAllocator.allocate(bytes.size)
        buf.writeByteArray(bytes, 0, bytes.size)
        return buf
    }

    // --- Integration tests ---

    @Test
    fun `fixed Content-Length POST request flows through decoder and aggregator`() {
        val (pipeline, collector) = createPipeline()

        pipeline.notifyRead(
            bufOf(
                "POST /echo HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Length: 11\r\n" +
                "\r\n" +
                "hello world",
            ),
        )

        assertEquals(1, collector.requests.size)
        val req = collector.requests[0]
        assertEquals(HttpMethod.POST, req.method)
        assertEquals("/echo", req.path)
        assertNotNull(req.body)
        assertEquals("hello world", req.body!!.decodeToString())
    }

    @Test
    fun `chunked POST request flows through decoder and aggregator`() {
        val (pipeline, collector) = createPipeline()

        pipeline.notifyRead(
            bufOf(
                "POST /echo HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "\r\n" +
                "5\r\nhello\r\n" +
                "6\r\n world\r\n" +
                "0\r\n" +
                "\r\n",
            ),
        )

        assertEquals(1, collector.requests.size)
        val req = collector.requests[0]
        assertEquals(HttpMethod.POST, req.method)
        assertEquals("/echo", req.path)
        assertNotNull(req.body)
        assertEquals("hello world", req.body!!.decodeToString())
    }
}
