package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * That a transport can tell its pipeline a flush has landed.
 *
 * Every transport in the tree reports a flush completion — the POSIX and Node
 * ones inline, Netty and NWConnection through a captured callback in their
 * completion contexts — and until this wiring every one of them reported into
 * a null. Nothing in production assigned `IoTransport.onFlushComplete`, and
 * the pipeline had no entrance for it either, so a handler streaming something
 * out had no signal that its last chunk had gone.
 *
 * The signal is best-effort by design, and these say what that means: it can
 * arrive from inside the flush that caused it, and one raised before the
 * pipeline has a handler is dropped rather than kept — unlike a read, a
 * completion has no later reader, because a pipeline with no handlers wrote
 * nothing to complete.
 */
class FlushCompletionTest {

    private val logger = PrintLogger("FlushCompletionTest")

    private fun channelOver(transport: TestIoTransport): PipelinedChannel =
        object : AbstractPipelinedChannel(transport, logger) {}

    /** Records completions in order, alongside the writes they answer. */
    private class Recorder : DuplexHandler {
        val events: MutableList<String> = mutableListOf()

        override fun onFlush(ctx: PipelineHandlerContext) {
            events.add("flush")
            ctx.propagateFlush()
        }

        override fun onFlushComplete(ctx: PipelineHandlerContext) {
            events.add("landed")
            ctx.propagateFlushComplete()
        }
    }

    @Test
    fun `a transport that finishes a flush tells the pipeline`() {
        val transport = TestIoTransport()
        val channel = channelOver(transport)
        val recorder = Recorder()
        channel.pipeline.addLast("recorder", recorder)
        recorder.events.clear()

        // What a transport does once the bytes it accepted have gone.
        transport.onFlushComplete?.invoke()

        assertEquals(
            listOf("landed"),
            recorder.events,
            "the completion reaches the handler — before this, every engine reported into a null",
        )
    }

    @Test
    fun `a completion raised from inside the flush arrives after it`() {
        // A transport whose flush drains in place and answers before it
        // returns, which the callback's contract explicitly allows: the
        // completion lands inside the handler's own `onFlush` frame.
        val transport = object : TestIoTransport() {
            override fun flush(): Boolean {
                val drained = super.flush()
                onFlushComplete?.invoke()
                return drained
            }
        }
        val channel = channelOver(transport)
        val recorder = Recorder()
        channel.pipeline.addLast("recorder", recorder)
        recorder.events.clear()

        channel.pipeline.requestFlush()

        assertEquals(listOf("flush", "landed"), recorder.events)
    }

    @Test
    fun `two flushes are two completions`() {
        val transport = TestIoTransport()
        val channel = channelOver(transport)
        val recorder = Recorder()
        channel.pipeline.addLast("recorder", recorder)
        recorder.events.clear()

        transport.onFlushComplete?.invoke()
        transport.onFlushComplete?.invoke()

        // Not coalesced. A read boundary is a hint about a burst that has
        // ended; a flush completion answers a particular flush, and a handler
        // sending the next chunk on each one needs them counted.
        assertEquals(listOf("landed", "landed"), recorder.events)
    }

    @Test
    fun `a completion raised before the first handler is dropped`() {
        val transport = TestIoTransport()
        val channel = channelOver(transport)
        transport.onFlushComplete?.invoke()
        val recorder = Recorder()

        channel.pipeline.addLast("recorder", recorder)

        // Deliberately not journalled. The only writer on an empty pipeline is
        // the head, and it writes what a handler gave it — so there is nothing
        // this completion could be answering, and a handler installed later did
        // not ask for it.
        assertEquals(emptyList<String>(), recorder.events)
    }
}
