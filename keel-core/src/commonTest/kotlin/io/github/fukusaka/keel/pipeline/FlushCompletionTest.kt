package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlin.coroutines.CoroutineContext
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
 * arrive from inside the flush that caused it, and one raised while a handler
 * is installed but the pipeline's drain has not run is held for that drain
 * rather than lost.
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
    fun `a completion raised before the drain is replayed by it`() {
        val transport = TestIoTransport()
        val queue = QueueingDispatcher()
        transport.dispatcher = queue
        val channel = channelOver(transport)
        val recorder = Recorder()
        // Attached, but the drain its installation scheduled has not run: the
        // window `callHandlerAdded` opens so a codec stack added back to back
        // replays once. Nothing gates a flush in it, so a handler that responds
        // on its activation issues one here.
        channel.pipeline.addLast("recorder", recorder)
        recorder.events.clear()

        transport.onFlushComplete?.invoke()
        transport.onFlushComplete?.invoke()

        assertEquals(emptyList<String>(), recorder.events, "nothing has run on the loop yet")

        queue.runQueued()

        // Both, not one: they are counted rather than folded, because each
        // answers a flush and a handler pacing itself on them would be short.
        assertEquals(listOf("landed", "landed"), recorder.events)
    }

    @Test
    fun `a handler that throws on a completion sends it down the error path`() {
        val transport = TestIoTransport()
        val channel = channelOver(transport)
        val seen = mutableListOf<String>()
        channel.pipeline.addLast(
            "thrower",
            object : DuplexHandler {
                override fun onFlushComplete(ctx: PipelineHandlerContext) {
                    seen.add("landed")
                    throw IllegalStateException("a handler that cannot finish its completion")
                }
            },
        )
        channel.pipeline.addLast(
            "catcher",
            object : DuplexHandler {
                override fun onError(ctx: PipelineHandlerContext, cause: Throwable) {
                    seen.add("error:" + (cause.message ?: ""))
                }
            },
        )
        seen.clear()

        transport.onFlushComplete?.invoke()

        // The throw does not escape into the transport's completion path,
        // whose caller is mid-teardown or mid-drain and has no way to answer
        // it. It goes where every other handler failure goes.
        assertEquals(
            listOf("landed", "error:a handler that cannot finish its completion"),
            seen,
        )
    }

    /** Holds dispatched work until a test asks for it. */
    private class QueueingDispatcher : CoroutineDispatcher() {
        private val queued = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queued.addLast(block)
        }

        fun runQueued() {
            while (queued.isNotEmpty()) queued.removeFirst().run()
        }
    }
}
