package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlin.coroutines.CoroutineContext
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
private object RunImmediately : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable): Unit = block.run()
}

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
