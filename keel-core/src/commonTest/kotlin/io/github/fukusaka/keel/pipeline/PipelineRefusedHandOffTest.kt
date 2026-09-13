package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.seconds

/**
 * A loop that answers it can take a hand-off and then throws when given one.
 *
 * A stopped event-loop group behaves this way: the answer and the hand-off are
 * two separate moments, and the group can stop between them. The pipeline hands
 * work over at four places, and each has to survive the throw rather than let it
 * travel to whoever asked for a close, a write or a read.
 */
private class RefusingLoop : CoroutineDispatcher() {
    var refusing = false

    override fun isDispatchNeeded(context: CoroutineContext): Boolean = refusing

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        if (refusing) throw IllegalStateException("the loop refuses the hand-off")
        block.run()
    }
}

private class RefusalSplitTransport(tracker: TrackingAllocator) : TestIoTransport(tracker) {
    override val reportsEveryEndAsReadClosed: Boolean get() = false
}

private fun channelOn(transport: TestIoTransport) =
    object : AbstractPipelinedChannel(transport, PrintLogger("refusal")) {}

private fun TrackingAllocator.bytes(vararg values: Byte): IoBuf =
    allocate(64).also { buf -> for (v in values) buf.writeByte(v) }

/**
 * What each hand-off does when the loop refuses it after saying it could take it.
 *
 * The answer is the same one a loop that says up front that it cannot take the
 * work already gets: the close runs on the caller's thread, and the journal
 * nothing will drain is discarded. Reaching that answer is what these pin — the
 * throw used to travel to the caller instead, leaving the pipeline unended and,
 * for a close asked of the pipeline, the descriptor still open.
 */
class PipelineRefusedHandOffTest {

    @Test
    fun `a close whose hand-off the loop refuses still ends the pipeline`() = runTest(timeout = 15.seconds) {
        val tracker = TrackingAllocator()
        val loop = RefusingLoop()
        val transport = RefusalSplitTransport(tracker).apply { dispatcher = loop }
        val channel = channelOn(transport)
        channel.ensureBridge()
        transport.onRead?.invoke(tracker.bytes(1, 2, 3))
        assertEquals(1, tracker.outstandingCount, "premise: the bridge is holding the peer's bytes")

        transport.owningContext = false
        loop.refusing = true

        channel.close()

        assertFalse(transport.isOpen, "the close released the descriptor")
        assertEquals(0, tracker.outstandingCount, "the end of life released what the bridge held")
    }

    @Test
    fun `a close asked of the pipeline and refused still closes the transport`() = runTest(timeout = 15.seconds) {
        val tracker = TrackingAllocator()
        val loop = RefusingLoop()
        val transport = RefusalSplitTransport(tracker).apply { dispatcher = loop }
        val channel = channelOn(transport)
        channel.ensureBridge()
        transport.onRead?.invoke(tracker.bytes(1, 2, 3))
        assertEquals(1, tracker.outstandingCount, "premise: the bridge is holding the peer's bytes")

        transport.owningContext = false
        loop.refusing = true

        channel.pipeline.requestClose()

        assertFalse(transport.isOpen, "the close released the descriptor")
        assertEquals(0, tracker.outstandingCount, "the end of life released what the bridge held")
    }

    @Test
    fun `a drain the loop refuses releases the reads rather than leaving them scheduled`() =
        runTest(timeout = 15.seconds) {
            val tracker = TrackingAllocator()
            val loop = RefusingLoop()
            val transport = RefusalSplitTransport(tracker).apply { dispatcher = loop }
            val channel = channelOn(transport)

            // A read with no chain to take it is journalled; the drain that
            // would replay it is asked for when the first inbound handler
            // lands, and handed to the loop.
            transport.onRead?.invoke(tracker.bytes(7, 8))
            assertEquals(1, tracker.outstandingCount, "premise: the read is in the journal")

            loop.refusing = true
            channel.pipeline.addLast("h", object : InboundHandler {})

            assertEquals(0, tracker.outstandingCount, "the discard released the read the drain would have replayed")

            channel.close()
            transport.releaseWritten()
            tracker.assertNoLeaks()
        }
}
