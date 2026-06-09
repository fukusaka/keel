package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import io.github.fukusaka.keel.pipeline.InboundHandler
import io.github.fukusaka.keel.pipeline.Pipeline
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import io.github.fukusaka.keel.pipeline.TimerHandle
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [RequestDeadlineHandler]'s header-complete deadline state machine,
 * driven deterministically: the test channel's [scheduleDeadline] override captures
 * the scheduled task and timeout instead of using a real timer, so the deadline can
 * be "fired" by invoking the captured task. No wall-clock timing involved.
 */
class RequestDeadlineHandlerTest {

    private class FakeTimerHandle : TimerHandle {
        var cancelled = false
        override fun touch() = Unit
        override fun cancel() { cancelled = true }
    }

    private val transport = TestIoTransport()

    private val scheduledTimeouts = mutableListOf<Long>()
    private var pendingTask: (() -> Unit)? = null
    private var lastHandle: FakeTimerHandle? = null

    // Captures scheduleDeadline so the test fires the deadline manually.
    private val channel = object : AbstractPipelinedChannel(transport, PrintLogger("test")) {
        override fun scheduleDeadline(delayMillis: Long, task: () -> Unit): TimerHandle {
            scheduledTimeouts.add(delayMillis)
            pendingTask = task
            return FakeTimerHandle().also { lastHandle = it }
        }
    }

    /** Swallows propagated reads so an unconsumed [HttpRequestHead] does not reach the tail. */
    private class Sink : InboundHandler {
        override fun onRead(ctx: PipelineHandlerContext, msg: Any) = Unit
    }

    private fun pipelineWith(timeoutMillis: Long): Pipeline {
        val p = channel.pipeline
        p.addLast("request-deadline", RequestDeadlineHandler(timeoutMillis))
        p.addLast("sink", Sink())
        return p
    }

    private fun head() = HttpRequestHead(HttpMethod.GET, "/")

    @Test
    fun `RequestStarted arms the header deadline with the configured timeout`() {
        pipelineWith(HEADER_TIMEOUT).notifyUserEvent(HttpRequestStarted)
        assertEquals(listOf(HEADER_TIMEOUT), scheduledTimeouts, "should schedule exactly the configured timeout")
        assertFalse(transport.closed, "arming must not close the channel")
    }

    @Test
    fun `a request head within the budget disarms the deadline`() {
        val p = pipelineWith(HEADER_TIMEOUT)
        p.notifyUserEvent(HttpRequestStarted)
        p.notifyRead(head())
        assertTrue(lastHandle!!.cancelled, "the head should cancel the pending header deadline")
        assertFalse(transport.closed, "a head within budget must not close the channel")
    }

    @Test
    fun `the deadline elapsing force-closes the channel`() {
        pipelineWith(HEADER_TIMEOUT).notifyUserEvent(HttpRequestStarted)
        // Simulate the timer firing: the head never arrived in time.
        pendingTask!!.invoke()
        assertTrue(transport.closed, "an elapsed header deadline must force-close the slow-header peer")
    }

    @Test
    fun `a head after the deadline fired does not re-cancel a fresh deadline`() {
        val p = pipelineWith(HEADER_TIMEOUT)
        p.notifyUserEvent(HttpRequestStarted)
        pendingTask!!.invoke() // deadline fired, channel closed, handle cleared
        val firedHandle = lastHandle!!
        // A late head (defensive): should not throw and should not touch the fired handle again.
        p.notifyRead(head())
        assertTrue(transport.closed)
        assertFalse(firedHandle.cancelled, "the already-fired deadline handle is not cancelled again")
    }

    @Test
    fun `a zero timeout disables the deadline entirely`() {
        pipelineWith(0).notifyUserEvent(HttpRequestStarted)
        assertTrue(scheduledTimeouts.isEmpty(), "a 0 timeout must not schedule a deadline")
        assertFalse(transport.closed)
    }

    @Test
    fun `a missing engine timer disables enforcement without closing`() {
        // A plain channel whose transport wires no EventLoop timer: scheduleDeadline
        // returns null, so the deadline cannot be armed. The handler must degrade
        // gracefully (no crash, no force-close) rather than enforce or fault.
        val plainTransport = TestIoTransport()
        val plainChannel = object : AbstractPipelinedChannel(plainTransport, PrintLogger("test")) {}
        plainChannel.pipeline.addLast("request-deadline", RequestDeadlineHandler(HEADER_TIMEOUT))
        plainChannel.pipeline.addLast("sink", Sink())
        plainChannel.pipeline.notifyUserEvent(HttpRequestStarted)
        assertFalse(plainTransport.closed, "an unenforceable deadline must not close the connection")
    }

    @Test
    fun `connection inactive cancels a pending deadline`() {
        val p = pipelineWith(HEADER_TIMEOUT)
        p.notifyUserEvent(HttpRequestStarted)
        p.notifyInactive()
        assertTrue(lastHandle!!.cancelled, "going inactive should cancel the pending deadline")
    }

    private companion object {
        const val HEADER_TIMEOUT = 5_000L
    }
}
