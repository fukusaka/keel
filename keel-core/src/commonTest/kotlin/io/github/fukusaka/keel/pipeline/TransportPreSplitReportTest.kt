package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
        val transport = Transport()
        // A listener for the end alone, so the report takes that hook and not
        // the fallback to the read side's — which would set the read side's
        // flag and let a check that reads only it answer correctly by luck.
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
    fun `the peer's end of file marks the terminal report as made`() {
        val transport = Transport()

        transport.peerFin()

        assertTrue(transport.terminalReportAlreadyMade())
    }

    @Test
    fun `the read-idle timeout still reclaims a connection after the peer's end of file`() {
        val transport = Transport()
        var ended = 0
        transport.onClosed = { ended++ }

        transport.waitToRead()
        assertEquals(1, transport.timer.scheduled.size, "premise: waiting to read arms the timer")

        transport.peerFin()
        assertFalse(
            transport.timer.scheduled.single().cancelled,
            "the peer finishing leaves nobody else to reclaim a connection its caller never closes",
        )

        transport.timer.scheduled.single().task()
        assertEquals(1, ended, "so the timeout still fires and ends the connection")
    }

    @Test
    fun `reads waiting again after the peer's end of file arm the timer`() {
        val transport = Transport()

        transport.peerFin()
        transport.waitToRead()

        assertEquals(1, transport.timer.scheduled.size)
    }
}
