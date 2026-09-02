package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * That closing a channel asks its handlers to close.
 *
 * `Pipeline.requestClose` and `PipelineHandler.onClose` have existed since the
 * pipeline was written with no caller outside tests: `close()` went straight to
 * the transport, so `onClose` never ran on a connection. A handler holding
 * something the connection owns — a TLS codec's native session, most of all —
 * had no point at which it was told to let go.
 *
 * The descriptor is deliberately not left to that walk. These pin the handlers
 * hearing the close, the transport ending up closed whatever the handlers do
 * with it, and the walk being asked for once however many times a handler asks
 * back. What they do not pin is the guard on the release itself — that a walk
 * which already closed the transport is not made to close it twice — which is
 * held by the close counts in `AbstractPipelinedChannelTest`.
 */
class ChannelCloseWalkTest {

    private val logger = PrintLogger("ChannelCloseWalkTest")

    private fun channelOver(transport: TestIoTransport): PipelinedChannel =
        object : AbstractPipelinedChannel(transport, logger) {}

    /** Records the endings it is told about, in order. */
    private open class Recorder : DuplexHandler {
        val events: MutableList<String> = mutableListOf()

        override fun onInactive(ctx: PipelineHandlerContext) {
            events.add("inactive")
            ctx.propagateInactive()
        }

        override fun onClose(ctx: PipelineHandlerContext) {
            events.add("close")
            ctx.propagateClose()
        }
    }

    @Test
    fun `two closes off the loop walk the handlers once`() {
        val transport = TestIoTransport()
        val queue = QueueingDispatcher()
        transport.dispatcher = queue
        val channel = channelOver(transport)
        val recorder = Recorder()
        channel.pipeline.addLast("recorder", recorder)
        queue.runQueued()
        recorder.events.clear()
        transport.owningContext = false
        queue.onRun = { transport.owningContext = true }

        // Both closes run before the loop gets to either hand-off. The flag is
        // written before the dispatch, so the second close queues no second
        // walk — a mutation probe showed nothing pinned this: making the
        // capture always-true survived every case in the tree.
        channel.close()
        channel.close()

        queue.runQueued()

        assertEquals(
            listOf("inactive", "close"),
            recorder.events,
            "one ending and one walk, however many times the caller closed before the loop ran",
        )
    }

    /**
     * Holds dispatched work until a test asks for it, so the queued walk is
     * observable.
     *
     * [runQueued] drains until nothing is left rather than running one round:
     * off the owning thread the outbound walk queues *each hop* — every
     * `propagateClose` goes back through the pipeline's dispatch — so a single
     * round runs the tail and leaves the rest of the chain behind.
     */
    private class QueueingDispatcher : CoroutineDispatcher() {
        private val queued = ArrayDeque<Runnable>()

        /** Run once before the queued work, to put the transport on its loop. */
        var onRun: (() -> Unit)? = null

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queued.addLast(block)
        }

        fun runQueued() {
            onRun?.invoke()
            while (queued.isNotEmpty()) queued.removeFirst().run()
        }
    }

    @Test
    fun `a channel the caller closes tells its handlers to close`() {
        val transport = TestIoTransport()
        val channel = channelOver(transport)
        val recorder = Recorder()
        channel.pipeline.addLast("recorder", recorder)
        recorder.events.clear()

        channel.close()

        assertEquals(
            listOf("inactive", "close"),
            recorder.events,
            "the ending it observes, then the close it is being asked to perform",
        )
        assertFalse(transport.isOpen)
    }

    @Test
    fun `a handler that swallows the close does not strand the descriptor`() {
        val transport = TestIoTransport()
        val channel = channelOver(transport)
        val recorder = object : Recorder() {
            override fun onClose(ctx: PipelineHandlerContext) {
                events.add("close")
                // Deliberately not propagated. The walk ends here, so it never
                // reaches the head — whose entire job on this event is to close
                // the transport.
            }
        }
        channel.pipeline.addLast("recorder", recorder)
        recorder.events.clear()

        channel.close()

        assertEquals(listOf("inactive", "close"), recorder.events)
        assertFalse(
            transport.isOpen,
            "the descriptor is released by the caller's own close, not by the walk reaching the head",
        )
    }

    @Test
    fun `a handler that throws on close does not keep the descriptor`() {
        val transport = TestIoTransport()
        val channel = channelOver(transport)
        val recorder = object : Recorder() {
            override fun onClose(ctx: PipelineHandlerContext) {
                events.add("close")
                throw IllegalStateException("a handler that cannot finish its close")
            }
        }
        channel.pipeline.addLast("recorder", recorder)
        recorder.events.clear()

        channel.close()

        assertEquals(listOf("inactive", "close"), recorder.events)
        assertFalse(transport.isOpen)
    }

    @Test
    fun `closing twice asks the handlers once`() {
        val transport = TestIoTransport()
        val channel = channelOver(transport)
        val recorder = Recorder()
        channel.pipeline.addLast("recorder", recorder)
        recorder.events.clear()

        channel.close()
        channel.close()

        // The second close finds the transport already gone and has nothing
        // left to ask about. A handler that released on the first would
        // otherwise be asked to release again.
        assertEquals(listOf("inactive", "close"), recorder.events)
    }

    @Test
    fun `a close from off the owning thread releases first and walks after`() {
        val transport = TestIoTransport()
        val queue = QueueingDispatcher()
        transport.dispatcher = queue
        // Shaped like a real engine rather than like a fixed flag: every
        // transport in the tree answers `inOwningContext` by asking which
        // thread it is on, so work handed to the loop reports `true` once it
        // runs there. A test that leaves this `false` throughout sees the walk
        // abandoned at its first hop, which is an artifact of the double —
        // no engine behaves that way.
        transport.owningContext = false
        queue.onRun = { transport.owningContext = true }
        val channel = channelOver(transport)
        val recorder = Recorder()
        channel.pipeline.addLast("recorder", recorder)
        recorder.events.clear()

        channel.close()

        assertFalse(
            transport.isOpen,
            "the release does not wait on the queued walk — it is as prompt as it was when close() " +
                "did nothing but close the transport",
        )
        assertEquals(emptyList<String>(), recorder.events, "nothing has run on the loop yet")

        queue.runQueued()

        // The handler does hear its close off this thread. What it does not get
        // is a transport to use: the release above went first, so a handler
        // that wanted to write a farewell has nowhere to put it.
        assertEquals(listOf("inactive", "close"), recorder.events)
    }

    @Test
    fun `a handler that closes from inside its own close does not walk again`() {
        val transport = TestIoTransport()
        lateinit var channel: PipelinedChannel
        var depth = 0
        var maxDepth = 0
        val recorder = object : Recorder() {
            override fun onClose(ctx: PipelineHandlerContext) {
                depth++
                if (depth > maxDepth) maxDepth = depth
                events.add("close")
                // A handler that force-closes the channel as it tears down.
                // Without a guard this re-enters `close()`, which finds the
                // transport still open — the walk has not reached its end yet —
                // and starts the walk again. Measured at 2620 frames before a
                // stack overflow, which the walk's own catch then swallowed.
                channel.close()
                depth--
            }
        }
        channel = channelOver(transport)
        channel.pipeline.addLast("recorder", recorder)
        recorder.events.clear()

        channel.close()

        assertEquals(1, maxDepth, "the close is asked for once, however many times a handler asks back")
        assertEquals(listOf("inactive", "close"), recorder.events)
        assertFalse(transport.isOpen)
    }

    @Test
    fun `a channel whose transport died first still asks its handlers to close`() {
        val transport = TestIoTransport()
        val channel = channelOver(transport)
        val recorder = Recorder()
        channel.pipeline.addLast("recorder", recorder)
        recorder.events.clear()
        // What an idle reclaim or a connection failure does to a Coroutine-mode
        // channel: the transport closes itself, and the application's own
        // `close()` comes later. The handler still holds what it holds.
        transport.close()

        channel.close()

        assertEquals(listOf("inactive", "close"), recorder.events)
    }

    @Test
    fun `a close that cannot reach the loop goes past the handlers`() {
        val transport = TestIoTransport()
        // A loop that has stopped: the pipeline can neither run the walk here
        // nor hand it over.
        transport.owningContext = false
        transport.owningContextAlive = false
        val channel = channelOver(transport)
        val recorder = Recorder()
        channel.pipeline.addLast("recorder", recorder)
        recorder.events.clear()

        channel.close()

        // The ending still arrives — it is raised where it is, not dispatched.
        // The close does not: the fallback reproduces the walk's last step and
        // nothing before it, so the handlers are skipped. Deliberately, and the
        // reason it is reported rather than done quietly. Asserting only that
        // the transport ended would say nothing, since this method's own last
        // line does that whatever the pipeline did.
        assertEquals(
            listOf("inactive"),
            recorder.events,
            "a stopped loop carries the ending but not the close",
        )
        assertFalse(transport.isOpen)
    }
}
