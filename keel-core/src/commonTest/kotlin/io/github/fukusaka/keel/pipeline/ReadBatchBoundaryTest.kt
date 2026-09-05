package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * That a transport can tell its pipeline where a batch of reads ends.
 *
 * `Pipeline.notifyReadComplete` and `PipelineHandler.onReadComplete` have
 * existed since the pipeline was written, and nothing sent the signal: no
 * transport had a way to say it, so `onReadComplete` never ran on any
 * connection and the only thing that consumed one was `TailHandler`.
 *
 * What it is for is answering a burst with one flush instead of one per
 * message. A handler that wants that needs to be told when there is no more
 * coming *for now*, and "for now" is what each engine can actually
 * distinguish — the reads drained from a single wake, a single completion,
 * or a single framework callback.
 */
class ReadBatchBoundaryTest {

    private val logger = PrintLogger("ReadBatchBoundaryTest")

    private val tracker = TrackingAllocator(DefaultAllocator)

    private fun transport(): TestIoTransport = TestIoTransport(tracker)

    private fun channelOver(transport: TestIoTransport): PipelinedChannel =
        object : AbstractPipelinedChannel(transport, logger) {}

    /**
     * That every buffer these cases hand to a read was released.
     *
     * The handler propagates rather than consuming, so the release is the
     * tail's — which is correct, and worth pinning: a boundary must not change
     * who owns a read, and without this the cases would pass just as well if it
     * did.
     */
    @AfterTest
    fun everyReadWasReleased() {
        assertEquals(0, tracker.outstandingCount, "a read this case delivered was not released")
    }

    /** Records reads and the boundaries between them, in order. */
    private open class Recorder : DuplexHandler {
        val events: MutableList<String> = mutableListOf()

        override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
            events.add("read")
            ctx.propagateRead(msg)
        }

        override fun onReadComplete(ctx: PipelineHandlerContext) {
            events.add("batchEnd")
            ctx.propagateReadComplete()
        }

        override fun onInactive(ctx: PipelineHandlerContext) {
            events.add("inactive")
            ctx.propagateInactive()
        }
    }

    @Test
    fun `a transport that finishes a batch of reads tells the pipeline`() {
        val transport = transport()
        val channel = channelOver(transport)
        val recorder = Recorder()
        channel.pipeline.addLast("recorder", recorder)

        // What an engine does at the end of the reads it had for one wake.
        transport.onRead?.invoke(transport.allocator.allocate(8).also { it.writerIndex = 4 })
        transport.onReadComplete?.invoke()

        assertEquals(
            listOf("read", "batchEnd"),
            recorder.events,
            "the boundary reaches the handler after the reads it closes — a handler answering a " +
                "burst with one flush has nothing to hang it on without this",
        )
    }

    @Test
    fun `two batches are two boundaries`() {
        val transport = transport()
        val channel = channelOver(transport)
        val recorder = Recorder()
        channel.pipeline.addLast("recorder", recorder)

        transport.onRead?.invoke(transport.allocator.allocate(8).also { it.writerIndex = 4 })
        transport.onReadComplete?.invoke()
        transport.onRead?.invoke(transport.allocator.allocate(8).also { it.writerIndex = 4 })
        transport.onReadComplete?.invoke()

        assertEquals(listOf("read", "batchEnd", "read", "batchEnd"), recorder.events)
    }

    @Test
    fun `the boundary a channel journals before its first handler is replayed once`() {
        val transport = transport()
        val channel = channelOver(transport)
        // Both arrive while the pipeline is still empty, so the journal holds
        // them and the drain is what delivers them — the path every
        // eagerly-arming engine takes on a connection whose peer speaks first.
        transport.onRead?.invoke(transport.allocator.allocate(8).also { it.writerIndex = 4 })
        transport.onReadComplete?.invoke()
        val recorder = Recorder()

        channel.pipeline.addLast("recorder", recorder)

        assertEquals(listOf("read", "batchEnd"), recorder.events)
    }

    @Test
    fun `batches journalled before the first handler arrive as one boundary`() {
        val transport = transport()
        val channel = channelOver(transport)
        repeat(2) {
            transport.onRead?.invoke(transport.allocator.allocate(8).also { it.writerIndex = 4 })
            transport.onReadComplete?.invoke()
        }
        val recorder = Recorder()

        channel.pipeline.addLast("recorder", recorder)

        // Not `[read, batchEnd, read, batchEnd]`. The journal holds reads in a
        // queue and the boundary as a flag, so the drain replays every read it
        // held and then one boundary — the pipeline has coalesced consecutive
        // boundaries since it was written, on the ground that a handler treats
        // one as a hint that now is a good moment to flush rather than as a
        // frame delimiter. A handler installed late is told about the burst it
        // missed, not about how the engine happened to split it.
        assertEquals(listOf("read", "read", "batchEnd"), recorder.events)
    }

    @Test
    fun `a boundary with nothing before it still reaches the handler`() {
        val transport = transport()
        val channel = channelOver(transport)
        val recorder = Recorder()
        channel.pipeline.addLast("recorder", recorder)

        // Netty ends every read cycle with its boundary, including a cycle
        // whose first read returned no bytes: `AbstractNioByteChannel.read`
        // breaks out of the loop on `lastBytesRead() <= 0` and still calls
        // `fireChannelReadComplete()`. A handler cannot read this as "reads
        // just arrived".
        transport.onReadComplete?.invoke()

        assertEquals(listOf("batchEnd"), recorder.events)
    }

    @Test
    fun `a handler that closes mid-batch does not get the boundary that follows`() {
        val transport = transport()
        lateinit var channel: PipelinedChannel
        val recorder = object : Recorder() {
            override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
                super.onRead(ctx, msg)
                channel.close()
            }
        }
        channel = channelOver(transport)
        channel.pipeline.addLast("recorder", recorder)

        transport.onRead?.invoke(transport.allocator.allocate(8).also { it.writerIndex = 4 })
        // The transport is in the middle of delivering a batch and says so
        // when it finishes, whatever the handler did to the channel meanwhile.
        transport.onReadComplete?.invoke()

        assertEquals(
            listOf("read", "inactive"),
            recorder.events,
            "the close ended the pipeline's life inside the batch, so the boundary that follows " +
                "reaches nobody — nothing is delivered after the ending",
        )
    }
}
