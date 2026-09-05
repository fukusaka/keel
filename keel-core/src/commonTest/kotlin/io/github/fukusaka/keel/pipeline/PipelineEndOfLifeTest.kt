package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * A pipeline has an end of life: once its channel's close has released the
 * transport, no handler can ever be added that would usefully drain the
 * pre-attach journal, so a journal nothing has drained is released there and
 * a read arriving after it on such a pipeline is released on arrival. A
 * pipeline whose handlers are already draining keeps delivering what the
 * transport still reports — what an ending means for those reads is the next
 * contract, not this one.
 *
 * Measured before this held: a channel closed before any inbound handler was
 * installed — a Coroutine-mode channel closed without a read, or a pipeline
 * whose installer threw before its first inbound handler — kept every
 * journalled pooled read (up to the journal's cap) for as long as the
 * pipeline was reachable, on every engine.
 */
class PipelineEndOfLifeTest {

    private class Fixture {
        val tracker = TrackingAllocator()
        val queue = QueueingDispatcher()
        val transport = TestIoTransport(tracker)
        val channel: PipelinedChannel = object : AbstractPipelinedChannel(transport, PrintLogger("end-of-life")) {}

        fun read() {
            channel.pipeline.notifyRead(tracker.allocate(8).also { it.writerIndex = 4 })
        }
    }

    @Test
    fun `closing a channel that never got an inbound handler releases the journalled reads`() {
        val f = Fixture()
        f.read()
        f.read()
        assertEquals(2, f.tracker.outstandingCount, "premise: both reads are journalled")

        f.channel.close()

        assertFalse(f.transport.isOpen)
        assertEquals(0, f.tracker.outstandingCount, "the journal goes with the transport")
    }

    @Test
    fun `a read arriving after the end of life of a pipeline nothing drained is released on arrival`() {
        val f = Fixture()
        f.channel.close()

        f.read()

        assertEquals(0, f.tracker.outstandingCount)
    }

    @Test
    fun `an inbound handler added after the end of life hears the ending and no read`() {
        val f = Fixture()
        f.read()
        f.channel.close()
        val events = mutableListOf<String>()

        f.channel.pipeline.addLast(
            "late",
            object : InboundHandler {
                override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
                    events.add("read")
                }

                override fun onInactive(ctx: PipelineHandlerContext) {
                    events.add("inactive")
                }
            },
        )

        assertEquals(listOf("inactive"), events)
        assertEquals(0, f.tracker.outstandingCount)
    }

    @Test
    fun `an outbound-only pipeline releases its journalled reads at the end of life too`() {
        val f = Fixture()
        f.channel.pipeline.addLast(
            "encoder",
            object : OutboundHandler {
                override fun onWrite(ctx: PipelineHandlerContext, msg: Any) = ctx.propagateWrite(msg)
            },
        )
        f.read()
        assertEquals(1, f.tracker.outstandingCount, "premise: an outbound-only pipeline still journals")

        f.channel.close()

        assertEquals(0, f.tracker.outstandingCount)
    }

    @Test
    fun `a read a handler already consumed is not released again at the end of life`() {
        val f = Fixture()
        f.channel.pipeline.addLast(
            "consumer",
            object : InboundHandler {
                override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
                    (msg as io.github.fukusaka.keel.buf.IoBuf).release()
                }
            },
        )
        f.read()
        assertEquals(0, f.tracker.outstandingCount, "premise: the handler released it")

        f.channel.close()

        assertEquals(0, f.tracker.outstandingCount)
    }

    @Test
    fun `a close from off the loop releases the journal once the loop has run it`() {
        val f = Fixture()
        f.transport.dispatcher = f.queue
        f.read()
        f.transport.owningContext = false
        f.queue.onRun = { f.transport.owningContext = true }

        f.channel.close()

        assertFalse(f.transport.isOpen, "the descriptor is released at once")
        f.queue.runQueued()
        assertEquals(0, f.tracker.outstandingCount, "and the journal once the loop ran the ending")
    }
}
