package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.testing.transport.TestIoTransport
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
    fun `a close that cannot reach the loop still walks the handlers in place`() {
        val transport = TestIoTransport()
        // A loop that has stopped: the pipeline can neither run the walk there
        // nor hand it over.
        transport.owningContext = false
        transport.owningContextAlive = false
        val channel = channelOver(transport)
        val recorder = Recorder()
        channel.pipeline.addLast("recorder", recorder)
        recorder.events.clear()

        channel.close()

        // The loop cannot take the walk, and a stopped loop is a quiescent one
        // -- the same premise under which the descriptor is released from this
        // thread -- so the walk runs here instead of being skipped. A handler
        // holding a native session hears its close either way.
        assertEquals(
            listOf("inactive", "close"),
            recorder.events,
            "a stopped loop carries the ending and the close, in place",
        )
        assertFalse(transport.isOpen)
    }

    @Test
    fun `a handler that asks the pipeline to close from inside its own close does not walk again`() {
        val transport = TestIoTransport()
        var depth = 0
        var maxDepth = 0
        val recorder = object : Recorder() {
            override fun onClose(ctx: PipelineHandlerContext) {
                depth++
                if (depth > maxDepth) maxDepth = depth
                events.add("close")
                // Straight to the pipeline, past the channel's own guard. The
                // cap keeps a regression from overflowing the stack instead
                // of failing the assertion.
                if (depth < RECURSION_CAP) ctx.pipeline.requestClose()
                ctx.propagateClose()
                depth--
            }
        }
        val channel = channelOver(transport)
        channel.pipeline.addLast("recorder", recorder)
        recorder.events.clear()

        channel.close()

        assertEquals(1, maxDepth, "the walk is the pipeline's to start once, whoever asks")
        assertEquals(listOf("inactive", "close"), recorder.events)
        assertFalse(transport.isOpen)
    }

    @Test
    fun `two close requests on the loop walk the handlers once`() {
        val transport = TestIoTransport()
        val channel = channelOver(transport)
        val recorder = Recorder()
        channel.pipeline.addLast("recorder", recorder)
        recorder.events.clear()

        // Two callers reaching the pipeline directly -- what two threads
        // closing at once become once the loop runs both their hand-offs.
        channel.pipeline.requestClose()
        channel.pipeline.requestClose()

        // The ending is the walk's own end of life — nobody told the pipeline
        // the connection ended before the walk, so the walk's completion does,
        // once. Its place relative to the close is not pinned: for a bare
        // request the order is the mechanism's, not a contract.
        assertEquals(
            1,
            recorder.events.count { it == "close" },
            "one walk, however many asked for it: ${recorder.events}",
        )
        assertEquals(1, recorder.events.count { it == "inactive" }, "and one ending: ${recorder.events}")
        assertFalse(transport.isOpen)
    }

    @Test
    fun `a write a handler issues from its close during the in-place walk is released rather than carried`() {
        // The in-place walk rests on the loop being quiescent; letting it
        // run outbound work inline would lift the loop's confinement for any
        // thread that wrote during the teardown. So the farewell is released,
        // exactly as it is for a close from off the loop.
        val tracker = TrackingAllocator()
        val transport = TestIoTransport(tracker)
        transport.owningContext = false
        transport.owningContextAlive = false
        val channel = channelOver(transport)
        val recorder = object : Recorder() {
            override fun onClose(ctx: PipelineHandlerContext) {
                events.add("close")
                ctx.pipeline.requestWrite(tracker.allocate(8).also { it.writerIndex = 4 })
                ctx.propagateClose()
            }
        }
        channel.pipeline.addLast("recorder", recorder)
        recorder.events.clear()

        channel.close()

        assertEquals(listOf("inactive", "close"), recorder.events)
        assertEquals(0, transport.written.size, "the farewell did not reach the transport")
        assertEquals(0, tracker.outstandingCount, "and was released")
    }

    @Test
    fun `a close a handler initiates from its own context is the walk and ends the life before the channel's close`() {
        // The Netty `ctx.close()` idiom: the handler starts the walk from its
        // own position toward the head, and its completion is the end of the
        // pipeline's life. The channel's later close finds nothing left to
        // walk, so the handler above heard its close once, from that walk.
        val transport = TestIoTransport()
        val channel = channelOver(transport)
        val above = Recorder()
        val closer = object : Recorder() {
            override fun onInactive(ctx: PipelineHandlerContext) {
                events.add("inactive")
                ctx.propagateClose()
                ctx.propagateInactive()
            }
        }
        channel.pipeline.addLast("above", above)
        channel.pipeline.addLast("closer", closer)
        above.events.clear()
        closer.events.clear()

        channel.pipeline.notifyInactive()
        channel.close()

        assertEquals(listOf("inactive", "close"), above.events, "once, from the handler's own close")
        assertFalse(transport.isOpen)
    }

    private companion object {
        const val RECURSION_CAP = 5
    }
}
