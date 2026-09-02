package io.github.fukusaka.keel.engine.nio

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.pipeline.DuplexHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlin.test.Test

/**
 * That this engine's flush completion reaches a handler, over a real socket.
 *
 * The channel-side wiring is checked in `keel-core` by invoking the transport's
 * callback from the test itself, which passes whether or not any engine ever
 * calls it. This asks the other half: a handler writes, the engine sends, and
 * the completion comes back up the pipeline — the loop a handler streaming
 * something out actually runs in.
 */
class NioFlushCompletionTest {

    private class Streamer : DuplexHandler {
        val landed: CompletableDeferred<Unit> = CompletableDeferred()

        override fun onActive(ctx: PipelineHandlerContext) {
            val payload = "one chunk".encodeToByteArray()
            val out = ctx.allocator.allocate(payload.size)
            for (b in payload) out.writeByte(b)
            ctx.propagateWrite(out)
            ctx.propagateFlush()
        }

        override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
            (msg as? IoBuf)?.release()
        }

        override fun onFlushComplete(ctx: PipelineHandlerContext) {
            landed.complete(Unit)
        }
    }

    @Test
    fun `a handler that writes and flushes is told when the bytes have gone`() = runTest {
        val engine = NioEngine()
        val streamer = Streamer()
        val server = engine.bindPipeline(LOOPBACK_HOST, 0) { channel ->
            channel.pipeline.addLast("streamer", streamer)
        }
        try {
            val port = (server.localAddress as InetSocketAddress).port
            val client = engine.connect(LOOPBACK_HOST, port)
            try {
                // The handler writes from `onActive`, so the connection alone
                // is the whole stimulus. Reading it back is what lets the
                // engine finish the send.
                val readBuf = DefaultAllocator.allocate(64)
                withTimeout(IO_OP_TIMEOUT_MS) { client.read(readBuf) }
                readBuf.release()

                // The wait is the assertion: this returns when the completion
                // has come back through the pipeline, and times out when it
                // never does.
                withTimeout(IO_OP_TIMEOUT_MS) { streamer.landed.await() }
            } finally {
                client.close()
            }
        } finally {
            server.close()
            engine.close()
        }
    }
}
