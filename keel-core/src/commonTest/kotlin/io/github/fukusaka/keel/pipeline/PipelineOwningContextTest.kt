package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the outbound funnel: work handed to the pipeline from off the
 * transport's owning context is routed onto it rather than run on the
 * caller's thread, and is released rather than leaked once the transport has
 * closed and nothing will run it.
 *
 * The double dispatches through [RunImmediately], so a routed block still runs
 * before the call returns — what these assert is the *routing decision*, which
 * is the part the pipeline owns. The engine tests cover a real EventLoop.
 */

/**
 * Outbound funnel behaviour, driven through a transport that can be told it is
 * off its owning context.
 */
class PipelineOwningContextTest {

    private val logger = PrintLogger("PipelineOwningContextTest")
    private val transport = TestIoTransport().apply { dispatcher = RunImmediately }
    private val channel = object : AbstractPipelinedChannel(transport, logger) {}

    @Test
    fun `requestWrite from off the owning context still reaches the transport`() {
        transport.owningContext = false
        val tracker = TrackingAllocator()
        val buf = tracker.allocate(8).also { it.writerIndex = 4 }

        channel.pipeline.requestWrite(buf)

        assertEquals(1, transport.written.size, "the write must be routed, not dropped")
        assertEquals(0, tracker.outstandingCount - transport.written.size, "no double release")
        transport.releaseWritten()
        assertEquals(0, tracker.outstandingCount)
    }

    @Test
    fun `requestWrite from off the owning context releases when the transport is closed`() {
        // Nothing will ever run the dispatched block, so the buffer whose
        // ownership the caller just transferred has to be released here.
        transport.owningContext = false
        transport.close()
        val tracker = TrackingAllocator()
        val buf = tracker.allocate(8).also { it.writerIndex = 4 }

        channel.pipeline.requestWrite(buf)

        assertTrue(transport.written.isEmpty(), "a closed transport must not be written to")
        assertEquals(0, tracker.outstandingCount, "the abandoned write must still be released")
    }

    @Test
    fun `propagateWrite from off the owning context releases when the transport is closed`() {
        // Same guarantee one layer in: a handler that emits after finishing
        // asynchronous work must not leak when the connection went away first.
        transport.owningContext = false
        val tracker = TrackingAllocator()
        var ctx: PipelineHandlerContext? = null
        channel.pipeline.addLast(
            "capture",
            object : OutboundHandler {
                override fun onWrite(ctx: PipelineHandlerContext, msg: Any) = Unit
                override fun handlerAdded(ctx: PipelineHandlerContext) {
                    // Captured the way a handler would stash it for later use.
                }
            },
        )
        channel.pipeline.addLast(
            "emitter",
            object : OutboundHandler {
                override fun handlerAdded(c: PipelineHandlerContext) {
                    ctx = c
                }

                override fun onWrite(ctx: PipelineHandlerContext, msg: Any) = Unit
            },
        )
        transport.close()

        val buf = tracker.allocate(8).also { it.writerIndex = 4 }
        checkNotNull(ctx).propagateWrite(buf)

        assertEquals(0, tracker.outstandingCount, "the abandoned propagation must still be released")
    }

    @Test
    fun `requestWrite from off the owning context releases when the owning context has stopped`() {
        // The state neither existing case covers: the transport is *open*, so
        // the closed check passes, but its dispatcher has stopped -- the queue
        // accepts the block and nothing drains it, taking the buffer's
        // ownership with it, past anything the transport's own teardown can
        // reach. Asking the transport whether a dispatch would still run is
        // what turns that into a release.
        transport.owningContext = false
        transport.owningContextAlive = false
        val tracker = TrackingAllocator()
        val buf = tracker.allocate(8).also { it.writerIndex = 4 }

        channel.pipeline.requestWrite(buf)

        assertTrue(transport.isOpen, "premise: open, only its owning context stopped")
        assertTrue(transport.written.isEmpty(), "nothing runs the block, so nothing is written")
        assertEquals(0, tracker.outstandingCount, "the abandoned write must still be released")
    }

    @Test
    fun `requestWrite on the owning context runs inline`() {
        val tracker = TrackingAllocator()
        val buf = tracker.allocate(8).also { it.writerIndex = 4 }

        channel.pipeline.requestWrite(buf)

        assertEquals(1, transport.written.size)
        transport.releaseWritten()
        assertEquals(0, tracker.outstandingCount)
    }

    @Test
    fun `requestClose closes the transport when the owning context has stopped`() {
        // The chain walk ends at HeadHandler, whose only job is to close the
        // transport. If the walk cannot run, nothing releases the descriptor —
        // the write side releases its buffer, and before this the close side
        // did not even do that much.
        transport.owningContext = false
        transport.owningContextAlive = false

        channel.pipeline.requestClose()

        assertTrue(transport.isOpen.not(), "the close must still reach the transport")
        assertTrue(transport.closed, "the descriptor must be released, chain or no chain")
    }

    @Test
    fun `propagateClose closes the transport when the owning context has stopped`() {
        // Same guarantee one layer in: a handler closing after asynchronous
        // work, on a connection whose loop has since stopped.
        var ctx: PipelineHandlerContext? = null
        channel.pipeline.addLast(
            "emitter",
            object : OutboundHandler {
                override fun handlerAdded(c: PipelineHandlerContext) {
                    ctx = c
                }

                override fun onWrite(ctx: PipelineHandlerContext, msg: Any) = Unit
            },
        )
        transport.owningContext = false
        transport.owningContextAlive = false

        checkNotNull(ctx).propagateClose()

        assertTrue(transport.closed, "the descriptor must be released, chain or no chain")
    }

    @Test
    fun `a close on a stopped context releases the journalled reads as well as the descriptor`() {
        // The other way a stopped connection ends. Recovering the descriptor and
        // leaving the journal holding its buffers would fix half of the same
        // leak -- and the journal is this pipeline's own state, so the
        // transport's teardown cannot reach it.
        val tracker = TrackingAllocator()
        val stopped = TestIoTransport(tracker).apply { dispatcher = NeverRuns }
        val stoppedChannel = object : AbstractPipelinedChannel(stopped, logger) {}
        stoppedChannel.pipeline.notifyRead(tracker.allocate(8).also { it.writerIndex = 4 })
        assertEquals(1, tracker.outstandingCount, "premise: the read is journalled")

        stopped.owningContext = false
        stopped.owningContextAlive = false
        stoppedChannel.pipeline.requestClose()

        assertTrue(stopped.closed, "the descriptor must be released")
        assertEquals(0, tracker.outstandingCount, "and the journal must not be left holding its buffers")
    }

    @Test
    fun `a handler added after the owning context stopped releases the journalled reads`() {
        // The pipeline's other dispatch site. Reads that arrive before any
        // inbound handler exists are journalled and replayed on the next
        // dispatcher tick — but on a stopped loop that tick never comes, and the
        // journal holds pooled buffers for as long as the pipeline is reachable.
        //
        // Driven through a dispatcher that accepts and never runs, because one
        // that runs inline would perform the very replay a dead loop cannot.
        val tracker = TrackingAllocator()
        val stopped = TestIoTransport(tracker).apply { dispatcher = NeverRuns }
        val stoppedChannel = object : AbstractPipelinedChannel(stopped, logger) {}
        val buf = tracker.allocate(8).also { it.writerIndex = 4 }
        stoppedChannel.pipeline.notifyRead(buf)
        assertEquals(1, tracker.outstandingCount, "premise: the read is journalled, not yet delivered")
        assertTrue(stopped.isOpen, "premise: open, so the release is not just the closed-transport path")

        // Only `owningContextAlive` matters here: the handler-added path reads
        // that and never consults `inOwningContext`, so setting the latter
        // would suggest a coverage this test does not have.
        stopped.owningContextAlive = false
        stoppedChannel.pipeline.addLast(
            "late",
            object : InboundHandler {
                override fun onRead(ctx: PipelineHandlerContext, msg: Any) = Unit
            },
        )

        assertEquals(0, tracker.outstandingCount, "the journalled read must be released, not stranded")
    }

    @Test
    fun `a handler added after a peer close on a stopped context still receives onInactive`() {
        // The discard is what ends the journal, so it owes the late handler the
        // inactivation the drain would have delivered. Without that hand-off the
        // per-handler replay matches no branch and does nothing at all — and a
        // bridge installed after a peer close waits for an EOF it already
        // missed. Pins the guarantee, not the comment claiming it.
        val tracker = TrackingAllocator()
        val stopped = TestIoTransport(tracker).apply { dispatcher = NeverRuns }
        val stoppedChannel = object : AbstractPipelinedChannel(stopped, logger) {}
        stoppedChannel.pipeline.notifyInactive()
        // Journalled alongside the lifecycle state, so this also covers the
        // ordering the discard depends on: promote the flags, then drain the
        // queues.
        stoppedChannel.pipeline.notifyRead(tracker.allocate(8).also { it.writerIndex = 4 })

        stopped.owningContextAlive = false
        var sawInactive = false
        stoppedChannel.pipeline.addLast(
            "late",
            object : InboundHandler {
                override fun onRead(ctx: PipelineHandlerContext, msg: Any) = Unit
                override fun onInactive(ctx: PipelineHandlerContext) {
                    sawInactive = true
                }
            },
        )

        assertTrue(sawInactive, "the peer close observed before the handler existed must still reach it")
        assertEquals(0, tracker.outstandingCount, "and the journalled read alongside it must be released")
    }

    @Test
    fun `a handler added after activation on a stopped context still receives onActive`() {
        // The replay's other branch. The discard must leave the lifecycle
        // bookkeeping exactly where the drain would have — promoting all three
        // flags, not just the inactive one — or half the replay stays dead.
        val stopped = TestIoTransport().apply { dispatcher = NeverRuns }
        val stoppedChannel = object : AbstractPipelinedChannel(stopped, logger) {}
        stoppedChannel.pipeline.notifyActive()

        stopped.owningContextAlive = false
        var sawActive = false
        stoppedChannel.pipeline.addLast(
            "late",
            object : InboundHandler {
                override fun onRead(ctx: PipelineHandlerContext, msg: Any) = Unit
                override fun onActive(ctx: PipelineHandlerContext) {
                    sawActive = true
                }
            },
        )

        assertTrue(sawActive, "the activation observed before the handler existed must still reach it")
    }

    @Test
    fun `propagateWrite releases when its previous context resolves to null`() {
        // The chain walk runs inside the funnel, so a context that ends up with
        // no previous outbound handler — a detached node, or the head with
        // nothing before it — must release the message rather than drop it on
        // the `?: return` the old code used.
        val tracker = TrackingAllocator()
        var ctx: PipelineHandlerContext? = null
        channel.pipeline.addLast(
            "emitter",
            object : OutboundHandler {
                override fun handlerAdded(c: PipelineHandlerContext) {
                    ctx = c
                }

                override fun onWrite(ctx: PipelineHandlerContext, msg: Any) = Unit
            },
        )
        val emitter = checkNotNull(ctx)
        channel.pipeline.remove("emitter")

        val buf = tracker.allocate(8).also { it.writerIndex = 4 }
        emitter.propagateWrite(buf)

        assertEquals(0, tracker.outstandingCount, "a null previous context must release, not drop")
    }
}
