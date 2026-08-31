package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Records the lifecycle it is told about, and passes each on. */
private class RecordingHandler : DuplexHandler {
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

/**
 * What closing a channel owes the handlers standing in it.
 *
 * A channel's `close()` used to reach past its pipeline to the transport, so
 * the descriptor was released and nothing above it was told — measured on a
 * real connection, the handlers of a locally closed channel heard nothing at
 * all. Handlers are where a TLS session's native memory and a reader's queued
 * buffers are held, and `onClose` travelling to the head is the only thing
 * that asks for them back.
 *
 * The cases below are the ways a close arrives, each with its own way of
 * going wrong: once, twice, after the transport has gone by itself, through a
 * chain rather than to its first handler, carrying the journal nobody will
 * read, and with nobody left to carry it at all. The double dispatches inline
 * ([RunImmediately]), so a routed close has run by the time the call returns;
 * what is asserted is the routing decision, which is the pipeline's own. The
 * engine tests drive a real loop.
 *
 * Where a case turns [TestIoTransport.owningContext] off it is load-bearing,
 * not scenery. The double answers `true` by default, and on that branch the
 * funnel runs the walk inline without consulting the transport at all — so a
 * case that is *about* that consultation says so explicitly, and would pass
 * unfixed without it. The others close from the owning context, which is
 * where a loop closes its own connections.
 */
class PipelineCloseTest {

    private val logger = PrintLogger("PipelineCloseTest")

    private fun channelOver(transport: TestIoTransport): PipelinedChannel =
        object : AbstractPipelinedChannel(transport, logger) {}

    @Test
    fun `closing a channel tells the handlers standing in it`() {
        val transport = TestIoTransport().apply { dispatcher = RunImmediately }
        val channel = channelOver(transport)
        val handler = RecordingHandler()
        channel.pipeline.addLast("recorder", handler)

        channel.close()

        assertEquals(
            listOf("close"),
            handler.events,
            "the close reaches the handler — this is where a TLS session and queued buffers are given back",
        )
        assertTrue(transport.closed, "and it still ends at the head, which is what releases the descriptor")
    }

    @Test
    fun `closing a channel twice tells the handlers once`() {
        val transport = TestIoTransport().apply { dispatcher = RunImmediately }
        val channel = channelOver(transport)
        val handler = RecordingHandler()
        channel.pipeline.addLast("recorder", handler)

        // Both of these happen on an ordinary connection: the channel closes
        // itself when the peer goes away, and the application closes it too.
        channel.close()
        channel.close()

        assertEquals(
            listOf("close"),
            handler.events,
            "a second close is not a second release — the handlers hear it once",
        )
    }

    @Test
    fun `closing a channel whose transport already ended tells the handlers`() {
        val transport = TestIoTransport().apply { dispatcher = RunImmediately }
        val channel = channelOver(transport)
        val handler = RecordingHandler()
        channel.pipeline.addLast("recorder", handler)

        // Off the owning context, which is the whole case: the funnel's
        // inline branch never asks whether the transport is open, so on it
        // this passes without the code it exists for. The application thread
        // is where a close like this comes from anyway.
        transport.owningContext = false

        // The shape of a connection the transport ended on its own — a
        // readiness failure does this — with the application's close arriving
        // afterwards. The descriptor is already gone; what the handlers hold
        // is not, and nothing else is going to ask them for it.
        transport.close()
        channel.close()

        assertEquals(
            listOf("close"),
            handler.events,
            "a closed transport is no reason to keep the close from the handlers",
        )
    }

    @Test
    fun `a close on an ended transport reaches every handler and not just the first`() {
        val transport = TestIoTransport().apply { dispatcher = RunImmediately }
        val channel = channelOver(transport)
        val nearer = RecordingHandler()
        val further = RecordingHandler()
        // Outbound runs from the tail towards the head, so `further` is asked
        // first and `nearer` is the one a walk that stops early loses.
        channel.pipeline.addLast("nearer-the-head", nearer)
        channel.pipeline.addLast("further-from-the-head", further)
        transport.owningContext = false
        transport.close()

        channel.close()

        // What this adds over the single-handler case above is the *reach*
        // of the walk rather than its start. It does not isolate the hop's
        // funnel from the entrance's: both consult the same transport, so
        // measured, reverting either one fails this case and that one alike.
        // It is here for the walk that stops after the handler it reached
        // first, which nothing else would catch.
        assertEquals(listOf("close"), further.events, "the first handler the walk reaches hears it")
        assertEquals(
            listOf("close"),
            nearer.events,
            "and so does the one after it — the walk does not stop at the hop and jump to the head",
        )
    }

    @Test
    fun `closing a channel releases what the pipeline was holding for a handler that never came`() {
        val tracker = TrackingAllocator()
        val transport = TestIoTransport(tracker).apply { dispatcher = RunImmediately }
        val channel = channelOver(transport)

        // Journalled rather than delivered: an engine that arms its read
        // eagerly can land bytes before any handler is installed, and the
        // pipeline holds them for the one that arrives. They are pooled, and
        // a closing connection is where waiting for that handler ends.
        //
        // The reads only. The rest of the journal carries why a connection
        // ended, and a handler added after the close is a supported way to be
        // told — which is why this does not end the journal outright.
        channel.pipeline.notifyRead(tracker.allocate(8).also { it.writerIndex = 4 })
        assertEquals(1, tracker.outstandingCount, "the read is being held, which is what makes this a leak to fix")

        channel.close()

        assertEquals(
            0,
            tracker.outstandingCount,
            "the journal goes with the close — this frame is the last one that can reach it",
        )
    }

    @Test
    fun `closing a channel from off the owning context takes effect before it returns`() {
        // The walk is queued and never run, so the only thing that can answer
        // this is the claim taken on the calling thread. Which is the point:
        // handlers may only run on the owning context, so a caller from
        // anywhere else has its walk handed over — and `close()` still has to
        // mean something to the caller that asked, on the thread that asked.
        val transport = TestIoTransport().apply {
            dispatcher = NeverRuns
            owningContext = false
        }
        val channel = channelOver(transport)

        channel.close()

        assertFalse(
            channel.isOpen,
            "a close that has been handed over has still taken effect for its caller",
        )
    }

    @Test
    fun `closing a channel with no owning context left still releases the descriptor`() {
        val transport = TestIoTransport().apply {
            dispatcher = NeverRuns
            // Both, for the same reason as the case above: on the inline
            // branch there is no dispatch to refuse, so a dead loop is never
            // asked about.
            owningContext = false
            owningContextAlive = false
        }
        val channel = channelOver(transport)
        val handler = RecordingHandler()
        channel.pipeline.addLast("recorder", handler)

        channel.close()

        // The handlers genuinely miss it here, and that is the trade this
        // branch exists to make: there is no thread left that may walk the
        // chain, and the descriptor outranks the walk.
        assertTrue(
            transport.closed,
            "with nothing able to carry the close, the head's own job is still done directly",
        )
        assertEquals(
            emptyList(),
            handler.events,
            "and the walk is skipped rather than run on a thread that must not run it",
        )
    }
}
