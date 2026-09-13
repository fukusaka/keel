package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

private class RefusalTransport(tracker: TrackingAllocator) : TestIoTransport(tracker) {
    override val reportsEveryEndAsReadClosed: Boolean get() = false
}

private class Bed {
    val tracker = TrackingAllocator()
    val transport = RefusalTransport(tracker)
    val channel: PipelinedChannel = object : AbstractPipelinedChannel(transport, PrintLogger("refusal")) {}
    val pipeline: Pipeline get() = channel.pipeline

    fun bytes(vararg values: Byte): IoBuf = tracker.allocate(8).also { buf -> for (v in values) buf.writeByte(v) }
}

/**
 * Who refuses a read once the read side is over, and who does not.
 *
 * The report can be raised on the pipeline from inside the chain — a codec's
 * own end of stream — and the transport goes on reading past it, so something
 * has to decide what the bytes that follow are. The bridge decides, because
 * the bridge is what answered the caller: it releases them once it has told a
 * caller `-1`. The head cannot decide, because the phase it would read is
 * raised before the sweep and a handler can stop the sweep before the bridge
 * hears anything — the reader would then be starved of bytes it was never
 * told to stop expecting.
 */
class PipelineReadClosedRefusalTest {

    @Test
    fun `a read arriving after the read side was reported over never reaches a caller`() =
        runTest(timeout = 15.seconds) {
            val bed = Bed()
            // The bridge keeps the channel in Coroutine mode, so delivering the
            // report does not close the connection and the transport goes on
            // reading.
            bed.channel.ensureBridge()
            bed.transport.readEnabled = true

            bed.pipeline.notifyReadClosed()

            val dst = bed.tracker.allocate(16)
            assertEquals(-1, bed.channel.read(dst), "premise: the read side is over")

            bed.transport.onRead?.invoke(bed.bytes(1, 2, 3))

            dst.clear()
            assertEquals(-1, bed.channel.read(dst), "and stays over for this caller: what followed is not handed to it")
            dst.release()

            bed.channel.close()
            bed.transport.releaseWritten()
            bed.tracker.assertNoLeaks()
        }

    @Test
    fun `a report a handler consumes leaves the caller below it reading`() =
        runTest(timeout = 20.seconds) {
            val bed = Bed()
            bed.pipeline.addLast(
                "consuming",
                object : DuplexHandler {
                    override fun onActive(ctx: PipelineHandlerContext) = ctx.propagateActive()

                    // Consumes the report rather than passing it on, so nothing
                    // below hears it and no caller has been told `-1`.
                    override fun onReadClosed(ctx: PipelineHandlerContext) = Unit
                },
            )
            bed.channel.ensureBridge()
            bed.transport.readEnabled = true

            bed.pipeline.notifyReadClosed()
            bed.transport.onRead?.invoke(bed.bytes(1, 2, 3))

            val dst = bed.tracker.allocate(16)
            val n = withTimeoutOrNull(2.seconds) { bed.channel.read(dst) }
            dst.release()
            assertEquals(3, n, "the report stopped above the bridge, so the bytes are still the caller's")

            bed.channel.close()
            bed.transport.releaseWritten()
            bed.tracker.assertNoLeaks()
        }
}
