package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The ending is the last inbound event: nothing inbound is delivered after
 * `onInactive`. The pipeline drops a read (releasing it), a batch boundary,
 * a flush completion, a writability change and a user event that arrive
 * after the ending, and logs a late error rather than handing it to a
 * handler that has already ended. This is the live-path guard, distinct
 * from the drain's own — the ending is delivered here by `notifyInactive`
 * directly, so the pipeline is not being destroyed.
 */
class PipelineEndingGuardTest {

    private class Fixture(val tracker: TrackingAllocator = TrackingAllocator()) {
        val transport = TestIoTransport(tracker)
        val log = mutableListOf<String>()
        val channel: PipelinedChannel = object : AbstractPipelinedChannel(transport, PrintLogger("ending-guard")) {}
        val pipeline: Pipeline get() = channel.pipeline
        fun read(): IoBuf = tracker.allocate(8).also { it.writerIndex = 4 }
    }

    private open class Recorder(val name: String, val log: MutableList<String>) : DuplexHandler {
        override fun onActive(ctx: PipelineHandlerContext) {
            log.add("$name:active")
            ctx.propagateActive()
        }

        override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
            log.add("$name:read")
            ctx.propagateRead(msg)
        }

        override fun onReadComplete(ctx: PipelineHandlerContext) {
            log.add("$name:boundary")
            ctx.propagateReadComplete()
        }

        override fun onFlushComplete(ctx: PipelineHandlerContext) {
            log.add("$name:flushDone")
            ctx.propagateFlushComplete()
        }

        override fun onWritabilityChanged(ctx: PipelineHandlerContext, isWritable: Boolean) {
            log.add("$name:writable=$isWritable")
            ctx.propagateWritabilityChanged(isWritable)
        }

        override fun onUserEvent(ctx: PipelineHandlerContext, event: Any) {
            log.add("$name:event")
            ctx.propagateUserEvent(event)
        }

        override fun onError(ctx: PipelineHandlerContext, cause: Throwable) {
            log.add("$name:error")
            ctx.propagateError(cause)
        }

        override fun onInactive(ctx: PipelineHandlerContext) {
            log.add("$name:inactive")
            ctx.propagateInactive()
        }
    }

    @Test
    fun `no inbound event reaches a handler after the ending`() {
        val f = Fixture()
        f.pipeline.addLast("h", Recorder("h", f.log))
        f.pipeline.notifyInactive()
        assertEquals(listOf("h:active", "h:inactive"), f.log, "premise: the handler is active then ended")

        // Everything after the ending is dropped, not delivered.
        f.pipeline.notifyRead(f.read())
        f.pipeline.notifyReadComplete()
        f.pipeline.notifyFlushComplete()
        f.pipeline.notifyWritabilityChanged(true)
        f.pipeline.notifyUserEvent("late")
        f.pipeline.notifyError(IllegalStateException("late"))

        assertEquals(listOf("h:active", "h:inactive"), f.log, "nothing follows the ending")
        assertEquals(0, f.tracker.outstandingCount, "the read delivered after the ending is released")
    }

    @Test
    fun `a read after the ending is released and never reaches the handler`() {
        val f = Fixture()
        f.pipeline.addLast("h", Recorder("h", f.log))
        f.pipeline.notifyInactive()
        f.log.clear()

        f.pipeline.notifyRead(f.read())

        assertEquals(emptyList(), f.log, "the handler does not see a read after its ending")
        assertEquals(0, f.tracker.outstandingCount, "and the read is released, not leaked")
    }
}
