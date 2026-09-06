package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * What a transport written before the peer's end of file became an event of
 * its own is answered with.
 *
 * Such a transport has one report for every way a connection can be over, and
 * it makes that report the only way it can: on `onReadClosed`, either through
 * the deprecated alias or by invoking the hook. Everything here is about that
 * report being read as the end it is — the split contract is
 * [PipelineReadClosedTest], whose transport says it speaks it.
 */
class TransportPreSplitReportTest {

    /** A timer that only records; the test decides what fires. */
    private class RecordingTimer : EventLoopTimer {
        val scheduled = mutableListOf<Handle>()

        inner class Handle(val task: () -> Unit) : TimerHandle {
            var cancelled = false

            override fun touch() = Unit

            override fun cancel() {
                cancelled = true
            }
        }

        override fun schedule(delayMillis: Long, task: () -> Unit): TimerHandle = Handle(task).also { scheduled += it }
    }

    /** The base with the entries a transport reports through opened to the test. */
    private class Transport(
        tracker: TrackingAllocator = TrackingAllocator(),
        val timer: RecordingTimer = RecordingTimer(),
    ) : TestIoTransport(tracker) {
        override val eventLoopTimer: EventLoopTimer get() = timer
        override val idleTimeoutMillis: Long get() = 1

        fun waitToRead() = armIdleTimeout()

        fun peerFin() = reportReadClosedOnce()

        fun endItself() = reportEndOnce()

        @Suppress("DEPRECATION")
        fun reportTheOldWay() = reportInactiveOnce()

        @Suppress("DEPRECATION")
        fun terminalReportAlreadyMade(): Boolean = inactiveAlreadyReported

        fun readSideEndReported(): Boolean = readClosedAlreadyReported
    }

    /** The same base, saying it tells the peer's end of file apart. */
    private class SplitTransport(
        tracker: TrackingAllocator = TrackingAllocator(),
    ) : TestIoTransport(tracker) {
        override val reportsEveryEndAsReadClosed: Boolean get() = false

        fun endItself() = reportEndOnce()

        fun peerFin() = reportReadClosedOnce()

        @Suppress("DEPRECATION")
        fun terminalReportAlreadyMade(): Boolean = inactiveAlreadyReported

        fun readSideEndReported(): Boolean = readClosedAlreadyReported
    }

    /** Records the inbound events under their names. */
    private class Recorder(val log: MutableList<String>) : InboundHandler {
        override fun onActive(ctx: PipelineHandlerContext) {
            log.add("active")
        }

        override fun onReadClosed(ctx: PipelineHandlerContext) {
            log.add("readClosed")
        }

        override fun onInactive(ctx: PipelineHandlerContext) {
            log.add("inactive")
        }
    }

    @Test
    fun `the base speaks the contract from before the split`() {
        assertTrue(
            Transport().reportsEveryEndAsReadClosed,
            "a transport extending the pre-split base cannot say it tells the two apart, so the base says it does not",
        )
    }

    @Test
    fun `a report from before the split reaches the chain as the ending`() {
        val transport = Transport()
        val log = mutableListOf<String>()
        val channel = object : AbstractPipelinedChannel(transport, PrintLogger("pre-split")) {}
        channel.pipeline.addLast("recorder", Recorder(log))

        transport.onReadClosed?.invoke()

        assertEquals(
            listOf("active", "inactive"),
            log,
            "the one report a pre-split transport makes is every way its connection could be over",
        )
    }

    @Test
    fun `the deprecated alias reports what it always did`() {
        val transport = Transport()
        val log = mutableListOf<String>()
        val channel = object : AbstractPipelinedChannel(transport, PrintLogger("pre-split")) {}
        channel.pipeline.addLast("recorder", Recorder(log))

        transport.reportTheOldWay()

        assertEquals(listOf("active", "inactive"), log)
    }

    @Test
    fun `the connection ending marks the terminal report as made`() {
        // A transport that tells the two apart, so the report takes the hook
        // for the end alone — a pre-split one reports on the read side's, and
        // a check that reads only that flag would answer correctly by luck.
        val transport = SplitTransport()
        var ended = 0
        transport.onClosed = { ended++ }
        assertFalse(transport.terminalReportAlreadyMade(), "premise: nothing reported yet")

        transport.endItself()

        assertEquals(1, ended, "premise: the end went out on its own hook, not the read side's")
        assertFalse(transport.readSideEndReported(), "premise: and the read side was not reported")
        assertTrue(
            transport.terminalReportAlreadyMade(),
            "a caller asking whether the listener has been told the connection is over gets the end too",
        )
    }

    @Test
    fun `the connection ending is reported on the read side by a transport that reports one end`() {
        val transport = Transport()
        var ended = 0
        var readClosed = 0
        transport.onClosed = { ended++ }
        transport.onReadClosed = { readClosed++ }

        transport.endItself()

        assertEquals(0, ended, "the hook for the end alone means a report this transport never makes")
        assertEquals(1, readClosed, "so the one report it does make carries the end, as it always did")
        assertTrue(transport.terminalReportAlreadyMade())
    }

    @Test
    fun `nothing reports the peer's end of file after the connection's end`() {
        // The end is the last thing a listener hears. A path discovering the
        // peer's own afterwards has nobody left to tell.
        val transport = SplitTransport()
        var readClosed = 0
        transport.onClosed = { }
        transport.onReadClosed = { readClosed++ }

        transport.endItself()
        transport.peerFin()

        assertEquals(0, readClosed, "the connection was already reported over")
    }

    @Test
    fun `the peer's end of file marks the terminal report as made`() {
        val transport = Transport()

        transport.peerFin()

        assertTrue(transport.terminalReportAlreadyMade())
    }

    @Test
    fun `an idle reclamation reports the way the transport always did`() {
        // The channel offers a hook for the end alone, and a transport from
        // before the split has no report that means it. Filling that hook
        // would end the channel by a route this transport never takes: the
        // handlers' close walk would run at reclamation time, and a caller's
        // read would answer the end of file where it was told this was a
        // misuse.
        val transport = Transport()
        val log = mutableListOf<String>()
        val channel = object : AbstractPipelinedChannel(transport, PrintLogger("pre-split")) {}
        channel.pipeline.addLast("recorder", Recorder(log))
        channel.ensureBridge()

        transport.waitToRead()
        transport.timer.scheduled.single().task()

        assertEquals(listOf("active", "inactive"), log, "the ending, and no close walk under the caller")
        assertFalse(channel.endedByTransport, "and no transport-reported end for a transport that reports none")
    }

    @Test
    fun `a bridge put in the chain by name is not a caller of the channel's own`() {
        // The one report a pre-split transport makes is answered from the
        // field, as it was before the peer's end of file became an event:
        // a bridge nothing named to the channel was Pipeline mode to it, and
        // still is. The wider question belongs to the report that is new.
        val transport = Transport()
        val channel = object : AbstractPipelinedChannel(transport, PrintLogger("pre-split")) {}
        channel.pipeline.addLast(PipelinedChannel.SUSPEND_BRIDGE_NAME, SuspendBridgeHandler())

        transport.onReadClosed?.invoke()

        assertFalse(channel.isOpen, "nothing told the channel it had a caller")
    }

    @Test
    fun `a bridge removed by name leaves the channel the caller's`() {
        // The mode is the field's answer, not the chain's: a channel whose
        // caller took the bridge out of the chain is still the caller's, and
        // closing it here would close it under them.
        val transport = Transport()
        val log = mutableListOf<String>()
        val channel = object : AbstractPipelinedChannel(transport, PrintLogger("pre-split")) {}
        channel.pipeline.addLast("recorder", Recorder(log))
        channel.ensureBridge()
        channel.pipeline.remove(PipelinedChannel.SUSPEND_BRIDGE_NAME)

        transport.onReadClosed?.invoke()

        assertTrue(channel.isOpen, "the channel is left for the caller to close")
    }

    @Test
    fun `a Coroutine-mode reader over a transport that reports one end is given the end of file`() =
        runTest(timeout = 15.seconds) {
            // The other half of the compatibility branch. In Pipeline mode the
            // ending reaches the handlers; here there are none, and the report
            // ends the read side for the caller: what the peer sent before it
            // goes with the ending, and the read after it is the end of file.
            val tracker = TrackingAllocator()
            val transport = Transport(tracker)
            val channel = object : AbstractPipelinedChannel(transport, PrintLogger("pre-split")) {}
            channel.ensureBridge()
            transport.onRead?.invoke(tracker.allocate(8).also { it.writeByte(1) })

            transport.onReadClosed?.invoke()

            val dst = tracker.allocate(8)
            assertEquals(-1, channel.read(dst), "the one report a pre-split transport makes ends the read side")
            dst.release()
            assertTrue(channel.isOpen, "and leaves a Coroutine-mode channel for its caller to close")
            assertEquals(0, tracker.outstandingCount, "what was queued went with the ending")
        }

    @Test
    fun `the read-idle timeout still reclaims a connection after the peer's end of file`() {
        val transport = Transport()
        var endHook = 0
        transport.onClosed = { endHook++ }

        transport.waitToRead()
        assertEquals(1, transport.timer.scheduled.size, "premise: waiting to read arms the timer")

        transport.peerFin()
        assertFalse(
            transport.timer.scheduled.single().cancelled,
            "the peer finishing leaves nobody else to reclaim a connection its caller never closes",
        )
        assertTrue(transport.isOpen, "premise: nothing has reclaimed it yet")
        var reportedAgain = 0
        transport.onReadClosed = { reportedAgain++ }

        transport.timer.scheduled.single().task()
        assertFalse(transport.isOpen, "so the timeout reclaims the connection the peer left half-closed")
        assertEquals(0, reportedAgain, "the one report this transport makes was made by the peer's end of file")
        assertEquals(0, endHook, "and not on the hook for the end alone, which it never fills")
    }

    @Test
    fun `reads waiting again after the peer's end of file arm the timer`() {
        val transport = Transport()

        transport.peerFin()
        transport.waitToRead()

        assertEquals(1, transport.timer.scheduled.size)
    }
}
