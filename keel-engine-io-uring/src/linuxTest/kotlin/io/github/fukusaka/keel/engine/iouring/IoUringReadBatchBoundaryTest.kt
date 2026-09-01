package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.pipeline.DuplexHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * That this engine announces the end of the reads it had for one event, over
 * a real socket rather than through the transport's callback by hand.
 *
 * `Pipeline.notifyReadComplete` reaching a handler is what the wiring in
 * `AbstractPipelinedChannel` does; whether *this* engine ever sends it is a
 * separate question, and one no test asked until this one. A handler that
 * answers a burst with one flush depends on the answer.
 */
class IoUringReadBatchBoundaryTest {

    private class BoundaryRecorder : DuplexHandler {
        val seen: MutableList<String> = mutableListOf()
        val firstBoundary: CompletableDeferred<List<String>> = CompletableDeferred()

        override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
            seen.add("read")
            (msg as? IoBuf)?.release()
        }

        override fun onReadComplete(ctx: PipelineHandlerContext) {
            seen.add("batchEnd")
            firstBoundary.complete(seen.toList())
        }
    }

    // The long budget throughout, not the per-op one: every wait here spans
    // the engine coming up and a full round trip through it, which is the
    // case that constant exists for on a loaded runner.
    @Test
    fun `bytes off the wire are followed by the boundary that closes them`() = runBlocking {
        withTimeout(IO_OP_LONG_TIMEOUT_MS) {
            val engine = IoUringEngine()
            val recorder = BoundaryRecorder()
            val server = engine.bindPipeline("127.0.0.1", 0) { channel ->
                channel.pipeline.addLast("recorder", recorder)
            }
            try {
                val port = (server.localAddress as InetSocketAddress).port
                val client = engine.connect("127.0.0.1", port)
                try {
                    val payload = "one burst"
                    val out = DefaultAllocator.allocate(payload.length)
                    for (b in payload.encodeToByteArray()) out.writeByte(b)
                    client.write(out)
                    client.flush()

                    assertEquals(
                        listOf("read", "batchEnd"),
                        recorder.firstBoundary.await(),
                        "the engine delivers what it read and then says the batch is over",
                    )
                } finally {
                    client.close()
                }
            } finally {
                server.close()
                engine.close()
            }
        }
    }
}
