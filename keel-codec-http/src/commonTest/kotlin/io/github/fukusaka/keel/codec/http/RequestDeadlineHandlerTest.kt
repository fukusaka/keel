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
 * Unit tests for [RequestDeadlineHandler]'s header-complete and request-total
 * deadline state machine, driven deterministically: the test channel's
 * [scheduleDeadline] override captures each scheduled task + timeout instead of using
 * a real timer, so a deadline can be "fired" by invoking its captured task (looked up
 * by its distinct timeout value). No wall-clock timing involved.
 */
class RequestDeadlineHandlerTest {

    private class FakeTimerHandle : TimerHandle {
        var cancelled = false
        override fun touch() = Unit
        override fun cancel() { cancelled = true }
    }

    private class Scheduled(val millis: Long, val task: () -> Unit, val handle: FakeTimerHandle)

    private val transport = TestIoTransport()
    private val scheduled = mutableListOf<Scheduled>()

    // Captures every scheduleDeadline so the test fires/inspects deadlines by timeout.
    private val channel = object : AbstractPipelinedChannel(transport, PrintLogger("test")) {
        override fun scheduleDeadline(delayMillis: Long, task: () -> Unit): TimerHandle =
            FakeTimerHandle().also { scheduled.add(Scheduled(delayMillis, task, it)) }
    }

    private val scheduledTimeouts get() = scheduled.map { it.millis }
    private fun byTimeout(millis: Long) = scheduled.first { it.millis == millis }
    private fun fire(millis: Long) = byTimeout(millis).task.invoke()

    /** Swallows propagated reads so an unconsumed message does not reach the tail. */
    private class Sink : InboundHandler {
        override fun onRead(ctx: PipelineHandlerContext, msg: Any) = Unit
    }

    private fun pipelineWith(headerMillis: Long, requestMillis: Long = 0): Pipeline {
        val p = channel.pipeline
        p.addLast("request-deadline", RequestDeadlineHandler(headerMillis, requestMillis))
        p.addLast("sink", Sink())
        return p
    }

    private fun head() = HttpRequestHead(HttpMethod.GET, "/")
    private fun bodyEnd() = HttpBodyEnd.EMPTY

    // --- Header-complete deadline ---

    @Test
    fun `RequestStarted arms the header deadline with the configured timeout`() {
        pipelineWith(HEADER_TIMEOUT).notifyUserEvent(HttpRequestStarted)
        assertEquals(listOf(HEADER_TIMEOUT), scheduledTimeouts, "should schedule exactly the header timeout")
        assertFalse(transport.closed, "arming must not close the channel")
    }

    @Test
    fun `a request head within the budget disarms the header deadline`() {
        val p = pipelineWith(HEADER_TIMEOUT)
        p.notifyUserEvent(HttpRequestStarted)
        p.notifyRead(head())
        assertTrue(byTimeout(HEADER_TIMEOUT).handle.cancelled, "the head should cancel the header deadline")
        assertFalse(transport.closed)
    }

    @Test
    fun `the header deadline elapsing force-closes the channel`() {
        pipelineWith(HEADER_TIMEOUT).notifyUserEvent(HttpRequestStarted)
        fire(HEADER_TIMEOUT)
        assertTrue(transport.closed, "an elapsed header deadline must force-close the slow-header peer")
    }

    // --- Removal (protocol switch) ---

    @Test
    fun `removing the handler disarms the in-flight deadlines`() {
        val p = pipelineWith(HEADER_TIMEOUT, REQUEST_TIMEOUT)
        p.notifyUserEvent(HttpRequestStarted)
        // A protocol switch swaps out the HTTP codec mid-request (the WebSocket
        // upgrade does exactly this). The scheduled task closes the channel, so a
        // deadline left armed would fire at a connection no longer serving that
        // request — detaching the handler must disarm it.
        p.remove("request-deadline")
        assertTrue(byTimeout(HEADER_TIMEOUT).handle.cancelled, "removal must disarm the header deadline")
        assertTrue(byTimeout(REQUEST_TIMEOUT).handle.cancelled, "removal must disarm the request-total deadline")
        assertFalse(transport.closed, "removal itself must not close the channel")
    }

    // --- Request-total deadline ---

    @Test
    fun `RequestStarted arms the request-total deadline with the configured timeout`() {
        pipelineWith(headerMillis = 0, requestMillis = REQUEST_TIMEOUT).notifyUserEvent(HttpRequestStarted)
        assertEquals(listOf(REQUEST_TIMEOUT), scheduledTimeouts, "should schedule exactly the request-total timeout")
        assertFalse(transport.closed)
    }

    @Test
    fun `a body end within the budget disarms the request-total deadline`() {
        val p = pipelineWith(headerMillis = 0, requestMillis = REQUEST_TIMEOUT)
        p.notifyUserEvent(HttpRequestStarted)
        p.notifyRead(bodyEnd())
        assertTrue(byTimeout(REQUEST_TIMEOUT).handle.cancelled, "the body end should cancel the request-total deadline")
        assertFalse(transport.closed)
    }

    @Test
    fun `the request-total deadline elapsing force-closes the channel`() {
        pipelineWith(headerMillis = 0, requestMillis = REQUEST_TIMEOUT).notifyUserEvent(HttpRequestStarted)
        fire(REQUEST_TIMEOUT)
        assertTrue(transport.closed, "an elapsed request-total deadline must force-close the slow-body peer")
    }

    // --- Both deadlines together ---

    @Test
    fun `both deadlines arm on start and disarm at their own phase boundary`() {
        val p = pipelineWith(headerMillis = HEADER_TIMEOUT, requestMillis = REQUEST_TIMEOUT)
        p.notifyUserEvent(HttpRequestStarted)
        assertEquals(listOf(HEADER_TIMEOUT, REQUEST_TIMEOUT), scheduledTimeouts, "both deadlines arm at request start")
        // The head disarms only the header deadline; the request-total stays armed.
        p.notifyRead(head())
        assertTrue(byTimeout(HEADER_TIMEOUT).handle.cancelled, "head cancels header deadline")
        assertFalse(byTimeout(REQUEST_TIMEOUT).handle.cancelled, "head does not cancel the request-total deadline")
        // The body end disarms the request-total deadline.
        p.notifyRead(bodyEnd())
        assertTrue(byTimeout(REQUEST_TIMEOUT).handle.cancelled, "body end cancels the request-total deadline")
        assertFalse(transport.closed)
    }

    // --- Disabled / degradation ---

    @Test
    fun `both timeouts zero disables scheduling entirely`() {
        pipelineWith(headerMillis = 0, requestMillis = 0).notifyUserEvent(HttpRequestStarted)
        assertTrue(scheduled.isEmpty(), "no deadline should be scheduled when both are disabled")
        assertFalse(transport.closed)
    }

    @Test
    fun `a missing engine timer disables enforcement without closing`() {
        // A plain channel whose transport wires no EventLoop timer: scheduleDeadline
        // returns null, so the deadline cannot be armed. The handler must degrade
        // gracefully (no crash, no force-close) rather than enforce or fault.
        val plainTransport = TestIoTransport()
        val plainChannel = object : AbstractPipelinedChannel(plainTransport, PrintLogger("test")) {}
        plainChannel.pipeline.addLast("request-deadline", RequestDeadlineHandler(HEADER_TIMEOUT, REQUEST_TIMEOUT))
        plainChannel.pipeline.addLast("sink", Sink())
        plainChannel.pipeline.notifyUserEvent(HttpRequestStarted)
        assertFalse(plainTransport.closed, "an unenforceable deadline must not close the connection")
    }

    @Test
    fun `connection inactive cancels pending deadlines`() {
        val p = pipelineWith(headerMillis = HEADER_TIMEOUT, requestMillis = REQUEST_TIMEOUT)
        p.notifyUserEvent(HttpRequestStarted)
        p.notifyInactive()
        assertTrue(byTimeout(HEADER_TIMEOUT).handle.cancelled, "inactive cancels the header deadline")
        assertTrue(byTimeout(REQUEST_TIMEOUT).handle.cancelled, "inactive cancels the request-total deadline")
    }

    private companion object {
        const val HEADER_TIMEOUT = 5_000L
        const val REQUEST_TIMEOUT = 30_000L
    }
}
