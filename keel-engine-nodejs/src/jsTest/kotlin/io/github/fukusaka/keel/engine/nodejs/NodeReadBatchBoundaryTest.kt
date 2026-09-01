package io.github.fukusaka.keel.engine.nodejs

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.pipeline.DuplexHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * That this engine announces the end of the reads it had for one `'data'`
 * event, over a real socket rather than through the transport's callback by
 * hand.
 *
 * `Pipeline.notifyReadComplete` reaching a handler is what the wiring in
 * `AbstractPipelinedChannel` does; whether *this* engine ever sends it is a
 * separate question, and one no test asked until this one. A handler that
 * answers a burst with one flush depends on the answer.
 */
class NodeReadBatchBoundaryTest {

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

    @Test
    fun `bytes off the wire are followed by the boundary that closes them`() = runTest(timeout = 15.seconds) {
        val engine = NodeEngine()
        val recorder = BoundaryRecorder()
        // A fixed port, because this engine assigns ephemeral ones
        // asynchronously and rejects port zero in `bindPipeline`.
        val server = engine.bindPipeline("127.0.0.1", BOUNDARY_PORT) { channel ->
            channel.pipeline.addLast("recorder", recorder)
        }
        try {
            val client = engine.connect("127.0.0.1", BOUNDARY_PORT)
            try {
                val payload = "one burst"
                val out = DefaultAllocator.allocate(payload.length)
                for (b in payload.encodeToByteArray()) out.writeByte(b)
                client.write(out)
                client.flush()

                val seen = recorder.firstBoundary.await()

                assertEquals(
                    listOf("read", "batchEnd"),
                    seen,
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

    private companion object {
        const val BOUNDARY_PORT = 19923
    }
}
