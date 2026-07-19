package io.github.fukusaka.keel.server.http

import io.github.fukusaka.keel.codec.http.Http1ServerCodec
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import io.github.fukusaka.keel.pipeline.InboundHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import io.github.fukusaka.keel.server.http.dsl.KeelHttpServerBuilder
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Covers the [PipelineInstaller] extension point and that the built-in
 * `compression { }` DSL preserves its behaviour when routed through it
 * (registration → invocation order, no-op condition, double-config error).
 */
class PipelineInstallerTest {

    private val transport = TestIoTransport()
    private val channel = object : AbstractPipelinedChannel(transport, PrintLogger("test")) {}
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private object PassThrough : InboundHandler {
        override fun onRead(ctx: PipelineHandlerContext, msg: Any) = ctx.propagateRead(msg)
    }

    private fun install(installers: List<PipelineInstaller>) {
        channel.installHttpServerPipeline(
            router = Router(),
            middlewares = emptyList(),
            errorHandlers = ErrorHandlers.DEFAULT,
            queryParameterConfig = QueryParameterConfig.DEFAULT,
            scope = scope,
            pipelineInstallers = installers,
        )
    }

    @Test
    fun `installers run in registration order between the codec and the handler`() {
        val calls = mutableListOf<String>()
        val a = PipelineInstaller { pipeline, _ ->
            calls.add("a")
            pipeline.addLast("inst-a", PassThrough)
        }
        val b = PipelineInstaller { pipeline, _ ->
            calls.add("b")
            pipeline.addLast("inst-b", PassThrough)
        }

        install(listOf(a, b))

        assertEquals(listOf("a", "b"), calls, "installers run in registration order")
        val pipeline = channel.pipeline
        assertNotNull(pipeline.get("inst-a"))
        assertNotNull(pipeline.get("inst-b"))
        // The codec and terminal handler are still present around the installers.
        assertNotNull(pipeline.get(Http1ServerCodec.DECODER))
        assertNotNull(pipeline.get(Http1ServerCodec.ENCODER))
        assertNotNull(pipeline.get(HTTP_SERVER_HANDLER_NAME))
    }

    @Test
    fun `no installers leaves the pipeline with only the codec and handler`() {
        install(emptyList())

        val pipeline = channel.pipeline
        assertNull(pipeline.get("compression"))
        assertNull(pipeline.get("request-decompression"))
        assertNotNull(pipeline.get(Http1ServerCodec.DECODER))
        assertNotNull(pipeline.get(HTTP_SERVER_HANDLER_NAME))
    }

    @Test
    fun `a second compression block is rejected`() {
        val builder = KeelHttpServerBuilder()
        builder.compression { encoder(FakeEncoderCodec("gzip")) }
        assertFailsWith<IllegalStateException> {
            builder.compression { encoder(FakeEncoderCodec("deflate")) }
        }
    }

    @Test
    fun `an empty compression block is a no-op and does not consume the single-config slot`() {
        val builder = KeelHttpServerBuilder()
        // Empty block builds a null config: it registers no installer and must
        // not mark compression as configured, so a real block afterwards works.
        builder.compression { /* no encoders, no requestDecompression */ }
        builder.compression { encoder(FakeEncoderCodec("gzip")) }
    }
}

private class FakeEncoderCodec(override val name: String) : io.github.fukusaka.keel.compression.CompressionCodec {
    override val encoder: io.github.fukusaka.keel.compression.Encoder =
        object : io.github.fukusaka.keel.compression.Encoder {
            override val name: String = this@FakeEncoderCodec.name
            override fun newSession(
                allocator: io.github.fukusaka.keel.buf.BufferAllocator,
                options: io.github.fukusaka.keel.compression.EncoderOptions,
            ): io.github.fukusaka.keel.compression.EncoderSession =
                throw UnsupportedOperationException("not needed for DSL tests")
        }
    override val decoder: io.github.fukusaka.keel.compression.Decoder =
        object : io.github.fukusaka.keel.compression.Decoder {
            override val name: String = this@FakeEncoderCodec.name
            override fun newSession(
                allocator: io.github.fukusaka.keel.buf.BufferAllocator,
                options: io.github.fukusaka.keel.compression.DecoderOptions,
            ): io.github.fukusaka.keel.compression.DecoderSession =
                throw UnsupportedOperationException("not needed for DSL tests")
        }
}
